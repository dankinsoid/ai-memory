;; @ai-generated(guided)
(ns ai-memory.system.backend
  "Integrant init-keys for Datomic + Qdrant backend.
   Loaded via classpath — only one backend ns should be on the classpath at a time."
  (:require [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [ai-memory.db.core :as db]
            [ai-memory.store.datomic-store :as datomic]
            [ai-memory.store.qdrant-store :as qdrant]
            [ai-memory.store.memory-vector-store :as mem-vectors]
            [ai-memory.store.protocols :as p]))

(defmethod ig/init-key :db/conn [_ {:keys [cfg]}]
  (let [uri  (:datomic-uri cfg)
        conn (db/connect uri)]
    (db/ensure-schema conn)
    (db/recompute-tag-counts! conn)
    (log/info "Datomic connected:" uri)
    conn))

(defmethod ig/init-key :store/fact [_ {:keys [conn]}]
  (datomic/create conn))

(defn- make-vector-store
  "Creates a VectorStore based on :vector-backend config.
   :memory — in-memory (no external deps), :qdrant — real Qdrant."
  [cfg embedding collection]
  (let [backend (or (:vector-backend cfg) :qdrant)
        store   (case backend
                  :memory (mem-vectors/create collection)
                  :qdrant (qdrant/create cfg collection))
        dim     (p/embedding-dim embedding)]
    (p/ensure-store! store dim)
    (log/info "Vector store" collection "backend:" backend)
    store))

(defmethod ig/init-key :store/vectors [_ {:keys [cfg embedding collection]}]
  (make-vector-store cfg embedding collection))

(defmethod ig/init-key :store/tag-vectors [_ {:keys [cfg embedding collection]}]
  (make-vector-store cfg embedding collection))
