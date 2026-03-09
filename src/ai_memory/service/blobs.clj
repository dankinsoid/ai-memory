;; @ai-generated(guided)
(ns ai-memory.service.blobs
  "Disk-only blob operations: write/update files on filesystem.
   No fact-store or vector-store dependencies — those belong in service.facts."
  (:require [ai-memory.blob.store :as blob-store]
            [ai-memory.blob.exec :as blob-exec]
            [clojure.string :as str])
  (:import [java.util Date UUID]))

(defn write-to-disk!
  "Creates a new blob directory with file content and metadata on disk.
   Pure disk I/O — does not create fact nodes or embeddings.
   `blob-path` — base filesystem path for blobs
   `data`      — {:title, :summary, :blob-content (text) or :path (file), :tags, :type, :project}
   Returns blob-dir name string."
  [blob-path data]
  (let [blob-content (:blob-content data)
        blob-dir (blob-store/make-blob-dir-name blob-path (:title data))
        ext      (cond
                   (:path data) (last (str/split (:path data) #"\."))
                   blob-content "md"
                   :else        "txt")
        filename (blob-store/make-section-filename 0 (:title data) :ext ext)
        _        (if blob-content
                   (blob-store/write-section! blob-path blob-dir filename blob-content)
                   (when (:path data)
                     (blob-store/write-section-binary! blob-path blob-dir filename (:path data))))
        lines    (when blob-content (count (str/split-lines blob-content)))
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
    blob-dir))

(defn update-on-disk!
  "Updates blob file and/or metadata on disk. Pure disk I/O.
   `blob-path` — base filesystem path for blobs
   `blob-dir`  — blob directory name
   `data`      — {:blob-content str, :summary str, :tags [str]} — all optional
   Returns blob-dir."
  [blob-path blob-dir {:keys [blob-content summary tags]}]
  (when (and blob-content (not (str/blank? blob-content)))
    (when-let [section-file (blob-store/read-section-by-index blob-path blob-dir 0)]
      (blob-store/write-section! blob-path blob-dir
        (get-in section-file [:meta :file]) blob-content)))
  (when (or summary tags)
    (let [meta    (blob-store/read-meta blob-path blob-dir)
          updated (cond-> (or meta {})
                    summary (assoc :summary summary)
                    tags    (assoc :tags tags))]
      (blob-store/write-meta! blob-path blob-dir updated)))
  blob-dir)

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
   `cfg`      — full config map (needs :blob-path and project-path)
   `blob-dir` — blob directory name
   `command`  — command string
   Returns execution result."
  [cfg blob-dir command]
  (blob-exec/exec-blob cfg blob-dir command))
