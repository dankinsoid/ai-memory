(ns ai-memory.config
  (:require [clojure.java.io :as io]))

(defn- default-db-path []
  (str (System/getProperty "user.home") "/.claude/ai-memory/db"))

(defn- default-blob-path []
  (str (System/getProperty "user.home") "/.claude/ai-memory/blobs"))

(defn- ensure-dir! [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    path))

(defn load-config []
  (let [blob-path (or (System/getenv "AI_MEMORY_BLOB_PATH")
                      (System/getenv "BLOB_PATH")
                      (default-blob-path))
        db-path   (or (System/getenv "AI_MEMORY_DB_PATH")
                      (default-db-path))]
    {:db-path        (ensure-dir! db-path)
     :port           (parse-long (or (System/getenv "PORT") "8080"))
     :openai-api-key (System/getenv "OPENAI_API_KEY")
     :blob-path      (ensure-dir! blob-path)
     :project-path   (System/getenv "PROJECT_PATH")
     :api-token      (System/getenv "API_TOKEN")}))
