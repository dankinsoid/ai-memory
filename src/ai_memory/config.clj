(ns ai-memory.config)

(defn load-config []
  {:datomic-uri    (or (System/getenv "DATOMIC_URI")
                       "datomic:mem://ai-memory-dev")
   :port           (parse-long (or (System/getenv "PORT") "8080"))
   :embedding-url  (or (System/getenv "EMBEDDING_URL")
                       "http://localhost:8090")
   :qdrant-url     (or (System/getenv "QDRANT_URL")
                       "http://localhost:6333")
   :blob-path      (or (System/getenv "BLOB_PATH")
                       "data/blobs")})
