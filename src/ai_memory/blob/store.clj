(ns ai-memory.blob.store
  "Filesystem operations for blob storage.
   Blobs live in data/blobs/{YYYY-MM-DD}_{slug}/ with meta.edn + content files."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.time LocalDate ZoneId]
           [java.time.format DateTimeFormatter]))

(def ^:private date-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd"))

(defn- slugify
  "Converts a title to a URL-safe slug: lowercase, spaces/special → hyphens."
  [s]
  (-> s
      str/lower-case
      (str/replace #"[^a-z0-9\-]+" "-")
      (str/replace #"-+" "-")
      (str/replace #"^-|-$" "")))

(defn- today-str []
  (.format (LocalDate/now (ZoneId/systemDefault)) date-fmt))

(defn- unique-dir-name
  "Returns a unique directory name, appending -2, -3 etc on collision."
  [base-path name]
  (let [dir (io/file base-path name)]
    (if-not (.exists dir)
      name
      (loop [n 2]
        (let [candidate (str name "-" n)]
          (if-not (.exists (io/file base-path candidate))
            candidate
            (recur (inc n))))))))

(defn make-blob-dir-name
  "Creates blob directory name: {date}_{slug}. Ensures uniqueness under base-path."
  [base-path title & {:keys [date]}]
  (let [date-str (or date (today-str))
        slug     (slugify title)
        name     (str date-str "_" slug)]
    (unique-dir-name base-path name)))

(defn make-section-filename
  "Creates section filename: {NNNN}-{slug}.{ext}"
  [index summary & {:keys [ext] :or {ext "md"}}]
  (let [slug (slugify summary)
        ;; Limit slug length to avoid filesystem issues
        slug (subs slug 0 (min (count slug) 60))]
    (format "%04d-%s.%s" index slug ext)))

(defn write-meta!
  "Writes meta.edn to blob directory."
  [base-path dir-name meta-data]
  (let [dir  (io/file base-path dir-name)
        file (io/file dir "meta.edn")]
    (.mkdirs dir)
    (spit file (pr-str meta-data))
    (.getPath file)))

(defn read-meta
  "Reads and parses meta.edn from a blob directory. Returns nil if not found."
  [base-path dir-name]
  (let [file (io/file base-path dir-name "meta.edn")]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(defn write-section!
  "Writes a section file to blob directory. Returns the file path."
  [base-path dir-name filename content]
  (let [dir  (io/file base-path dir-name)
        file (io/file dir filename)]
    (.mkdirs dir)
    (spit file content)
    (.getPath file)))

(defn write-section-binary!
  "Copies a binary file (image, etc.) into blob directory."
  [base-path dir-name filename source-path]
  (let [dir  (io/file base-path dir-name)
        file (io/file dir filename)]
    (.mkdirs dir)
    (io/copy (io/file source-path) file)
    (.getPath file)))

(def ^:private sidecar-min-lines
  "Minimum section size (in lines) to warrant a sidecar .meta.edn file."
  20)

(defn- sidecar-filename
  "Returns sidecar meta filename: 05-slug.md → 05-slug.meta.edn"
  [filename]
  (str/replace filename #"\.[^.]+$" ".meta.edn"))

(defn write-section-meta!
  "Writes sidecar .meta.edn for a section. Only writes if lines >= threshold."
  [base-path dir-name filename meta-data]
  (let [lines (:lines meta-data 0)]
    (when (>= lines sidecar-min-lines)
      (let [dir  (io/file base-path dir-name)
            file (io/file dir (sidecar-filename filename))]
        (spit file (pr-str meta-data))
        (.getPath file)))))

(defn read-section-meta
  "Reads sidecar .meta.edn for a section file. Returns nil if not found."
  [base-path dir-name filename]
  (let [file (io/file base-path dir-name (sidecar-filename filename))]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(defn read-section
  "Reads a section file as string. Returns nil if not found."
  [base-path dir-name filename]
  (let [file (io/file base-path dir-name filename)]
    (when (.exists file)
      (slurp file))))

(defn- find-section-file-by-index
  "Finds a section file by numeric prefix (e.g. index 5 → '05-*.md').
   Excludes sidecar .meta.edn files."
  [base-path dir-name index]
  (let [dir    (io/file base-path dir-name)
        prefix (format "%04d-" index)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(let [n (.getName %)]
                      (and (str/starts-with? n prefix)
                           (not (str/ends-with? n ".meta.edn")))))
           first))))

(defn read-section-by-index
  "Reads a section by its index (0-based).
   Finds file by numeric prefix, reads sidecar .meta.edn if present."
  [base-path dir-name index]
  (when-let [file (find-section-file-by-index base-path dir-name index)]
    (let [filename (.getName file)
          content  (slurp file)
          sidecar  (read-section-meta base-path dir-name filename)]
      {:meta    (or sidecar {:file filename :lines (count (str/split-lines content))})
       :content content})))

(defn count-sections
  "Counts section files in a blob directory (files matching NNNN-*.ext pattern)."
  [base-path dir-name]
  (let [dir (io/file base-path dir-name)]
    (if (.exists dir)
      (->> (.listFiles dir)
           (filter #(re-matches #"\d{4,}-.*" (.getName %)))
           count)
      0)))

(defn list-blob-dirs
  "Lists all blob directories, sorted newest first (date prefix sort)."
  [base-path]
  (let [base (io/file base-path)]
    (when (.exists base)
      (->> (.listFiles base)
           (filter #(.isDirectory %))
           (map #(.getName %))
           (sort #(compare %2 %1))))))

(defn blob-exists?
  "Returns true if the blob directory and meta.edn exist."
  [base-path dir-name]
  (let [meta-file (io/file base-path dir-name "meta.edn")]
    (.exists meta-file)))

(defn find-session-blob-dir
  "Finds existing blob directory for a session-id by scanning meta.edn files.
   Returns dir-name or nil."
  [base-path session-id]
  (when-let [dirs (list-blob-dirs base-path)]
    (->> dirs
         (filter #(str/includes? % "session"))
         (some (fn [dir-name]
                 (when-let [meta (read-meta base-path dir-name)]
                   (when (= session-id (:session-id meta))
                     dir-name)))))))

;; --- Session chunks (_current.md + named chunks) ---

(def ^:private current-chunk-file "_current.md")

(defn append-current-chunk!
  "Appends markdown content to _current.md in blob directory.
   Creates dir and file if needed. Returns {:line-count N :byte-count N}."
  [base-path dir-name content]
  (let [dir  (io/file base-path dir-name)
        file (io/file dir current-chunk-file)]
    (.mkdirs dir)
    (spit file content :append true)
    {:line-count (count (str/split-lines (slurp file)))
     :byte-count (.length file)}))

(defn list-chunks
  "Lists session chunk files in a blob directory, sorted for display.
   Numbered chunks sorted by prefix, _current.md last.
   Excludes: meta.edn, compact.md.
   Returns vec of {:file filename :lines N :index N}."
  [base-path dir-name]
  (let [dir (io/file base-path dir-name)]
    (when (.exists dir)
      (let [files     (->> (.listFiles dir)
                           (map #(.getName %))
                           (filter #(str/ends-with? % ".md"))
                           (remove #{"compact.md"}))
            numbered  (->> files
                           (filter #(re-matches #"\d{4,}-.*\.md" %))
                           sort)
            current   (when (some #{"_current.md"} files) ["_current.md"])
            all-chunks (vec (concat numbered current))]
        (mapv (fn [idx filename]
                (let [file (io/file base-path dir-name filename)]
                  {:file  filename
                   :lines (count (str/split-lines (slurp file)))
                   :index idx}))
              (range) all-chunks)))))

(defn- next-chunk-number
  "Returns the next available chunk number for a blob directory."
  [base-path dir-name]
  (let [dir (io/file base-path dir-name)]
    (if (.exists dir)
      (let [existing (->> (.listFiles dir)
                          (map #(.getName %))
                          (keep #(when-let [m (re-find #"^(\d{4,})-" %)]
                                   (parse-long (second m)))))]
        (if (seq existing)
          (inc (apply max existing))
          1))
      1)))

(defn rename-current-chunk!
  "Renames _current.md to {NNNN}-{slug}.md. Returns new filename or nil."
  [base-path dir-name title]
  (let [dir  (io/file base-path dir-name)
        src  (io/file dir current-chunk-file)]
    (when (.exists src)
      (let [num      (next-chunk-number base-path dir-name)
            filename (make-section-filename num title)
            dest     (io/file dir filename)]
        (.renameTo src dest)
        filename))))
