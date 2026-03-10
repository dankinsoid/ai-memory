;; @ai-generated(guided)
(ns ai-memory.system
  "Integrant lifecycle methods for backend-agnostic system components.
   Backend-specific init-keys (db, stores) live in ai-memory.system.backend
   which is loaded from whichever backend source path is on the classpath."
  (:require [integrant.core :as ig]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [ai-memory.store.openai-embedding :as openai-emb]
            [ai-memory.store.random-embedding :as rand-emb]
            [ai-memory.service.tags :as tags]
            [ai-memory.metrics :as metrics]
            [ai-memory.scheduler :as scheduler]
            [ai-memory.web.handler :as web]
            ;; Side-effect require: registers backend-specific ig/init-key defmethods.
            ;; Resolved at classpath level — src-datomic/ or src-datalevin/.
            ai-memory.system.backend))

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
