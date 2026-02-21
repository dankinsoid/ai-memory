(ns ai-memory.web.api
  (:require [ai-memory.db.core :as db]
            [ai-memory.graph.node :as node]
            [ai-memory.graph.edge :as edge]
            [ai-memory.graph.write :as write]
            [ai-memory.tag.core :as tag]
            [ai-memory.tag.query :as tag-query]
            [ai-memory.tag.resolve :as tag-resolve]
            [ai-memory.blob.store :as blob-store]
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
  [conn cfg session-id session-summary project]
  (let [db  (db/db conn)
        eid (find-session-fact db session-id)]
    (if eid
      (node/update-content! conn cfg eid session-summary)
      (let [tag-strs (cond-> ["session"]
                       project (conj project))
            tag-refs (tag-resolve/resolve-tags conn tag-strs)
            tick     (db/current-tick db)]
        (node/create-node conn cfg
          {:content    session-summary
           :tag-refs   tag-refs
           :tick       tick
           :session-id session-id})))))

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

(defn- extract-first-user-prompt
  "Extracts a truncated first user message as a fallback session summary.
   Returns nil if no user text found."
  [messages]
  (when-let [first-user (first (filter has-user-text? messages))]
    (let [text (extract-text (:content first-user))
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
            project (derive-project cwd)
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
                            {:id         (UUID/randomUUID)
                             :type       :session
                             :project    project
                             :created-at (Date.)
                             :session-id session-id})
                        {:turn-count total-turns}
                        (when session-summary
                          {:session-summary session-summary}))]
        (blob-store/write-meta! base blob-dir meta-data)

        ;; Create or link Datomic session node
        (if session-eid
          (when-not existing-dir
            @(d/transact conn [[:db/add session-eid :node/blob-dir blob-dir]
                               [:db/add session-eid :node/updated-at (Date.)]]))
          (let [tag-strs (cond-> ["session"]
                           project (conj project))
                tag-refs (tag-resolve/resolve-tags conn tag-strs)]
            (node/create-node conn cfg
              {:content    (or session-summary
                               (extract-first-user-prompt messages)
                               "Session conversation")
               :tag-refs   tag-refs
               :tick       (db/current-tick db)
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
        chunk-title (:chunk-title body)
        compact     (:compact body)]
    (if-not session-id
      {:status 400 :body {:error "session_id required"}}
      (let [base        (:blob-path cfg)
            db          (db/db conn)
            session-eid (find-session-fact db session-id)
            blob-dir    (or (when session-eid
                              (:node/blob-dir (d/pull db [:node/blob-dir] session-eid)))
                            (blob-store/find-session-blob-dir base session-id))

            ;; 1. Update session summary in Datomic + blob meta
            summary-result
            (when summary
              (upsert-session-fact! conn cfg session-id summary project)
              (when blob-dir
                (let [meta (blob-store/read-meta base blob-dir)]
                  (when meta
                    (blob-store/write-meta! base blob-dir
                      (assoc meta :session-summary summary)))))
              "updated")

            ;; 2. Name current chunk
            chunk-result
            (when (and chunk-title blob-dir)
              (or (blob-store/rename-current-chunk! base blob-dir chunk-title)
                  "no _current.md to rename"))

            ;; 3. Compact summary
            compact-result
            (when (and compact blob-dir session-eid)
              (blob-store/write-section! base blob-dir "compact.md" compact)
              (let [meta (blob-store/read-meta base blob-dir)]
                (when meta
                  (blob-store/write-meta! base blob-dir
                    (assoc meta :compact-summary compact))))
              (node/update-content! conn cfg session-eid compact)
              "stored")]

        {:status 200
         :body   (cond-> {:blob-dir (or blob-dir "not-yet-created")}
                   summary-result (assoc :summary summary-result)
                   chunk-result   (assoc :chunk chunk-result)
                   compact-result (assoc :compact compact-result))}))))
