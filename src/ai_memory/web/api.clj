(ns ai-memory.web.api
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.graph.node :as node]
            [ai-memory.graph.write :as write]
            [ai-memory.graph.delete :as delete]
            [ai-memory.tag.query :as tag-query]
            [ai-memory.tag.resolve :as tag-resolve]
            [ai-memory.decay.core :as decay]
            [ai-memory.blob.store :as blob-store]
            [ai-memory.blob.exec :as blob-exec]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util Date UUID]
           [java.time Instant]))

;; --- Store accessors ---

(defn- fact-store [stores] (:fact-store stores))
(defn- vector-store [stores] (:vector-store stores))
(defn- embedding [stores] (:embedding stores))

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
   :tags    (vec (:node/tags n))})

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
  [_conn stores _req]
  (let [fs    (fact-store stores)
        nodes (tag-query/all-nodes fs)
        edges (p/all-edges fs)]
    {:status 200
     :body   {:nodes (mapv node->d3 nodes)
              :links (mapv edge->d3 edges)}}))

;; --- Stats ---

(defn get-stats
  "Returns global counts for the playground stat bar."
  [_conn stores _req]
  (let [fs (fact-store stores)]
    {:status 200
     :body   {:fact-count (count (p/all-nodes fs))
              :tag-count  (count (p/all-tags fs))
              :edge-count (count (p/all-edges fs))
              :tick       (p/current-tick fs)}}))

;; --- Diagnostics ---

(defn get-health
  "Health check with vector store reachability."
  [stores _req]
  (let [info (p/store-info (vector-store stores))]
    {:status 200
     :body   {:status "ok"
              :qdrant (select-keys info [:reachable? :status])}}))

(defn get-diagnostics
  "Full diagnostic: collection info + optional end-to-end test search.
   Pass ?test-query=<text> to also run embedding + vector search."
  [stores req]
  (let [info       (p/store-info (vector-store stores))
        test-query (get-in req [:query-params "test-query"])]
    {:status 200
     :body   (cond-> {:qdrant info}
               (and (:reachable? info) test-query)
               (assoc :test-search
                      (try
                        (let [vec     (p/embed-query (embedding stores) test-query)
                              results (p/search (vector-store stores) vec 3 nil)]
                          {:ok true :hits (count results)})
                        (catch Exception e
                          {:ok false :error (.getMessage e)}))))}))

;; --- Graph: top nodes ---

(def ^:private node-pull-spec-full
  [:db/id :node/content :node/weight :node/cycle :node/created-at :node/updated-at
   :node/blob-dir :node/session-id :node/sources :node/tags])

(defn get-top-nodes
  "Returns highest effective-weight nodes as graph entry points."
  [stores req]
  (let [fs    (fact-store stores)
        limit (min 100 (or (some-> (get-in req [:query-params "limit"]) parse-long) 20))
        tag   (get-in req [:query-params "tag"])
        tick  (p/current-tick fs)
        nodes (if tag
                (tag-query/by-tag fs tag)
                (tag-query/all-nodes fs))
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
                                 (assoc :edge-count (count (p/find-edges-from fs (:db/id n))))))
                           top-n)}}))

;; --- Graph: neighborhood BFS ---

