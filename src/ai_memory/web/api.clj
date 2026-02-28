(ns ai-memory.web.api
  (:require [ai-memory.db.core :as db]
            [ai-memory.graph.node :as node]
            [ai-memory.graph.edge :as edge]
            [ai-memory.graph.write :as write]
            [ai-memory.graph.delete :as delete]
            [ai-memory.tag.query :as tag-query]
            [ai-memory.tag.resolve :as tag-resolve]
            [ai-memory.decay.core :as decay]
            [ai-memory.blob.store :as blob-store]
            [ai-memory.blob.exec :as blob-exec]
            [ai-memory.embedding.vector-store :as vs]
            [ai-memory.embedding.core :as embedding]
            [datomic.api :as d]
            [clojure.string :as str])
  (:import [java.util Date UUID]
           [java.time Instant]))

;; --- D3 visualization ---

(defn- infer-d3-type
  "Infers a display type from node attributes (for D3 visualization)."
  [n]
  (cond
    (:node/blob-dir n)    "blob"
    (:node/session-id n)  "session"
    :else                 "fact"))

(defn- node->d3 [n]
  {:id      (str (:db/id n))
   :content (:node/content n)
   :type    (infer-d3-type n)
   :weight  (:node/weight n)
   :tags    (mapv :tag/name (:node/tag-refs n))})

(defn- node->d3-with-ew [tick n]
  (assoc (node->d3 n)
    :effective-weight (decay/effective-weight
                        (or (:node/weight n) 0.0)
                        (or (:node/cycle n) 0)
                        tick
                        decay/default-decay-k)))

(defn- edge->d3 [e]
  {:source (str (get-in e [:edge/from :db/id]))
   :target (str (get-in e [:edge/to :db/id]))
   :weight (:edge/weight e)})

(defn get-graph
  "Returns full graph for D3 visualization."
  [conn _req]
  (let [db    (db/db conn)
        nodes (d/q '[:find [(pull ?e [* {:node/tag-refs [:tag/name]}]) ...]
                      :where [?e :node/content]]
                    db)
        edges (edge/find-all db)]
    {:status 200
     :body   {:nodes (mapv node->d3 nodes)
              :links (mapv edge->d3 edges)}}))

;; --- Stats ---

