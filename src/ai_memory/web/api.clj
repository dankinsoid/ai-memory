(ns ai-memory.web.api
  (:require [ai-memory.db.core :as db]
            [ai-memory.graph.node :as node]
            [ai-memory.graph.edge :as edge]
            [ai-memory.graph.write :as write]
            [ai-memory.tag.core :as tag]
            [ai-memory.tag.query :as tag-query]
            [ai-memory.tag.resolve :as tag-resolve]
            [ai-memory.blob.store :as blob-store]
            [ai-memory.session :as session]
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
  {:id      (str (:node/id n))
   :content (:node/content n)
   :type    (infer-d3-type n)
   :weight  (:node/weight n)
   :tags    (mapv :tag/name (:node/tag-refs n))})

(defn- edge->d3 [e]
  {:source (str (get-in e [:edge/from :node/id]))
   :target (str (get-in e [:edge/to :node/id]))
   :weight (:edge/weight e)})

(defn get-graph
  "Returns full graph for D3 visualization."
  [conn _req]
  (let [db    (db/db conn)
        nodes (d/q '[:find [(pull ?e [* {:node/tag-refs [:tag/name]}]) ...]
                      :where [?e :node/id]]
                    db)
        edges (edge/find-all db)]
    {:status 200
     :body   {:nodes (mapv node->d3 nodes)
              :links (mapv edge->d3 edges)}}))

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

(defn create-tag [conn _cfg req]
  (let [{:keys [name]} (:body-params req)]
    (tag/ensure-tag! conn name)
    {:status 201
     :body   {:tag/name name}}))

(defn count-facts [conn cfg req]
  (let [db       (db/db conn)
        tag-sets (get-in req [:body-params :tag-sets])]
    {:status 200
     :body   {:counts (tag-query/count-by-tag-sets db (:metrics cfg) tag-sets)}}))

(defn get-facts [conn cfg req]
  (let [db       (db/db conn)
        body     (:body-params req)
        tag-sets (:tag-sets body)
        limit    (:limit body 50)]
    {:status 200
     :body   {:results (tag-query/fetch-by-tag-sets db (:metrics cfg) tag-sets {:limit limit})}}))

(defn recall [conn _cfg req]
  (let [db   (db/db conn)
        body (:body-params req)
        tags (:tags body)]
    {:status 200
     :body   {:results (if (seq tags)
                         (tag-query/by-tags db {:tags tags})
                         [])}}))

;; --- Search ---

(defn search [conn cfg req]
  (let [db    (db/db conn)
        body  (:body-params req)
        query (:query body)
        top-k (:top-k body 10)]
    {:status 200
     :body   {:results (node/search db cfg query top-k)}}))

;; --- Remember (full: nodes + turn summary + session summary) ---

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
  [conn cfg session-id session-summary project]
  (let [db  (db/db conn)
        eid (find-session-fact db session-id)]
    (if eid
      @(d/transact conn [[:db/add eid :node/content session-summary]
                         [:db/add eid :node/updated-at (Date.)]])
      (let [tag-strs (if project [project] ["session-log"])
            tag-refs (tag-resolve/resolve-tags conn tag-strs)
            tick     (db/current-tick db)]
        (node/create-node conn cfg
          {:content    session-summary
           :tag-refs   tag-refs
           :tick       tick
           :session-id session-id})))))

(defn remember [conn cfg req]
  (let [body            (:body-params req)
        nodes           (:nodes body)
        turn-summary    (:turn-summary body)
        session-summary (:session-summary body)
        context-id      (:context-id body)
        project         (:project body)
        result          (when (seq nodes)
                          (write/remember conn cfg body))]
    ;; Turn summary → RAM buffer (NOT a fact)
    (when (and turn-summary context-id)
      (session/append-turn-summary! context-id turn-summary))
    ;; Session summary → Datomic session fact (searchable)
    (when (and session-summary context-id)
      (upsert-session-fact! conn cfg context-id session-summary project))
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
        meta-data {:id           (UUID/randomUUID)
                   :type         (keyword (or (:type body) "file"))
                   :title        (:title body)
                   :project      (:project body)
                   :created-at   now
                   :summary      (:summary body)
                   :source-path  (:path body)
                   :tags         (:tags body)
                   :section-count 1}]
    (blob-store/write-meta! base blob-dir meta-data)
    (let [tag-refs (when (seq (:tags body))
                     (tag-resolve/resolve-tags conn (:tags body)))
          blob-node (node/create-node conn cfg
                      {:content   (:summary body)
                       :tag-refs  tag-refs
                       :tick      (db/current-tick (db/db conn))
                       :blob-dir  blob-dir})]
      {:status 201
       :body   {:blob-dir blob-dir
                :blob-id  (:node-uuid blob-node)}})))

;; --- Session sync (called by Stop hook) ---

