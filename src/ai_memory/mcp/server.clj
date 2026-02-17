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
            [ai-memory.db.core :as db]
            [clojure.string :as str])
  (:import [java.util Date]))

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

(defn handle-remember
  "Stores memory nodes with automatic associations and deduplication."
  [conn cfg params]
  (write/remember conn cfg params))

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
  [conn cfg {:keys [session-id project title summary status tags
                    sections continues facts]}]
  (let [base         (:blob-path cfg)
        project-path (or (:project-path cfg)
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