(defn- bfs-neighborhood
  "BFS traversal from center node, collecting nodes and edges up to `depth` hops."
  [fact-store center-eid depth limit tick]
  (loop [frontier #{center-eid}
         visited  #{center-eid}
         nodes    []
         edges    []
         d        0]
    (if (or (>= d depth) (empty? frontier))
      {:nodes nodes :edges edges :has-more (and (< d depth) (not (empty? frontier)))}
      (let [new-edges    (mapcat #(p/find-edges-from fact-store %) frontier)
            neighbor-ids (->> new-edges
                              (keep #(get-in % [:edge/to :db/id]))
                              (remove visited)
                              distinct
                              (take limit))
            new-nodes    (mapv #(p/find-node-by-eid fact-store %) neighbor-ids)
            edges-d3     (mapv edge->d3 new-edges)]
        (recur (set neighbor-ids)
               (into visited neighbor-ids)
               (into nodes (mapv (partial node->d3-with-ew tick) new-nodes))
               (into edges edges-d3)
               (inc d))))))

(defn get-graph-neighborhood
  "Returns subgraph around a center node."
  [_conn stores req]
  (let [fs      (fact-store stores)
        node-id (some-> (get-in req [:query-params "node_id"]) parse-long)
        depth   (min 3 (or (some-> (get-in req [:query-params "depth"]) parse-long) 1))
        limit   (min 200 (or (some-> (get-in req [:query-params "limit"]) parse-long) 50))]
    (if-not node-id
      {:status 400 :body {:error "node_id required"}}
      (let [tick   (p/current-tick fs)
            center (p/find-node-by-eid fs node-id)
            result (bfs-neighborhood fs node-id depth limit tick)]
        (if (:node/content center)
          {:status 200
           :body   (assoc result :center (node->d3-with-ew tick center))}
          {:status 404 :body {:error "Node not found"}})))))

;; --- Fact detail ---

(defn get-fact-detail
  "Returns single fact with full metadata, edges, and effective weight."
  [stores req]
  (let [fs  (fact-store stores)
        id  (some-> (get-in req [:path-params :id]) parse-long)]
    (if-not id
      {:status 400 :body {:error "id required"}}
      (let [node  (p/find-node-by-eid fs id)
            tick  (p/current-tick fs)
            eff-w (decay/effective-weight
                    (or (:node/weight node) 0.0)
                    (or (:node/cycle node) 0)
                    tick decay/default-decay-k)
            edges (p/find-edges-from fs id)]
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

(defn update-fact
  "PATCH /api/facts/:id — updates content, tags, and/or weight.
   Uses reinforce-node! to bump weight/cycle (same as dedup pipeline).
   All fields optional."
  [stores cfg req]
  (let [fs     (fact-store stores)
        vs     (vector-store stores)
        emb    (embedding stores)
        id     (some-> (get-in req [:path-params :id]) parse-long)
        body   (:body-params req)
        content (:content body)
        tags    (:tags body)
        weight  (:weight body)
        factor  (or (:reinforcement-factor cfg) 0.5)]
    (if-not id
      {:status 400 :body {:error "id required"}}
      (do
        (if (and content (not (str/blank? content)))
          ;; Content changed — reinforce with new content (bumps weight + cycle)
          (do
            (p/reinforce-node! fs id content 1.0 factor)
            (try
              (let [vector (p/embed-document emb content)]
                (p/upsert! vs id vector {}))
              (catch Exception e
                (log/warn e "Failed to re-embed updated node" id))))
          ;; No content change — still reinforce weight to mark as accessed
          (p/reinforce-weight! fs id 1.0 factor))
        (when (some? tags)
          (doseq [t tags] (p/ensure-tag! fs t))
          (p/replace-node-tags! fs id tags))
        (when (some? weight)
          (p/set-node-weight! fs id weight))
        {:status 200 :body {:status "updated"}}))))

;; --- Nodes ---

(defn list-nodes [_req]
  {:status 200
   :body   []})

(defn create-node [stores req]
  (let [body (:body-params req)]
    (p/create-node! (fact-store stores) body)
    {:status 201
     :body   {:status "created"}}))

;; --- Tags ---

(defn browse-tags [stores req]
  (let [limit  (some-> (get-in req [:query-params "limit"]) parse-long)
        offset (some-> (get-in req [:query-params "offset"]) parse-long)]
    {:status 200
     :body   (tag-query/browse (fact-store stores) {:limit  (or limit 50)
                                                     :offset (or offset 0)})}))

(defn count-facts [stores cfg req]
  (let [tag-sets (get-in req [:body-params :tag-sets])]
    {:status 200
     :body   {:counts (tag-query/count-by-tag-sets (fact-store stores) (:metrics cfg) tag-sets)}}))

(defn get-facts [stores cfg req]
  (let [body    (:body-params req)
        filters (:filters body)]
    {:status 200
     :body   {:results (tag-query/fetch-by-filters
                         (fact-store stores)
                         (vector-store stores)
                         (embedding stores)
                         (:metrics cfg)
                         filters)}}))

;; @ai-generated(guided)
(defn resolve-tags [stores req]
  (let [body       (:body-params req)
        candidates (:candidates body)
        threshold  (:threshold body)
        top-k      (:top-k body)]
    {:status 200
     :body   {:results (tag-resolve/resolve-tags
                         (fact-store stores)
                         (embedding stores)
                         candidates
                         (cond-> {}
                           threshold (assoc :threshold threshold)
                           top-k     (assoc :top-k top-k)))}}))

(defn recall [stores req]
  (let [body (:body-params req)
        tags (:tags body)]
    {:status 200
     :body   {:results (if (seq tags)
                         (tag-query/by-tags (fact-store stores) {:tags tags})
                         [])}}))

;; --- Reinforce ---

(defn reinforce [stores cfg req]
  (let [fs             (fact-store stores)
        body           (:body-params req)
        reinforcements (:reinforcements body)
        factor         (or (:reinforcement-factor cfg) 0.5)]
    {:status 200
     :body   (let [results (mapv (fn [{:keys [id score]}]
                                   (p/reinforce-weight! fs id score factor)
                                   {:id id :score score})
                                 reinforcements)]
               {:reinforced (count results)
                :details    results})}))

(defn promote-eternal [stores req]
  (let [id (:id (:body-params req))]
    (p/promote-edge-eternal! (fact-store stores) id)
    {:status 200
     :body   {:promoted id}}))

;; --- Helpers ---

(defn- link-blob-dir!
  "Links blob-dir to an existing node via fact-store."
  [fact-store eid blob-dir]
  (p/set-node-blob-dir! fact-store eid blob-dir))

;; --- Remember (nodes + session summary) ---

(defn- find-session-fact
  "Finds existing session node by session-id."
  [fact-store session-id]
  (when session-id
    (when-let [node (p/find-nodes-by-session fact-store session-id)]
      (:db/id node))))

(defn- upsert-session-fact!
  "Creates or updates the rolling session summary fact."
  [stores session-id session-summary project tags]
  (let [fs       (fact-store stores)
        vs       (vector-store stores)
        emb      (embedding stores)
        eid      (find-session-fact fs session-id)
        tag-strs (distinct (concat ["session"] (when project [(str "project/" project)]) tags))]
    (let [tag-vec (mapv str/trim tag-strs)]
      (doseq [t tag-vec] (p/ensure-tag! fs t))
      (if eid
        (do
          (p/update-node-content! fs eid session-summary)
          (try
            (let [vector (p/embed-document emb session-summary)]
              (p/upsert! vs eid vector {}))
            (catch Exception e
              (log/warn e "Failed to re-embed session node" eid)))
          (p/update-node-tags! fs eid tag-vec))
        (let [result (p/create-node! fs
                       {:content    session-summary
                        :tags       tag-vec
                        :session-id session-id})
              new-eid (:id result)]
          (try
            (let [vector (p/embed-document emb session-summary)]
              (p/upsert! vs new-eid vector {}))
            (catch Exception e
              (log/warn e "Failed to embed new session node" new-eid))))))))

(defn- find-project-fact [fact-store project]
  ;; Find entity with both "project" tag and the project/<name> tag (intersection)
  (when-let [nodes (p/find-nodes-by-tags fact-store
                                         ["project" (str "project/" project)]
                                         nil)]
    (:db/id (first nodes))))

(defn- upsert-project-fact! [stores project summary tags]
  (let [fs       (fact-store stores)
        vs       (vector-store stores)
        emb      (embedding stores)
        eid      (find-project-fact fs project)
        tag-strs (mapv str/trim (distinct (concat ["project" (str "project/" project)] tags)))]
    (doseq [t tag-strs] (p/ensure-tag! fs t))
    (if eid
      (do
        (p/update-node-content! fs eid summary)
        (try
          (let [vector (p/embed-document emb summary)]
            (p/upsert! vs eid vector {}))
          (catch Exception e
            (log/warn e "Failed to re-embed project node" eid)))
        (p/update-node-tags! fs eid tag-strs))
      (let [result  (p/create-node! fs {:content summary :tags tag-strs})
            new-eid (:id result)]
        (try
          (let [vector (p/embed-document emb summary)]
            (p/upsert! vs new-eid vector {}))
          (catch Exception e
            (log/warn e "Failed to embed new project node" new-eid)))))))

(defn project-update [stores cfg req]
  (let [{:keys [project summary tags]} (:body-params req)]
    (if (or (str/blank? project) (str/blank? summary))
      {:status 400 :body {:error "project and summary are required"}}
      (do (upsert-project-fact! stores project summary (or tags []))
          {:status 200 :body {:project project}}))))

(defn remember [stores cfg req]
  (let [body   (:body-params req)
        nodes  (:nodes body)
        params (assoc body :metrics (:metrics cfg))
        result (when (seq nodes)
                 (write/remember (fact-store stores)
                                 (vector-store stores)
                                 (embedding stores)
                                 params))]
    {:status 201
     :body   (or result {:nodes [] :edges-created 0})}))

;; --- Blobs ---

(defn list-blobs [stores req]
  (let [limit (some-> (get-in req [:query-params "limit"]) parse-long)]
    {:status 200
     :body   {:blobs (p/find-blob-nodes (fact-store stores) {:limit (or limit 20)})}}))

(defn read-blob [cfg req]
  (let [body         (:body-params req)
        blob-dir-raw (:blob-dir body)
        section      (:section body)
        base         (:blob-path cfg)
        blob-dir     (or (blob-store/resolve-blob-dir base blob-dir-raw)
                         blob-dir-raw)]
    (if section
      (if-let [result (blob-store/read-section-by-index base blob-dir section)]
        {:status 200 :body result}
        {:status 404 :body {:error (str "Section " section " not found in " blob-dir-raw)}})
      (if-let [meta (blob-store/read-meta base blob-dir)]
        {:status 200 :body meta}
        {:status 404 :body {:error (str "Blob not found: " blob-dir-raw)}}))))

(defn exec-blob [cfg req]
  (let [body     (:body-params req)
        blob-dir (:blob-dir body)
        command  (:command body)]
    (if-not (and blob-dir command)
      {:status 400 :body {:error "blob_dir and command required"}}
      (try
        {:status 200 :body (blob-exec/exec-blob cfg blob-dir command)}
        (catch Exception e
          {:status 400 :body {:error (.getMessage e)}})))))

(defn store-file [stores cfg req]
  (let [fs       (fact-store stores)
        vs       (vector-store stores)
        emb      (embedding stores)
        body     (:body-params req)
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
    (let [tags (mapv str/trim (distinct (:tags body)))]
      (doseq [t tags] (p/ensure-tag! fs t))
      (let [result  (p/create-node! fs
                      {:content  (:summary body)
                       :tags     tags
                       :blob-dir blob-dir})
            blob-id (:id result)]
        ;; Embed the blob node summary
        (try
          (let [vector (p/embed-document emb (:summary body))]
            (p/upsert! vs blob-id vector {}))
          (catch Exception e
            (log/warn e "Failed to embed blob node" blob-id)))
        {:status 201
         :body   {:blob-dir blob-dir
                  :blob-id  blob-id}}))))

(defn update-blob
  "PATCH /api/blobs/file — updates blob content and/or metadata.
   Reinforce the associated node (same as dedup pipeline)."
  [stores cfg req]
  (let [fs      (fact-store stores)
        vs      (vector-store stores)
        emb     (embedding stores)
        body    (:body-params req)
        base    (:blob-path cfg)
        blob-dir (:blob-dir body)
        factor  (or (:reinforcement-factor cfg) 0.5)]
    (if (str/blank? blob-dir)
      {:status 400 :body {:error "blob-dir required"}}
      (let [node (p/find-node-by-blob-dir fs blob-dir)]
        (if-not node
          {:status 404 :body {:error (str "No node found for blob-dir: " blob-dir)}}
          (let [eid     (:db/id node)
                summary (:summary body)
                content (:content body)
                tags    (:tags body)
                meta    (blob-store/read-meta base blob-dir)]
            ;; Update blob file on disk if content provided
            (when (and content (not (str/blank? content)))
              ;; Overwrite first section (index 0) — the main content file
              (when-let [section-file (blob-store/read-section-by-index base blob-dir 0)]
                (blob-store/write-section! base blob-dir
                  (get-in section-file [:meta :file]) content)))
            ;; Update meta.edn if summary or tags changed
            (when (or summary tags)
              (let [updated-meta (cond-> (or meta {})
                                   summary (assoc :summary summary)
                                   tags    (assoc :tags tags))]
                (blob-store/write-meta! base blob-dir updated-meta)))
            ;; Reinforce node — bump weight/cycle like dedup
            (if (and summary (not (str/blank? summary)))
              (do
                (p/reinforce-node! fs eid summary 1.0 factor)
                (try
                  (let [vector (p/embed-document emb summary)]
                    (p/upsert! vs eid vector {}))
                  (catch Exception e
                    (log/warn e "Failed to re-embed blob node" eid))))
              (p/reinforce-weight! fs eid 1.0 factor))
            ;; Update tags on node
            (when (some? tags)
              (doseq [t tags] (p/ensure-tag! fs t))
              (p/replace-node-tags! fs eid tags))
            {:status 200
             :body   {:blob-dir blob-dir
                      :blob-id  eid}}))))))

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

(def ^:private injected-tag-names
  ["system-reminder" "system_reminder" "system_instruction"
   "local-command-stdout" "local-command-caveat"
   "ide_selection" "ide_file" "ide_opened_file"
   "user-prompt-submit-hook"])

(def ^:private injected-tags-re
  (re-pattern (str "(?s)<(" (str/join "|" injected-tag-names) ")>.*?</\\1>")))

(defn- strip-injected-tags
  "Strips known injected XML-style context blocks from message text.
   Only removes tags in the explicit allowlist to avoid clobbering legitimate content."
  [s]
  (when s
    (-> s
        (str/replace injected-tags-re "")
        str/trim)))

(defn- format-turn-as-markdown
  "Formats a turn's messages as readable markdown.
   Skips messages with no text content (tool_use, tool_result, thinking)."
  [messages]
  (->> messages
       (keep (fn [{:keys [role content]}]
               (let [text (strip-injected-tags (extract-text content))]
                 (when-not (str/blank? text)
                   (str "**" (or role "unknown") "**\n" text)))))
       (str/join "\n\n")))

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

(defn- merge-git-context
  "Merges new git context with existing, preserving start-commit and remote."
  [existing-git new-git]
  (when new-git
    (let [start  (or (:start-commit existing-git) (:end-commit new-git))
          remote (or (:remote new-git) (:remote existing-git))]
      (cond-> new-git
        start  (assoc :start-commit start)
        remote (assoc :remote remote)))))

(defn session-sync [_conn stores cfg req]
  (let [fs         (fact-store stores)
        vs         (vector-store stores)
        emb        (embedding stores)
        body       (:body-params req)
        session-id (:session-id body)
        cwd        (:cwd body)
        messages   (:messages body)]
    (if-not session-id
      {:status 400 :body {:error "session_id required"}}
      (let [base    (:blob-path cfg)
            project (or (:project body) (derive-project cwd))
            turns   (group-into-turns messages)

            session-eid  (find-session-fact fs session-id)
            ;; Short name from fact store (nil if not linked)
            datomic-dir  (when session-eid
                           (:node/blob-dir (p/find-node fs session-eid)))
            ;; Resolved filesystem path (may include projects/ prefix)
            blob-dir     (or (when datomic-dir
                               (or (blob-store/resolve-blob-dir base datomic-dir)
                                   datomic-dir))
                             (blob-store/find-session-blob-dir base session-id)
                             (blob-store/make-blob-dir-name
                               base (str "session-" (subs session-id 0 8))
                               :project project))
            ;; Short name for DB and API response
            blob-dir-short (blob-store/blob-dir-name blob-dir)

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

            ;; Pull session_summary from fact store (from memory_remember)
            session-summary (when session-eid
                              (:node/content (p/find-node fs session-eid)))

            git-context (merge-git-context (:git existing-meta) (:git body))

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
                          {:session-summary session-summary})
                        (when git-context
                          {:git git-context}))]
        (blob-store/write-meta! base blob-dir meta-data)

        ;; Create or link session node
        (if session-eid
          (do
            (when-not datomic-dir
              (link-blob-dir! fs session-eid blob-dir-short))
            (when project
              (let [proj-tag (str "project/" project)]
                (p/ensure-tag! fs proj-tag)
                (p/update-node-tags! fs session-eid [proj-tag]))))
          (let [tags (cond-> ["session"]
                       project (conj (str "project/" project)))]
            (doseq [t tags] (p/ensure-tag! fs t))
            (let [content (or session-summary
                              (extract-first-user-prompt messages)
                              "Session conversation")
                  result  (p/create-node! fs
                            {:content    content
                             :tags       tags
                             :blob-dir   blob-dir-short
                             :session-id session-id})
                  new-eid (:id result)]
              (try
                (let [vector (p/embed-document emb content)]
                  (p/upsert! vs new-eid vector {}))
                (catch Exception e
                  (log/warn e "Failed to embed new session node" new-eid))))))

        {:status 200
         :body   {:blob-dir           blob-dir-short
                  :turns-added        (count new-turn-texts)
                  :total-turns        total-turns
                  :current-chunk-size (or (:byte-count append-result) 0)}}))))

;; --- Unified session update (summary, chunk naming, compact) ---

(defn session-update [_conn stores cfg req]
  (let [fs          (fact-store stores)
        body        (:body-params req)
        session-id  (:session-id body)
        project     (:project body)
        title       (:title body)
        summary     (:summary body)
        tags        (:tags body)
        chunk-title (:chunk-title body)
        compact     (:compact body)]
    (if-not session-id
      {:status 400 :body {:error "session_id required"}}
      (let [base        (:blob-path cfg)
            session-eid (find-session-fact fs session-id)
            ;; Short name from fact store (nil if not linked)
            datomic-dir (when session-eid
                          (:node/blob-dir (p/find-node fs session-eid)))
            ;; Resolved filesystem path
            blob-dir    (or (when datomic-dir
                              (or (blob-store/resolve-blob-dir base datomic-dir)
                                  datomic-dir))
                            (blob-store/find-session-blob-dir base session-id)
                            (let [dir-name (blob-store/make-blob-dir-name
                                             base (str "session-" (subs session-id 0 8))
                                             :project project)]
                              (blob-store/write-meta! base dir-name
                                (cond-> {:id         (UUID/randomUUID)
                                         :type       :session
                                         :created-at (Date.)
                                         :session-id session-id}
                                  project (assoc :project project)))
                              dir-name))
            blob-dir-short (blob-store/blob-dir-name blob-dir)

            ;; Link blob-dir to existing node if not yet linked
            _ (when (and session-eid (not datomic-dir))
                (link-blob-dir! fs session-eid blob-dir-short))

            ;; 1. Update session summary in fact store + blob meta
            summary-result
            (when summary
              (let [content (if title (str title "\n" summary) summary)
                    _ (upsert-session-fact! stores session-id content project tags)]
                ;; If node was just created by upsert, link blob-dir to it
                (when-not session-eid
                  (when-let [new-eid (find-session-fact fs session-id)]
                    (link-blob-dir! fs new-eid blob-dir-short))))
              (let [meta (blob-store/read-meta base blob-dir)]
                (when meta
                  (blob-store/write-meta! base blob-dir
                    (assoc meta :session-summary summary))))
              (if datomic-dir "updated" "created"))

            ;; 1b. Update session title in blob meta (title-only, no DB)
            _ (when title
                (let [meta (blob-store/read-meta base blob-dir)]
                  (when meta
                    (blob-store/write-meta! base blob-dir
                      (assoc meta :title title)))))

            ;; Re-read session-eid after potential upsert
            session-eid (or session-eid (find-session-fact fs session-id))

            ;; 2. Name current chunk
            chunk-result
            (when chunk-title
              (or (blob-store/rename-current-chunk! base blob-dir chunk-title)
                  "no _current.md to rename"))

            ;; 3. Compact summary (blob only, not stored as fact content)
            compact-result
            (when (and compact session-eid)
              (blob-store/write-section! base blob-dir "compact.md" compact)
              (node/embed-file! (vector-store stores) (embedding stores)
                                session-eid blob-dir-short "compact.md" compact)
              "stored")]

        {:status 200
         :body   (cond-> {:blob-dir blob-dir-short}
                   summary-result (assoc :summary summary-result)
                   chunk-result   (assoc :chunk chunk-result)
                   compact-result (assoc :compact compact-result))}))))

;; --- Session continuation (linking across /clear) ---

(defn session-continue [_conn stores cfg req]
  (let [fs              (fact-store stores)
        body            (:body-params req)
        prev-session-id (:prev-session-id body)
        session-id      (:session-id body)
        project         (:project body)]
    (if-not (and prev-session-id session-id)
      {:status 400 :body {:error "prev-session-id and session-id required"}}
      (let [prev-eid (or (find-session-fact fs prev-session-id)
                         ;; Self-heal: create prev session if Stop hook hasn't synced yet
                         (do (upsert-session-fact! stores prev-session-id
                               (str "session " (subs prev-session-id 0 8)) project nil)
                             (find-session-fact fs prev-session-id)))]
        (if-not prev-eid
          {:status 500 :body {:error "failed to create prev session fact"}}
          (let [;; Create new session fact if it doesn't exist yet
                _        (when-not (find-session-fact fs session-id)
                           (upsert-session-fact! stores session-id
                             (str "continuation of " prev-session-id) project nil))
                new-eid  (find-session-fact fs session-id)
                prev-dir (:node/blob-dir (p/find-node fs prev-eid))]
            ;; Create typed continuation edge: new → prev (tentative, weight=0.5)
            (p/find-or-create-edge! fs new-eid prev-eid 0.5 {:type :continuation})
            {:status 200
             :body   (cond-> {:status "linked" :edge-created true}
                       prev-dir (assoc :prev-blob-dir prev-dir))}))))))

(defn- traverse-continuation-chain
  "Follows :continuation edges backward from session-eid, returns vec of
   {:eid :session-id :blob-dir :content} in order (immediate prev first)."
  [fact-store start-eid max-depth]
  (loop [eid   start-eid
         chain []
         depth 0]
    (if (>= depth max-depth)
      chain
      (if-let [edge-data (p/find-typed-edge-from fact-store eid :continuation)]
        (let [prev      (:edge/to edge-data)
              prev-eid  (:db/id prev)]
          (recur prev-eid
                 (conj chain {:eid         prev-eid
                              :session-id  (:node/session-id prev)
                              :blob-dir    (:node/blob-dir prev)
                              :content     (:node/content prev)
                              :edge-id     (:edge/id edge-data)
                              :edge-weight (:edge/weight edge-data)})
                 (inc depth)))
        chain))))

(defn session-chain [_conn stores req]
  (let [fs         (fact-store stores)
        body       (:body-params req)
        session-id (:session-id body)
        strengthen (:strengthen body)]
    (if-not session-id
      {:status 400 :body {:error "session-id required"}}
      (let [eid (find-session-fact fs session-id)]
        (if-not eid
          {:status 200 :body {:chain []}}
          (let [chain (traverse-continuation-chain fs eid 10)]
            ;; On explicit load: promote first continuation edge to eternal (1.0)
            (when (and strengthen (seq chain))
              (p/promote-edge-eternal! fs (:edge-id (first chain))))
            {:status 200
             :body   {:chain (mapv #(dissoc % :eid) chain)}}))))))

;; --- Admin: deletion ---

(defn delete-fact
  "DELETE /api/facts/:id — true deletion from fact store, vector store, and filesystem."
  [stores cfg req]
  (let [eid (some-> (get-in req [:path-params :id]) parse-long)]
    (if-not eid
      {:status 400 :body {:error "Invalid id"}}
      (try
        {:status 200
         :body   (delete/delete-node! (fact-store stores)
                                      (vector-store stores)
                                      (:blob-path cfg)
                                      eid)}
        (catch Exception e
          {:status 404 :body {:error (ex-message e)}})))))

(defn reset-db
  "POST /api/admin/reset — wipe all facts, edges, blobs, and vectors."
  [stores cfg _req]
  {:status 200
   :body   (delete/reset-all! (fact-store stores)
                               (vector-store stores)
                               (:blob-path cfg))})

(defn reindex-vectors
  "POST /api/admin/reindex — re-embeds all non-entity nodes into vector store."
  [stores cfg _req]
  {:status 200
   :body   (node/reindex-all! (fact-store stores)
                               (vector-store stores)
                               (embedding stores)
                               (:blob-path cfg))})