(defn- parse-instant [s]
  (when s (Instant/parse s)))

(defn- group-into-turns
  "Groups messages into turns. A turn starts with a user message."
  [messages]
  (when (seq messages)
    (let [groups (reduce (fn [acc msg]
                           (if (= "user" (:type msg))
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

(defn- format-turn-as-markdown
  "Formats a turn's messages as readable markdown."
  [messages]
  (str/join "\n\n"
    (map (fn [{:keys [role content]}]
           (let [text (if (string? content)
                        content
                        (->> content
                             (filter #(= "text" (:type %)))
                             (map :text)
                             (str/join "\n")))]
             (str "**" (or role "unknown") "**\n" text)))
         messages)))

(defn- derive-project [cwd]
  (when cwd
    (last (str/split cwd #"/"))))

(defn session-sync [conn cfg req]
  (let [body      (:body-params req)
        session-id (:session-id body)
        cwd        (:cwd body)
        messages   (:messages body)]
    (if-not session-id
      {:status 400 :body {:error "session_id required"}}
      (let [base      (:blob-path cfg)
            db        (db/db conn)
            project   (derive-project cwd)
            turns     (group-into-turns messages)
            summaries (session/get-turn-summaries session-id)

            session-eid (find-session-fact db session-id)
            existing-dir (when session-eid
                           (:node/blob-dir (d/pull db [:node/blob-dir] session-eid)))
            blob-dir    (or existing-dir
                            (blob-store/make-blob-dir-name base (str "session-" (subs session-id 0 8))))

            section-offset (blob-store/count-sections base blob-dir)

            sections-written
            (doall (map-indexed
                     (fn [i {:keys [messages t-start t-end]}]
                       (let [summary  (session/match-summary summaries t-start t-end)
                             idx      (+ section-offset i)
                             filename (blob-store/make-section-filename
                                        idx (or summary (str "turn-" idx)))
                             content  (format-turn-as-markdown messages)
                             lines    (count (str/split-lines content))]
                         (blob-store/write-section! base blob-dir filename content)
                         (blob-store/write-section-meta! base blob-dir filename
                           {:file filename :summary summary :lines lines
                            :timestamp (str t-start)})
                         filename))
                     turns))

            total-sections (+ section-offset (count sections-written))
            ;; Pull session_summary from Datomic (the rolling summary from memory_remember)
            session-summary (when session-eid
                              (:node/content (d/pull db [:node/content] session-eid)))
            existing-meta (blob-store/read-meta base blob-dir)
            meta-data (merge
                        (or existing-meta
                            {:id         (UUID/randomUUID)
                             :type       :session
                             :project    project
                             :created-at (Date.)
                             :session-id session-id})
                        {:section-count total-sections}
                        (when session-summary
                          {:session-summary session-summary}))]
        (blob-store/write-meta! base blob-dir meta-data)

        (if session-eid
          (when-not existing-dir
            @(d/transact conn [[:db/add session-eid :node/blob-dir blob-dir]
                               [:db/add session-eid :node/updated-at (Date.)]]))
          (let [tag-refs (when project
                           (tag-resolve/resolve-tags conn [project]))]
            (node/create-node conn cfg
              {:content    (or (:summary meta-data) "Session conversation")
               :tag-refs   tag-refs
               :tick       (db/current-tick db)
               :blob-dir   blob-dir
               :session-id session-id})))

        {:status 200
         :body   {:blob-dir blob-dir
                  :sections-added (count sections-written)
                  :total-sections total-sections}}))))

;; --- Session compact (agent stores detailed summary before context clear) ---

(defn session-compact [conn cfg req]
  (let [body            (:body-params req)
        session-id      (:session-id body)
        compact-summary (:compact-summary body)]
    (if-not (and session-id compact-summary)
      {:status 400 :body {:error "session_id and compact_summary required"}}
      (let [db          (db/db conn)
            base        (:blob-path cfg)
            session-eid (find-session-fact db session-id)
            blob-dir    (when session-eid
                          (:node/blob-dir (d/pull db [:node/blob-dir] session-eid)))]
        (if-not blob-dir
          {:status 404 :body {:error (str "No blob found for session " session-id)}}
          (let [;; Write compact.md to blob dir
                _    (blob-store/write-section! base blob-dir "compact.md" compact-summary)
                ;; Update meta.edn with compact summary
                meta (blob-store/read-meta base blob-dir)
                meta (assoc meta :compact-summary compact-summary)
                _    (blob-store/write-meta! base blob-dir meta)
                ;; Update session node content in Datomic (searchable)
                _    @(d/transact conn [[:db/add session-eid :node/content compact-summary]
                                        [:db/add session-eid :node/updated-at (Date.)]])]
            {:status 200
             :body   {:blob-dir blob-dir
                      :status   "compacted"}}))))))
