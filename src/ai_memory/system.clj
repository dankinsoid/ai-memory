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
            [ai-memory.store.openai-embedding :as openai-emb]
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

(defn read-config
  "Reads system.edn via aero (resolves #env, #or, #long) then parses #ig/ref tags."
  []
  (aero/read-config (io/resource "system.edn")))

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

(defmethod ig/init-key :store/embedding [_ {:keys [cfg]}]
  (openai-emb/create cfg))

(defmethod ig/init-key :store/vectors [_ {:keys [cfg embedding collection]}]
  (let [store (qdrant/create cfg collection)
        dim   (p/embedding-dim embedding)]
    (p/ensure-store! store dim)
    store))

(defmethod ig/init-key :store/tag-vectors [_ {:keys [cfg embedding collection]}]
  (let [store (qdrant/create cfg collection)
        dim   (p/embedding-dim embedding)]
    (p/ensure-store! store dim)
    store))

(defmethod ig/init-key :service/seed [_ {:keys [stores]}]
  (tags/seed! stores)
  nil)

(defmethod ig/init-key :web/server [_ {:keys [cfg conn stores metrics]}]
  (let [full-cfg (assoc cfg :metrics metrics)]
    (log/info "Starting web server on port" (:port cfg))
    (web/start {:port   (:port cfg)
                :conn   conn
                :cfg    full-cfg
                :stores stores})))

(defmethod ig/halt-key! :web/server [_ server]
  (when server
    (log/info "Stopping web server")
    (.stop server)))

(defmethod ig/init-key :scheduler/reconcile [_ {:keys [fact-store]}]
  (scheduler/start fact-store))

(defmethod ig/halt-key! :scheduler/reconcile [_ executor]
  (scheduler/stop executor))
