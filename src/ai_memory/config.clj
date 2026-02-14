(ns ai-memory.config)

(defn load-config []
  {:datomic-uri (or (System/getenv "DATOMIC_URI")
                    "datomic:mem://ai-memory-dev")
   :port        (parse-long (or (System/getenv "PORT") "8080"))})
