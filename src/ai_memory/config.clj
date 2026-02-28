(ns ai-memory.config
  (:require [clojure.java.io :as io]))

(defn- default-blob-path []
  (str (System/getProperty "user.home") "/.ai-memory/blobs"))

(defn- ensure-blob-dir! [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    path))

(defn load-config []
  (let [blob-path (or (System/getenv "BLOB_PATH")
                      (default-blob-path))]
    {:datomic-uri    (or (System/getenv "DATOMIC_URI")
                         "datomic:mem://ai-memory-dev")
     :port           (parse-long (or (System/getenv "PORT") "8080"))
     :openai-api-key (System/getenv "OPENAI_API_KEY")
     :qdrant-url     (or (System/getenv "QDRANT_URL")
                         "http://localhost:6333")
     :blob-path      (ensure-blob-dir! blob-path)
     :project-path   (System/getenv "PROJECT_PATH")
     :api-token      (System/getenv "API_TOKEN")}))
