;; @ai-generated(guided)
(ns ai-memory.system.backend
  "Integrant init-keys for Datalevin backend.
   Loaded via classpath — only one backend ns should be on the classpath at a time.
   Vector search uses Datalevin's built-in HNSW index (no external vector DB)."
  (:require [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [ai-memory.db.datalevin :as db]
            [ai-memory.store.datalevin-store :as datalevin]
            [ai-memory.store.datalevin-vector-store :as dlv-vectors]
            [ai-memory.store.protocols :as p]))

(defmethod ig/init-key :db/conn [_ {:keys [cfg]}]
  (let [db-path (:datalevin-path cfg)
        dim     (:embedding-dim cfg)
        conn    (db/connect db-path {:embedding-dim dim})]
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
  "Creates a VectorStore backed by Datalevin's HNSW index.
   Vectors stored as :vp/* entities in the same DB as facts."
  [conn cfg collection]
  (let [store (dlv-vectors/create conn collection)
        dim   (:embedding-dim cfg)]
    (p/ensure-store! store dim)
    (log/info "Vector store" collection "backend: datalevin-hnsw dim:" dim)
    store))

(defmethod ig/init-key :store/vectors [_ {:keys [conn cfg collection]}]
  (make-vector-store conn cfg collection))

(defmethod ig/init-key :store/tag-vectors [_ {:keys [conn cfg collection]}]
  (make-vector-store conn cfg collection))
