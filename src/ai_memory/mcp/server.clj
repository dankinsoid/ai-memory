(ns ai-memory.mcp.server
  "MCP server for agent memory access.
   Tools: browse-tags, count-facts, get-facts, remember, create-tag,
   read-blob, store-conversation, store-file."
  (:require [ai-memory.graph.write :as write]
            [ai-memory.graph.node :as node]
            [ai-memory.tag.core :as tag]
            [ai-memory.tag.query :as tag-query]
            [ai-memory.tag.resolve :as tag-resolve]
            [ai-memory.blob.store :as blob-store]
            [ai-memory.blob.ingest :as ingest]
            [ai-memory.mcp.session :as session]
            [ai-memory.db.core :as db]
            [datomic.api :as d]
            [clojure.string :as str])
  (:import [java.util Date]
           [java.time Instant]))

;; TODO: implement MCP protocol (JSON-RPC over stdio/SSE)
;; For now, define the tool schemas and handlers.

(defn handle-browse-tags
  "Navigates tag taxonomy with depth-limited tree.
   `path`  — root of subtree (nil = full tree from roots)
   `depth` — max levels to return (default 2)"
  [conn _cfg {:keys [path depth] :or {depth 2}}]
  (let [db (db/db conn)]
    (tag-query/taxonomy db path depth)))

