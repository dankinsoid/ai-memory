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
  "Creates section filename: {NN}-{slug}.{ext}"
  [index summary & {:keys [ext] :or {ext "md"}}]
  (let [slug (slugify summary)
        ;; Limit slug length to avoid filesystem issues
        slug (subs slug 0 (min (count slug) 60))]
    (format "%02d-%s.%s" index slug ext)))

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

(defn read-section
  "Reads a section file as string. Returns nil if not found."
  [base-path dir-name filename]
  (let [file (io/file base-path dir-name filename)]
    (when (.exists file)
      (slurp file))))

(defn read-section-by-index
  "Reads a section by its index (0-based). Looks up filename from meta.edn."
  [base-path dir-name index]
  (when-let [meta (read-meta base-path dir-name)]
    (when-let [section (get-in meta [:sections index])]
      {:meta    section
       :content (read-section base-path dir-name (:file section))})))

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
