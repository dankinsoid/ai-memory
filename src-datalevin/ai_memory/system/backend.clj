;; @ai-generated(guided)
(ns ai-memory.system.backend
  "Integrant init-keys for Datalevin backend.
   Loaded via classpath — only one backend ns should be on the classpath at a time.
   Uses in-memory vector stores for now; Datalevin's built-in KNN can replace later."
  (:require [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [ai-memory.db.datalevin :as db]
            [ai-memory.store.datalevin-store :as datalevin]
            [ai-memory.store.memory-vector-store :as mem-vectors]
            [ai-memory.store.protocols :as p]))

(defmethod ig/init-key :db/conn [_ {:keys [cfg]}]
  (let [db-path (:datalevin-path cfg)
        conn    (db/connect db-path)]
    (db/ensure-schema conn)
    (db/recompute-tag-counts! conn)
    (log/info "Datalevin backend ready:" (or db-path "<in-memory>"))
    conn))

(defmethod ig/halt-key! :db/conn [_ conn]
  (when conn
    (log/info "Closing Datalevin connection")
    (db/close conn)))

(defmethod ig/init-key :store/fact [_ {:keys [conn]}]
  (datalevin/create conn))

(defn- make-vector-store
  "Creates a VectorStore. Currently always in-memory.
   Datalevin has built-in HNSW vector search — can be integrated later
   as a DatalevinVectorStore if in-memory becomes insufficient."
  [cfg collection]
  (let [store (mem-vectors/create collection)
        dim   (:embedding-dim cfg)]
    (p/ensure-store! store dim)
    (log/info "Vector store" collection "backend: memory dim:" dim)
    store))

(defmethod ig/init-key :store/vectors [_ {:keys [cfg collection]}]
  (make-vector-store cfg collection))

(defmethod ig/init-key :store/tag-vectors [_ {:keys [cfg collection]}]
  (make-vector-store cfg collection))
