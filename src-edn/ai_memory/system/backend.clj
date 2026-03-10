;; @ai-generated(solo)
(ns ai-memory.system.backend
  "Integrant init-keys for EDN file + Qdrant Cloud backend.
   Loaded via classpath — only one backend ns should be on the classpath at a time.
   Designed for simple local dev: zero JVM deps beyond Clojure stdlib,
   vector search delegated to Qdrant Cloud."
  (:require [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [ai-memory.db.edn-file :as db]
            [ai-memory.store.edn-store :as edn-store]
            [ai-memory.store.qdrant-store :as qdrant]
            [ai-memory.store.memory-vector-store :as mem-vectors]
            [ai-memory.store.protocols :as p]))

(defn- default-edn-path
  "~/.claude/ai-memory/db.edn for local process,
   /data/db.edn inside Docker (detected by /.dockerenv)."
  []
  (if (.exists (java.io.File. "/.dockerenv"))
    "/data/db.edn"
    (str (System/getProperty "user.home") "/.claude/ai-memory/db.edn")))

(defmethod ig/init-key :db/conn [_ {:keys [cfg]}]
  (let [file-path (or (:edn-path cfg) (default-edn-path))
        parent    (.getParentFile (java.io.File. file-path))]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent))
    (let [conn (db/connect file-path)]
      (log/info "EDN backend ready:" file-path)
      conn)))

(defmethod ig/halt-key! :db/conn [_ conn]
  (when conn
    (log/info "Closing EDN database")
    (db/close conn)))

(defmethod ig/init-key :store/fact [_ {:keys [conn]}]
  (edn-store/create conn))

(defn- make-vector-store
  "Creates a VectorStore based on :vector-backend config.
   :memory — in-memory (no external deps), :qdrant — Qdrant (local or Cloud)."
  [cfg collection]
  (let [backend (or (:vector-backend cfg) :qdrant)
        store   (case backend
                  :memory (mem-vectors/create collection)
                  :qdrant (qdrant/create cfg collection))
        dim     (:embedding-dim cfg)]
    (p/ensure-store! store dim)
    (log/info "Vector store" collection "backend:" backend "dim:" dim)
    store))

(defmethod ig/init-key :store/vectors [_ {:keys [cfg collection]}]
  (make-vector-store cfg collection))

(defmethod ig/init-key :store/tag-vectors [_ {:keys [cfg collection]}]
  (make-vector-store cfg collection))
