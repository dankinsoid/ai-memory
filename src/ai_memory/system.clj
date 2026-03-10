;; @ai-generated(solo)
(ns ai-memory.system
  "Integrant lifecycle methods for all system components."
  (:require [integrant.core :as ig]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [ai-memory.db.core :as db]
            [ai-memory.store.datomic-store :as datomic]
            [ai-memory.store.qdrant-store :as qdrant]
            [ai-memory.store.memory-vector-store :as mem-vectors]
            [ai-memory.store.openai-embedding :as openai-emb]
            [ai-memory.store.random-embedding :as rand-emb]
            [ai-memory.store.protocols :as p]
            [ai-memory.service.tags :as tags]
            [ai-memory.metrics :as metrics]
            [ai-memory.scheduler :as scheduler]
            [ai-memory.web.handler :as web]))

;; --- Config loading ---

(defn- default-blob-path []
  (str (System/getProperty "user.home") "/.ai-memory/blobs"))

(defn- ensure-blob-dir!
  "Ensures blob directory exists, returns path."
  [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    (.getPath dir)))

(defmethod aero/reader 'ig/ref
  [_ _ value]
  (ig/ref value))

(defn read-config
  "Reads system.edn via aero (resolves #env, #or, #long, #profile) then parses #ig/ref tags.
   `opts` — aero opts map, e.g. {:profile :dev} for in-memory vector stores."
  ([] (read-config {}))
  ([opts]
   (aero/read-config (io/resource "system.edn") opts)))

;; --- Lifecycle methods ---

(defmethod ig/init-key :config/env [_ cfg]
  (let [blob-path (ensure-blob-dir! (or (:blob-path cfg) (default-blob-path)))]
    (assoc cfg :blob-path blob-path)))

(defmethod ig/init-key :metrics/registry [_ _]
  (metrics/create-registry))

(defmethod ig/init-key :db/conn [_ {:keys [cfg]}]
  (let [uri  (:datomic-uri cfg)
        conn (db/connect uri)]
    (db/ensure-schema conn)
    (db/recompute-tag-counts! conn)
    (log/info "Datomic connected:" uri)
    conn))

(defmethod ig/init-key :store/fact [_ {:keys [conn]}]
  (datomic/create conn))

(defn- resolve-embedding-backend
  "Resolves embedding backend keyword.
   :auto — uses :openai if API key is present, :random otherwise."
  [cfg]
  (let [backend (or (:embedding-backend cfg) :openai)]
    (if (and (= backend :auto) (not (:openai-api-key cfg)))
      :random
      (if (= backend :auto) :openai backend))))

(defmethod ig/init-key :store/embedding [_ {:keys [cfg]}]
  (let [backend (resolve-embedding-backend cfg)]
    (log/info "Embedding backend:" backend)
    (case backend
      :random (rand-emb/create)
      :openai (openai-emb/create cfg))))

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

(defmethod ig/init-key :service/context
  [_ {:keys [cfg] :as ctx}]
  (-> ctx
      (assoc :blob-path (:blob-path cfg))
      (dissoc :cfg)))

(defmethod ig/init-key :service/seed [_ {:keys [ctx]}]
  (tags/seed! ctx)
  nil)

(defmethod ig/init-key :web/server [_ {:keys [ctx conn cfg]}]
  (log/info "Starting web server on port" (:port cfg))
  (web/start {:port (:port cfg)
              :conn conn
              :ctx  ctx
              :cfg  cfg}))

(defmethod ig/halt-key! :web/server [_ server]
  (when server
    (log/info "Stopping web server")
    (.stop server)))

(defmethod ig/init-key :scheduler/reconcile [_ {:keys [fact-store]}]
  (scheduler/start fact-store))

(defmethod ig/halt-key! :scheduler/reconcile [_ executor]
  (scheduler/stop executor))
