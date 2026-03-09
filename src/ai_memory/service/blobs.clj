;; @ai-generated(guided)
(ns ai-memory.service.blobs
  "Domain operations on blob storage: store files, update content/tags, list, read.
   Orchestrates FactStore, VectorStore, blob filesystem, and tag creation."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.service.tags :as tags]
            [ai-memory.blob.store :as blob-store]
            [ai-memory.blob.exec :as blob-exec]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util Date UUID]))

(defn- embed-node! [stores eid content]
  (try
    (let [vector (p/embed-document (:embedding stores) content)]
      (p/upsert! (:vector-store stores) eid vector {}))
    (catch Exception e
      (log/warn e "Failed to embed blob node" eid))))

(defn store!
  "Creates a new blob: writes file to disk, creates fact node, embeds summary.
   `stores`    — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `blob-path` — filesystem base path for blobs
   `data`      — {:title, :summary, :content or :path, :tags, :type, :project}
   Returns {:blob-dir str, :blob-id eid}."
  [stores blob-path data]
  (let [fs       (:fact-store stores)
        blob-dir (blob-store/make-blob-dir-name blob-path (:title data))
        ext      (cond
                   (:path data)    (last (str/split (:path data) #"\."))
                   (:content data) "md"
                   :else           "txt")
        filename (blob-store/make-section-filename 0 (:title data) :ext ext)
        _        (if (:content data)
                   (blob-store/write-section! blob-path blob-dir filename (:content data))
                   (when (:path data)
                     (blob-store/write-section-binary! blob-path blob-dir filename (:path data))))
        lines    (when (:content data) (count (str/split-lines (:content data))))
        _        (blob-store/write-section-meta! blob-path blob-dir filename
                   {:file filename :summary (:summary data) :lines lines})
        meta-data (cond-> {:id            (UUID/randomUUID)
                           :type          (keyword (or (:type data) "file"))
                           :title         (:title data)
                           :created-at    (Date.)
                           :summary       (:summary data)
                           :tags          (:tags data)
                           :section-count 1}
                    (:project data) (assoc :project (:project data))
                    (:path data)    (assoc :source-path (:path data)))]
    (blob-store/write-meta! blob-path blob-dir meta-data)
    (let [tag-names (mapv str/trim (distinct (:tags data)))
          _         (tags/ensure! stores tag-names)
          result    (p/create-node! fs {:content  (:summary data)
                                        :tags     tag-names
                                        :blob-dir blob-dir})
          blob-id   (:id result)]
      (embed-node! stores blob-id (:summary data))
      {:blob-dir blob-dir
       :blob-id  blob-id})))

(defn update!
  "Updates blob content and/or metadata. Reinforces the associated node.
   `stores`    — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `blob-path` — filesystem base path
   `cfg`       — {:reinforcement-factor N}
   `blob-dir`  — blob directory name
   `data`      — {:summary, :content, :tags} — all optional
   Returns {:blob-dir str, :blob-id eid} or throws if not found."
  [stores blob-path cfg blob-dir data]
  (let [fs     (:fact-store stores)
        factor (or (:reinforcement-factor cfg) 0.5)
        node   (p/find-node-by-blob-dir fs blob-dir)]
    (when-not node
      (throw (ex-info "No node found for blob-dir" {:blob-dir blob-dir})))
    (let [eid     (:db/id node)
          {:keys [summary content tags]} data
          meta    (blob-store/read-meta blob-path blob-dir)]
      ;; Update blob file on disk if content provided
      (when (and content (not (str/blank? content)))
        (when-let [section-file (blob-store/read-section-by-index blob-path blob-dir 0)]
          (blob-store/write-section! blob-path blob-dir
            (get-in section-file [:meta :file]) content)))
      ;; Update meta.edn if summary or tags changed
      (when (or summary tags)
        (let [updated-meta (cond-> (or meta {})
                             summary (assoc :summary summary)
                             tags    (assoc :tags tags))]
          (blob-store/write-meta! blob-path blob-dir updated-meta)))
      ;; Reinforce node
      (if (and summary (not (str/blank? summary)))
        (do
          (p/reinforce-node! fs eid summary 1.0 factor)
          (embed-node! stores eid summary))
        (p/reinforce-weight! fs eid 1.0 factor))
      ;; Update tags on node
      (when (some? tags)
        (let [tag-names (mapv str/trim tags)]
          (tags/ensure! stores tag-names)
          (p/replace-node-tags! fs eid tag-names)))
      {:blob-dir blob-dir
       :blob-id  eid})))

(defn list-blobs
  "Returns recent blob nodes.
   `stores` — map with :fact-store
   `opts`   — {:limit N}"
  [stores opts]
  (p/find-blob-nodes (:fact-store stores) opts))

(defn read-blob
  "Reads blob content from filesystem. Returns metadata or a specific section.
   `blob-path` — filesystem base path
   `blob-dir`  — blob directory name (raw, will be resolved)
   `section`   — section index or nil for metadata
   Returns map or nil if not found."
  [blob-path blob-dir section]
  (let [resolved (or (blob-store/resolve-blob-dir blob-path blob-dir)
                     blob-dir)]
    (if section
      (blob-store/read-section-by-index blob-path resolved section)
      (blob-store/read-meta blob-path resolved))))

(defn exec-blob
  "Executes a command on a blob directory.
   `cfg`     — full config map
   `blob-dir` — blob directory name
   `command` — command string
   Returns execution result."
  [cfg blob-dir command]
  (blob-exec/exec-blob cfg blob-dir command))