(defn handle-count-facts
  "Counts facts for each tag set (intersection). No entity pulls.
   `tag-sets` — [[\"lang/clj\" \"pat/err\"] [\"lang/py\"]]"
  [conn cfg {:keys [tag-sets]}]
  (let [db (db/db conn)]
    (tag-query/count-by-tag-sets db (:metrics cfg) tag-sets)))

(defn handle-get-facts
  "Fetches facts for each tag set (intersection) with per-set limit.
   `tag-sets` — [[\"lang/clj\" \"pat/err\"] [\"lang/py\"]]
   `limit`   — max facts per tag set (default 50)"
  [conn cfg {:keys [tag-sets limit] :or {limit 50}}]
  (let [db (db/db conn)]
    (tag-query/fetch-by-tag-sets db (:metrics cfg) tag-sets {:limit limit})))

(defn handle-create-tag
  "Creates a new tag in the taxonomy."
  [conn {:keys [name parent-path]}]
  (let [path (tag/create-tag! conn {:name name :parent-path parent-path})]
    {:tag/path path}))

(defn- extract-project-tag
  "Finds first proj/* tag from nodes' tags."
  [nodes]
  (->> nodes
       (mapcat :tags)
       (filter #(str/starts-with? % "proj/"))
       first))

(defn- find-session-fact
  "Finds existing session fact by session-id."
  [db session-id]
  (when session-id
    (d/q '[:find ?e .
           :in $ ?sid
           :where [?e :node/session-id ?sid]
                  [?e :node/type :node.type/session]]
         db session-id)))

(defn- upsert-session-fact!
  "Creates or updates the rolling session summary fact."
  [conn cfg session-id session-summary proj-tag]
  (let [db  (db/db conn)
        eid (find-session-fact db session-id)]
    (if eid
      ;; Update existing session fact
      @(d/transact conn [[:db/add eid :node/content session-summary]
                         [:db/add eid :node/updated-at (Date.)]])
      ;; Create new session fact
      (let [tag-refs (tag-resolve/resolve-tags conn [(or proj-tag "session-log")])
            tick     (db/current-tick db)]
        (node/create-node conn cfg
          {:content    session-summary
           :node-type  :session
           :tag-refs   tag-refs
           :tick       tick
           :session-id session-id})))))

(defn handle-remember
  "Stores memory nodes, turn summary (to RAM buffer), and session summary (to Datomic).
   Nodes go through write pipeline (dedup, edges).
   Turn summary → RAM buffer for later matching with blob sections.
   Session summary → find-or-create session fact (searchable via tags)."
  [conn cfg params]
  (let [nodes           (:nodes params)
        turn-summary    (:turn-summary params)
        session-summary (:session-summary params)
        context-id      (:context-id params)
        proj-tag        (extract-project-tag nodes)
        result          (when (seq nodes)
                          (write/remember conn cfg params))]
    ;; Turn summary → RAM buffer (NOT a fact)
    (when (and turn-summary context-id)
      (session/append-turn-summary! context-id turn-summary))
    ;; Session summary → Datomic session fact (searchable)
    (when (and session-summary context-id)
      (upsert-session-fact! conn cfg context-id session-summary proj-tag))
    (or result {:nodes [] :edges-created 0})))

(defn handle-read-blob
  "Reads blob metadata or a specific section.
   Without :section — returns meta.edn content.
   With :section (0-based index) — returns section content."
  [_conn cfg {:keys [blob-dir section]}]
  (let [base (:blob-path cfg)]
    (if section
      (if-let [result (blob-store/read-section-by-index base blob-dir section)]
        result
        {:error (str "Section " section " not found in " blob-dir)})
      (if-let [meta (blob-store/read-meta base blob-dir)]
        meta
        {:error (str "Blob not found: " blob-dir)}))))

(defn handle-list-blobs
  "Lists blob nodes from Datomic, sorted by date desc."
  [conn _cfg {:keys [type limit] :or {limit 20}}]
  (let [db (db/db conn)]
    (node/find-blobs db {:type type :limit limit})))

(defn- store-conversation-sections!
  "Parses session JSONL and writes section files to blob dir.
   Returns vector of {:file :summary :lines}."
  [base-path blob-dir session-path sections-spec]
  (let [turns (ingest/parse-session session-path)
        sections (if (seq sections-spec)
                   (ingest/split-by-boundaries (vec turns) sections-spec)
                   (ingest/split-auto turns 10))]
    (vec (map-indexed
           (fn [i {:keys [summary turns]}]
             (let [filename (blob-store/make-section-filename i summary)
                   content  (ingest/format-section turns)
                   lines    (count (str/split-lines content))]
               (blob-store/write-section! base-path blob-dir filename content)
               {:file filename :summary summary :lines lines}))
           sections))))

(defn handle-store-conversation
  "Stores a conversation session as a blob.
   Reads JSONL from ~/.claude, cleans messages, writes sections + meta.edn.
   Creates blob node in Datomic. Optionally stores extracted facts."
  [conn cfg {:keys [session-id project project-path title summary status tags
                    sections continues facts]}]
  (let [base         (:blob-path cfg)
        project-path (or project-path
                         (:project-path cfg)
                         (System/getProperty "user.dir"))
        session-path (ingest/resolve-session-path session-id project-path)
        _            (when-not session-path
                       (throw (ex-info "Session JSONL not found"
                                       {:session-id session-id})))
        blob-dir     (blob-store/make-blob-dir-name base title)
        now          (Date.)
        section-data (store-conversation-sections! base blob-dir session-path sections)
        meta-data    {:id         (java.util.UUID/randomUUID)
                      :type       :conversation
                      :title      title
                      :project    project
                      :created-at now
                      :summary    summary
                      :status     status
                      :session-id session-id
                      :continues  continues
                      :tags       tags
                      :sections   section-data}]
    ;; Write meta.edn
    (blob-store/write-meta! base blob-dir meta-data)
    ;; Create blob node in Datomic
    (let [tag-refs (when (seq tags)
                     (tag-resolve/resolve-tags conn tags))
          blob-node (node/create-node conn cfg
                      {:content   summary
                       :node-type :conversation
                       :tag-refs  tag-refs
                       :tick      (db/current-tick (db/db conn))
                       :blob-dir  blob-dir})
          ;; Store extracted facts with sources
          fact-results
          (when (seq facts)
            (let [nodes (mapv (fn [f]
                                (cond-> {:content   (:content f)
                                         :node-type (or (:node-type f) "fact")
                                         :tags      (:tags f)}
                                  (:section f)
                                  (assoc :sources
                                    #{(str blob-dir "/"
                                           (:file (nth section-data (:section f))))})))
                              facts)]
              (write/remember conn cfg {:nodes nodes})))]
      {:blob-dir  blob-dir
       :blob-id   (:node-uuid blob-node)
       :sections  (count section-data)
       :facts     (count (:nodes fact-results))})))

(defn handle-store-file
  "Stores a generic file as a blob. Content provided directly or read from path."
  [conn cfg {:keys [title project summary tags type content path]}]
  (let [base     (:blob-path cfg)
        blob-dir (blob-store/make-blob-dir-name base title)
        now      (Date.)
        ext      (cond
                   path    (last (str/split path #"\."))
                   content "md"
                   :else   "txt")
        filename (blob-store/make-section-filename 0 title :ext ext)
        _        (if content
                   (blob-store/write-section! base blob-dir filename content)
                   (when path
                     (blob-store/write-section-binary! base blob-dir filename path)))
        lines    (when content (count (str/split-lines content)))
        meta-data {:id           (java.util.UUID/randomUUID)
                   :type         (keyword (or type "file"))
                   :title        title
                   :project      project
                   :created-at   now
                   :summary      summary
                   :source-path  path
                   :tags         tags
                   :sections     [{:file filename :summary summary :lines lines}]}]
    (blob-store/write-meta! base blob-dir meta-data)
    (let [tag-refs (when (seq tags)
                     (tag-resolve/resolve-tags conn tags))
          blob-node (node/create-node conn cfg
                      {:content   summary
                       :node-type :document
                       :tag-refs  tag-refs
                       :tick      (db/current-tick (db/db conn))
                       :blob-dir  blob-dir})]
      {:blob-dir blob-dir
       :blob-id  (:node-uuid blob-node)})))

;; --- Session sync (called by Stop hook via HTTP) ---

(defn- parse-instant [s]
  (when s (Instant/parse s)))

(defn- group-into-turns
  "Groups messages into turns. A turn starts with a user message and ends
   with the last assistant message before the next user message."
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
                        ;; assistant content is often [{:type "text" :text "..."}]
                        (->> content
                             (filter #(= "text" (:type %)))
                             (map :text)
                             (str/join "\n")))]
             (str "**" (or role "unknown") "**\n" text)))
         messages)))