(defn get-stats
  "Returns global counts for the playground stat bar."
  [conn _req]
  (let [db (db/db conn)]
    {:status 200
     :body   {:fact-count (or (d/q '[:find (count ?n) . :where [?n :node/content]] db) 0)
              :tag-count  (or (d/q '[:find (count ?t) . :where [?t :tag/name]] db) 0)
              :edge-count (or (d/q '[:find (count ?e) . :where [?e :edge/id]] db) 0)
              :tick       (db/current-tick db)}}))

;; --- Diagnostics ---

(defn get-health
  "Health check with Qdrant reachability."
  [cfg _req]
  (let [info (vs/collection-info (:qdrant-url cfg))]
    {:status 200
     :body   {:status "ok"
              :qdrant (select-keys info [:reachable? :status])}}))

(defn get-diagnostics
  "Full Qdrant diagnostic: collection info + optional end-to-end test search.
   Pass ?test-query=<text> to also run embedding + vector search."
  [cfg req]
  (let [info       (vs/collection-info (:qdrant-url cfg))
        test-query (get-in req [:query-params "test-query"])]
    {:status 200
     :body   (cond-> {:qdrant info}
               (and (:reachable? info) test-query)
               (assoc :test-search
                      (try
                        (let [vec     (embedding/embed-query (:openai-api-key cfg) test-query)
                              results (vs/search (:qdrant-url cfg) vec 3)]
                          {:ok true :hits (count results)})
                        (catch Exception e
                          {:ok false :error (.getMessage e)}))))}))

;; --- Graph: top nodes ---

(def ^:private node-pull-spec-full
  [:db/id :node/content :node/weight :node/cycle :node/created-at :node/updated-at
   :node/blob-dir :node/session-id :node/sources
   {:node/tag-refs [:tag/name :tag/node-count]}])

(defn get-top-nodes
  "Returns highest effective-weight nodes as graph entry points."
  [conn _cfg req]
  (let [db    (db/db conn)
        limit (min 100 (or (some-> (get-in req [:query-params "limit"]) parse-long) 20))
        tag   (get-in req [:query-params "tag"])
        tick  (db/current-tick db)
        nodes (if tag
                (tag-query/by-tag db tag)
                (tag-query/all-nodes db))
        with-ew (mapv (fn [n]
                         (assoc n ::ew
                           (decay/effective-weight
                             (or (:node/weight n) 0.0)
                             (or (:node/cycle n) 0)
                             tick
                             decay/default-decay-k)))
                       nodes)
        top-n (->> with-ew (sort-by ::ew >) (take limit))]
    {:status 200
     :body   {:nodes (mapv (fn [n]
                             (-> (node->d3 n)
                                 (assoc :effective-weight (::ew n))
                                 (assoc :edge-count (count (edge/find-edges-from db (:db/id n))))))
                           top-n)}}))

;; --- Graph: neighborhood BFS ---

(defn- bfs-neighborhood
  "BFS traversal from center node, collecting nodes and edges up to `depth` hops."
  [db center-eid depth limit tick]
  (loop [frontier #{center-eid}
         visited  #{center-eid}
         nodes    []
         edges    []
         d        0]
    (if (or (>= d depth) (empty? frontier))
      {:nodes nodes :edges edges :has-more (and (< d depth) (not (empty? frontier)))}
      (let [new-edges   (mapcat #(edge/find-edges-from db %) frontier)
            neighbor-ids (->> new-edges
                              (keep #(get-in % [:edge/to :db/id]))
                              (remove visited)
                              distinct
                              (take limit))
            new-nodes   (mapv #(d/pull db node-pull-spec-full %) neighbor-ids)
            edges-d3    (mapv edge->d3 new-edges)]
        (recur (set neighbor-ids)
               (into visited neighbor-ids)
               (into nodes (mapv (partial node->d3-with-ew tick) new-nodes))
               (into edges edges-d3)
               (inc d))))))

(defn get-graph-neighborhood
  "Returns subgraph around a center node."
  [conn _cfg req]
  (let [db      (db/db conn)
        node-id (some-> (get-in req [:query-params "node_id"]) parse-long)
        depth   (min 3 (or (some-> (get-in req [:query-params "depth"]) parse-long) 1))
        limit   (min 200 (or (some-> (get-in req [:query-params "limit"]) parse-long) 50))]
    (if-not node-id
      {:status 400 :body {:error "node_id required"}}
      (let [tick   (db/current-tick db)
            center (d/pull db node-pull-spec-full node-id)
            result (bfs-neighborhood db node-id depth limit tick)]
        (if (:node/content center)
          {:status 200
           :body   (assoc result :center (node->d3-with-ew tick center))}
          {:status 404 :body {:error "Node not found"}})))))

;; --- Fact detail ---

(defn get-fact-detail
  "Returns single fact with full metadata, edges, and effective weight."
  [conn _cfg req]
  (let [db  (db/db conn)
        id  (some-> (get-in req [:path-params :id]) parse-long)]
    (if-not id
      {:status 400 :body {:error "id required"}}
      (let [node (d/pull db node-pull-spec-full id)
            tick (db/current-tick db)
            eff-w (decay/effective-weight
                    (or (:node/weight node) 0.0)
                    (or (:node/cycle node) 0)
                    tick decay/default-decay-k)
            edges (edge/find-edges-from db id)]
        (if (:node/content node)
          {:status 200
           :body   {:fact             (node->d3 node)
                    :effective-weight eff-w
                    :edges            (mapv edge->d3 edges)
                    :created-at       (str (:node/created-at node))
                    :updated-at       (str (:node/updated-at node))
                    :blob-dir         (:node/blob-dir node)
                    :session-id       (:node/session-id node)
                    :sources          (vec (:node/sources node))}}
          {:status 404 :body {:error "Not found"}})))))

;; --- Nodes ---

(defn list-nodes [_conn _req]
  {:status 200
   :body   []})

(defn create-node [conn cfg req]
  (let [body (:body-params req)]
    (node/create-node conn cfg body)
    {:status 201
     :body   {:status "created"}}))

;; --- Tags ---

(defn browse-tags [conn _cfg req]
  (let [db     (db/db conn)
        limit  (some-> (get-in req [:query-params "limit"]) parse-long)
        offset (some-> (get-in req [:query-params "offset"]) parse-long)]
    {:status 200
     :body   (tag-query/browse db {:limit  (or limit 50)
                                    :offset (or offset 0)})}))

(defn count-facts [conn cfg req]
  (let [db       (db/db conn)
        tag-sets (get-in req [:body-params :tag-sets])]
    {:status 200
     :body   {:counts (tag-query/count-by-tag-sets db (:metrics cfg) tag-sets)}}))

(defn get-facts [conn cfg req]
  (let [db      (db/db conn)
        body    (:body-params req)
        filters (:filters body)]
    {:status 200
     :body   {:results (tag-query/fetch-by-filters db cfg (:metrics cfg) filters)}}))

(defn recall [conn _cfg req]
  (let [db   (db/db conn)
        body (:body-params req)
        tags (:tags body)]
    {:status 200
     :body   {:results (if (seq tags)
                         (tag-query/by-tags db {:tags tags})
                         [])}}))

;; --- Reinforce ---

(defn reinforce [conn cfg req]
  (let [body           (:body-params req)
        reinforcements (:reinforcements body)
        factor         (or (:reinforcement-factor cfg) 0.5)]
    {:status 200
     :body   (let [results (mapv (fn [{:keys [id score]}]
                                   (node/reinforce-weight conn id score factor)
                                   {:id id :score score})
                                 reinforcements)]
               {:reinforced (count results)
                :details    results})}))

(defn promote-eternal [conn _cfg req]
  (let [id (:id (:body-params req))]
    (node/promote-eternal! conn id)
    {:status 200
     :body   {:promoted id}}))

;; --- Helpers ---

(defn- link-blob-dir!
  "Links blob-dir to an existing node."
  [conn eid blob-dir]
  (db/transact! conn [[:db/add eid :node/blob-dir blob-dir]]))

;; --- Remember (nodes + session summary) ---

(defn- find-session-fact
  "Finds existing session node by session-id."
  [db session-id]
  (when session-id
    (d/q '[:find ?e .
           :in $ ?sid
           :where [?e :node/session-id ?sid]]
         db session-id)))

(defn- upsert-session-fact!
  "Creates or updates the rolling session summary fact."
  [conn cfg session-id session-summary project tags]
  (let [db       (db/db conn)
        eid      (find-session-fact db session-id)
        tag-strs (distinct (concat ["session"] (when project [project]) tags))]
    (if eid
      (do (node/update-content! conn cfg eid session-summary)
          (let [tag-refs (tag-resolve/resolve-tags conn tag-strs)]
            (node/update-tag-refs conn eid tag-refs)))
      (let [tag-refs (tag-resolve/resolve-tags conn tag-strs)]
        (node/create-node conn cfg
          {:content    session-summary
           :tag-refs   tag-refs
           :session-id session-id})))))

(defn- find-project-fact [db project]
  (d/q '[:find ?e .
         :in $ ?pname
         :where
         [?t1 :tag/name "project"]
         [?e :node/tag-refs ?t1]
         [?t2 :tag/name ?pname]
         [?e :node/tag-refs ?t2]]
       db project))

(defn- upsert-project-fact! [conn cfg project summary tags]
  (let [db       (db/db conn)
        eid      (find-project-fact db project)
        tag-strs (distinct (concat ["project"] [project] tags))]
    (if eid
      (do (node/update-content! conn cfg eid summary)
          (node/update-tag-refs conn eid (tag-resolve/resolve-tags conn tag-strs)))
      (node/create-node conn cfg
        {:content  summary
         :tag-refs (tag-resolve/resolve-tags conn tag-strs)}))))

(defn project-update [conn cfg req]
  (let [{:keys [project summary tags]} (:body-params req)]
    (if (or (str/blank? project) (str/blank? summary))
      {:status 400 :body {:error "project and summary are required"}}
      (do (upsert-project-fact! conn cfg project summary (or tags []))
          {:status 200 :body {:project project}}))))

(defn remember [conn cfg req]
  (let [body   (:body-params req)
        nodes  (:nodes body)
        result (when (seq nodes)
                 (write/remember conn cfg body))]
    {:status 201
     :body   (or result {:nodes [] :edges-created 0})}))

;; --- Blobs ---

(defn list-blobs [conn _cfg req]
  (let [db    (db/db conn)
        limit (some-> (get-in req [:query-params "limit"]) parse-long)]
    {:status 200
     :body   {:blobs (node/find-blobs db {:limit (or limit 20)})}}))

(defn read-blob [_conn cfg req]
  (let [body     (:body-params req)
        blob-dir (:blob-dir body)
        section  (:section body)
        base     (:blob-path cfg)]
    (if section
      (if-let [result (blob-store/read-section-by-index base blob-dir section)]
        {:status 200 :body result}
        {:status 404 :body {:error (str "Section " section " not found in " blob-dir)}})
      (if-let [meta (blob-store/read-meta base blob-dir)]
        {:status 200 :body meta}
        {:status 404 :body {:error (str "Blob not found: " blob-dir)}}))))

(defn exec-blob [_conn cfg req]
  (let [body     (:body-params req)
        blob-dir (:blob-dir body)
        command  (:command body)]
    (if-not (and blob-dir command)
      {:status 400 :body {:error "blob_dir and command required"}}
      (try
        {:status 200 :body (blob-exec/exec-blob cfg blob-dir command)}
        (catch Exception e
          {:status 400 :body {:error (.getMessage e)}})))))

(defn store-file [conn cfg req]
  (let [body     (:body-params req)
        base     (:blob-path cfg)
        blob-dir (blob-store/make-blob-dir-name base (:title body))
        now      (Date.)
        ext      (cond
                   (:path body)    (last (str/split (:path body) #"\."))
                   (:content body) "md"
                   :else           "txt")
        filename (blob-store/make-section-filename 0 (:title body) :ext ext)
        _        (if (:content body)
                   (blob-store/write-section! base blob-dir filename (:content body))
                   (when (:path body)
                     (blob-store/write-section-binary! base blob-dir filename (:path body))))
        lines    (when (:content body) (count (str/split-lines (:content body))))
        _        (blob-store/write-section-meta! base blob-dir filename
                   {:file filename :summary (:summary body) :lines lines})
        meta-data (cond-> {:id           (UUID/randomUUID)
                           :type         (keyword (or (:type body) "file"))
                           :title        (:title body)
                           :created-at   now
                           :summary      (:summary body)
                           :tags         (:tags body)
                           :section-count 1}
                    (:project body)     (assoc :project (:project body))
                    (:path body)        (assoc :source-path (:path body)))]
    (blob-store/write-meta! base blob-dir meta-data)
    (let [tag-strs (distinct (:tags body))
          tag-refs (tag-resolve/resolve-tags conn tag-strs)
          blob-node (node/create-node conn cfg
                      {:content   (:summary body)
                       :tag-refs  tag-refs
                       :blob-dir  blob-dir})]
      {:status 201
       :body   {:blob-dir blob-dir
                :blob-id  (:node-eid blob-node)}})))

;; --- Session sync (called by UserPromptSubmit hook) ---

(defn- parse-instant [s]
  (when s (Instant/parse s)))

(defn- has-user-text?
  "Returns true if a message has actual user text (not just tool_result blocks)."
  [{:keys [type content]}]
  (and (= "user" type)
       (if (sequential? content)
         (some (fn [block]
                 (and (= "text" (:type block))
                      (not (str/blank? (:text block)))))
               content)
         (and (string? content) (not (str/blank? content))))))

(defn- group-into-turns
  "Groups messages into turns. A turn starts with a user text message
   (not tool_result). Tool exchanges stay in the same turn."
  [messages]
  (when (seq messages)
    (let [groups (reduce (fn [acc msg]
                           (if (has-user-text? msg)
                             (conj acc [msg])
                             (if (seq acc)
                               (update acc (dec (count acc)) conj msg)
                               (conj acc [msg]))))
                         [] messages)]
      (mapv (fn [msgs]
              {:messages msgs
               :t-start  (parse-instant (:timestamp (first msgs)))
               :t-end    (parse-instant (:timestamp (last msgs)))})
            groups))))

(defn- extract-text
  "Extracts text content from a message's content field."
  [content]
  (if (string? content)
    content
    (->> content
         (filter #(= "text" (:type %)))
         (map :text)
         (remove str/blank?)
         (str/join "\n"))))

(defn- format-turn-as-markdown
  "Formats a turn's messages as readable markdown.
   Skips messages with no text content (tool_use, tool_result, thinking)."
  [messages]
  (->> messages
       (keep (fn [{:keys [role content]}]
               (let [text (extract-text content)]
                 (when-not (str/blank? text)
                   (str "**" (or role "unknown") "**\n" text)))))
       (str/join "\n\n")))

(defn- strip-injected-tags
  "Strips injected XML-style context blocks from message text.
   Covers tags with hyphens or underscores in the name, e.g.:
   <system_instruction>, <system-reminder>, <ide_selection>, etc."
  [s]
  (when s
    (-> s
        (str/replace #"(?s)<[a-zA-Z][a-zA-Z0-9]*[-_][^>]*>.*?</[a-zA-Z][a-zA-Z0-9]*[-_][^>]*>" "")
        str/trim)))

(defn- extract-first-user-prompt
  "Extracts a truncated first user message as a fallback session summary.
   Returns nil if no user text found."
  [messages]
  (when-let [first-user (first (filter has-user-text? messages))]
    (let [text (strip-injected-tags (extract-text (:content first-user)))
          max-len 120]
      (when-not (str/blank? text)
        (if (<= (count text) max-len)
          text
          (str (subs text 0 max-len) "..."))))))

(defn- derive-project [cwd]
  (when cwd
    (last (str/split cwd #"/"))))

(defn session-sync [conn cfg req]
  (let [body       (:body-params req)
        session-id (:session-id body)
        cwd        (:cwd body)
        messages   (:messages body)]
    (if-not session-id
      {:status 400 :body {:error "session_id required"}}
      (let [base    (:blob-path cfg)
            db      (db/db conn)
            project (or (:project body) (derive-project cwd))
            turns   (group-into-turns messages)

            session-eid  (find-session-fact db session-id)
            existing-dir (or (when session-eid
                               (:node/blob-dir (d/pull db [:node/blob-dir] session-eid)))
                             (blob-store/find-session-blob-dir base session-id))
            blob-dir     (or existing-dir
                             (blob-store/make-blob-dir-name base (str "session-" (subs session-id 0 8))))

            ;; Count existing turns to number new ones correctly
            existing-meta (blob-store/read-meta base blob-dir)
            turn-offset   (or (:turn-count existing-meta) 0)

            ;; Format new turns as markdown and append to _current.md
            new-turn-texts
            (->> turns
                 (keep-indexed
                   (fn [i {:keys [messages t-start]}]
                     (let [content (format-turn-as-markdown messages)]
                       (when-not (str/blank? content)
                         (let [turn-num (+ turn-offset i 1)]
                           (str "## Turn " turn-num
                                (when t-start (str " (" t-start ")"))
                                "\n" content "\n\n"))))))
                 vec)

            append-result (when (seq new-turn-texts)
                            (blob-store/append-current-chunk!
                              base blob-dir (str/join new-turn-texts)))

            total-turns (+ turn-offset (count new-turn-texts))

            ;; Pull session_summary from Datomic (from memory_remember)
            session-summary (when session-eid
                              (:node/content (d/pull db [:node/content] session-eid)))

            meta-data (merge
                        (or existing-meta
                            (cond-> {:id         (UUID/randomUUID)
                                     :type       :session
                                     :created-at (Date.)
                                     :session-id session-id}
                              project (assoc :project project)))
                        {:turn-count total-turns}
                        (when (and project (not (:project existing-meta)))
                          {:project project})
                        (when session-summary
                          {:session-summary session-summary}))]
        (blob-store/write-meta! base blob-dir meta-data)

        ;; Create or link Datomic session node
        (if session-eid
          (do
            (when-not existing-dir
              (link-blob-dir! conn session-eid blob-dir))
            (when project
              (let [tag-refs (tag-resolve/resolve-tags conn [project])]
                (node/update-tag-refs conn session-eid tag-refs))))
          (let [tag-strs (cond-> ["session"]
                           project (conj project))
                tag-refs (tag-resolve/resolve-tags conn tag-strs)]
            (node/create-node conn cfg
              {:content    (or session-summary
                               (extract-first-user-prompt messages)
                               "Session conversation")
               :tag-refs   tag-refs
               :blob-dir   blob-dir
               :session-id session-id})))

        {:status 200
         :body   {:blob-dir           blob-dir
                  :turns-added        (count new-turn-texts)
                  :total-turns        total-turns
                  :current-chunk-size (or (:byte-count append-result) 0)}}))))

;; --- Unified session update (summary, chunk naming, compact) ---

(defn session-update [conn cfg req]
  (let [body        (:body-params req)
        session-id  (:session-id body)
        project     (:project body)
        summary     (:summary body)
        tags        (:tags body)
        chunk-title (:chunk-title body)
        compact     (:compact body)]
    (if-not session-id
      {:status 400 :body {:error "session_id required"}}
      (let [base        (:blob-path cfg)
            db          (db/db conn)
            session-eid (find-session-fact db session-id)
            existing-dir (or (when session-eid
                               (:node/blob-dir (d/pull db [:node/blob-dir] session-eid)))
                             (blob-store/find-session-blob-dir base session-id))

            ;; Create blob dir on first call if it doesn't exist yet
            blob-dir    (or existing-dir
                            (let [dir-name (blob-store/make-blob-dir-name
                                             base (str "session-" (subs session-id 0 8)))]
                              (blob-store/write-meta! base dir-name
                                (cond-> {:id         (UUID/randomUUID)
                                         :type       :session
                                         :created-at (Date.)
                                         :session-id session-id}
                                  project (assoc :project project)))
                              dir-name))

            ;; Link blob-dir to existing Datomic node if not yet linked
            _ (when (and session-eid (not existing-dir))
                (link-blob-dir! conn session-eid blob-dir))

            ;; 1. Update session summary in Datomic + blob meta
            summary-result
            (when summary
              (let [_ (upsert-session-fact! conn cfg session-id summary project tags)]
                ;; If node was just created by upsert, link blob-dir to it
                (when-not session-eid
                  (when-let [new-eid (find-session-fact (db/db conn) session-id)]
                    (link-blob-dir! conn new-eid blob-dir))))
              (let [meta (blob-store/read-meta base blob-dir)]
                (when meta
                  (blob-store/write-meta! base blob-dir
                    (assoc meta :session-summary summary))))
              (if existing-dir "updated" "created"))

            ;; Re-read session-eid after potential upsert
            session-eid (or session-eid (find-session-fact (db/db conn) session-id))

            ;; 2. Name current chunk
            chunk-result
            (when chunk-title
              (or (blob-store/rename-current-chunk! base blob-dir chunk-title)
                  "no _current.md to rename"))

            ;; 3. Compact summary (blob only, not stored as fact content)
            compact-result
            (when (and compact session-eid)
              (blob-store/write-section! base blob-dir "compact.md" compact)
              (let [meta (blob-store/read-meta base blob-dir)]
                (when meta
                  (blob-store/write-meta! base blob-dir
                    (assoc meta :compact-summary compact))))
              "stored")]

        {:status 200
         :body   (cond-> {:blob-dir blob-dir}
                   summary-result (assoc :summary summary-result)
                   chunk-result   (assoc :chunk chunk-result)
                   compact-result (assoc :compact compact-result))}))))

;; --- Session continuation (linking across /clear) ---

(defn session-continue [conn cfg req]
  (let [body            (:body-params req)
        prev-session-id (:prev-session-id body)
        session-id      (:session-id body)
        project         (:project body)]
    (if-not (and prev-session-id session-id)
      {:status 400 :body {:error "prev-session-id and session-id required"}}
      (let [db       (db/db conn)
            prev-eid (or (find-session-fact db prev-session-id)
                         ;; Self-heal: create prev session if Stop hook hasn't synced yet
                         (do (upsert-session-fact! conn cfg prev-session-id
                               (str "session " (subs prev-session-id 0 8)) project)
                             (find-session-fact (db/db conn) prev-session-id)))]
        (if-not prev-eid
          {:status 500 :body {:error "failed to create prev session fact"}}
          (let [;; Create new session fact if it doesn't exist yet
                _        (when-not (find-session-fact db session-id)
                           (upsert-session-fact! conn cfg session-id
                             (str "continuation of " prev-session-id) project))
                new-db   (db/db conn)
                new-eid  (find-session-fact new-db session-id)
                prev-dir (:node/blob-dir (d/pull new-db [:node/blob-dir] prev-eid))]
            ;; Create typed continuation edge: new → prev (tentative, weight=0.5)
            (edge/find-or-create-edge conn new-eid prev-eid 0.5
              {:type :continuation})
            {:status 200
             :body   (cond-> {:status "linked" :edge-created true}
                       prev-dir (assoc :prev-blob-dir prev-dir))}))))))

(defn- traverse-continuation-chain
  "Follows :continuation edges backward from session-eid, returns vec of
   {:eid :session-id :blob-dir :content} in order (immediate prev first)."
  [db start-eid max-depth]
  (loop [eid   start-eid
         chain []
         depth 0]
    (if (>= depth max-depth)
      chain
      (if-let [edge-data (edge/find-typed-edge-from db eid :continuation)]
        (let [prev      (:edge/to edge-data)
              prev-eid  (:db/id prev)]
          (recur prev-eid
                 (conj chain {:eid        prev-eid
                              :session-id (:node/session-id prev)
                              :blob-dir   (:node/blob-dir prev)
                              :content    (:node/content prev)
                              :edge-id    (:edge/id edge-data)
                              :edge-weight (:edge/weight edge-data)})
                 (inc depth)))
        chain))))

(defn session-chain [conn _cfg req]
  (let [body       (:body-params req)
        session-id (:session-id body)
        strengthen (:strengthen body)]
    (if-not session-id
      {:status 400 :body {:error "session-id required"}}
      (let [db  (db/db conn)
            eid (find-session-fact db session-id)]
        (if-not eid
          {:status 200 :body {:chain []}}
          (let [chain (traverse-continuation-chain db eid 10)]
            ;; On explicit load: promote first continuation edge to eternal (1.0)
            (when (and strengthen (seq chain))
              (edge/promote-eternal! conn (:edge-id (first chain))))
            {:status 200
             :body   {:chain (mapv #(dissoc % :eid) chain)}}))))))

;; --- Admin: deletion ---

(defn delete-fact
  "DELETE /api/facts/:id — true deletion from Datomic, Qdrant, and filesystem."
  [conn cfg req]
  (let [eid (some-> (get-in req [:path-params :id]) parse-long)]
    (if-not eid
      {:status 400 :body {:error "Invalid id"}}
      (try
        {:status 200 :body (delete/delete-node! conn cfg eid)}
        (catch Exception e
          {:status 404 :body {:error (ex-message e)}})))))

(defn reset-db
  "POST /api/admin/reset — wipe all facts, edges, blobs, and vectors."
  [conn cfg _req]
  {:status 200 :body (delete/reset-all! conn cfg)})

(defn reindex-vectors
  "POST /api/admin/reindex — re-embeds all non-entity nodes into Qdrant."
  [conn cfg _req]
  {:status 200 :body (node/reindex-all! (db/db conn) cfg)})
