(ns ai-memory.config
  (:require [clojure.java.io :as io]
            [ai-memory.store.openai-embedding :as openai-emb]
            [ai-memory.store.qdrant-store :as qdrant]
            [ai-memory.store.datomic-store :as datomic]))

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

(defn create-stores
  "Creates all storage layer instances from config and Datomic conn.
   Returns {:fact-store :vector-store :embedding}."
  [cfg conn]
  {:fact-store   (datomic/create conn)
   :vector-store (qdrant/create cfg)
   :embedding    (openai-emb/create cfg)})