(defn- derive-project [cwd]
  (when cwd
    (last (str/split cwd #"/"))))

(defn handle-session-sync
  "Receives delta messages from Stop hook. Groups into turns, matches summaries
   from RAM buffer, writes blob sections.
   Reuses the single :node.type/session node — attaches blob-dir to it."
  [conn cfg {:keys [session-id cwd messages]}]
  (let [base      (:blob-path cfg)
        db        (db/db conn)
        project   (derive-project cwd)
        turns     (group-into-turns messages)
        summaries (session/get-turn-summaries session-id)

        ;; Find existing session node (created by memory_remember)
        session-eid (find-session-fact db session-id)
        existing-dir (when session-eid
                       (:node/blob-dir (d/pull db [:node/blob-dir] session-eid)))
        blob-dir    (or existing-dir
                        (blob-store/make-blob-dir-name base (str "session-" (subs session-id 0 8))))

        ;; Read existing meta or initialize
        existing-meta (blob-store/read-meta base blob-dir)
        section-offset (count (:sections existing-meta))

        ;; Write new sections
        new-sections
        (vec (map-indexed
               (fn [i {:keys [messages t-start t-end]}]
                 (let [summary  (session/match-summary summaries t-start t-end)
                       idx      (+ section-offset i)
                       filename (blob-store/make-section-filename
                                  idx (or summary (str "turn-" idx)))
                       content  (format-turn-as-markdown messages)
                       lines    (count (str/split-lines content))]
                   (blob-store/write-section! base blob-dir filename content)
                   {:file filename :summary summary :lines lines
                    :timestamp (str t-start)}))
               turns))

        ;; Build/update meta.edn
        all-sections (into (vec (:sections existing-meta)) new-sections)
        meta-data (merge
                    (or existing-meta
                        {:id         (java.util.UUID/randomUUID)
                         :type       :session
                         :project    project
                         :created-at (Date.)
                         :session-id session-id})
                    {:sections all-sections
                     :summary  (->> all-sections
                                    (keep :summary)
                                    (str/join "; "))})]
    (blob-store/write-meta! base blob-dir meta-data)

    ;; Attach blob-dir to existing session node, or create one if agent never called memory_remember
    (if session-eid
      (when-not existing-dir
        @(d/transact conn [[:db/add session-eid :node/blob-dir blob-dir]
                           [:db/add session-eid :node/updated-at (Date.)]]))
      (let [tag-refs (when project
                       (tag-resolve/resolve-tags conn [(str "proj/" project)]))]
        (node/create-node conn cfg
          {:content    (or (:summary meta-data) "Session conversation")
           :node-type  :session
           :tag-refs   tag-refs
           :tick       (db/current-tick db)
           :blob-dir   blob-dir
           :session-id session-id})))

    {:blob-dir blob-dir
     :sections-added (count new-sections)
     :total-sections (count all-sections)}))
