(ns ai-memory.core
  (:require [ai-memory.config :as config]
            [ai-memory.db.core :as db]
            [ai-memory.embedding.vector-store :as vs]
            [ai-memory.metrics :as metrics]
            [ai-memory.scheduler :as scheduler]
            [ai-memory.web.handler :as web]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn start-system [config]
  (let [registry (metrics/create-registry)
        cfg      (assoc config :metrics registry)
        conn     (db/connect (:datomic-uri cfg))]
    (db/ensure-schema conn)
    (vs/ensure-collection! (:qdrant-url cfg))
    (let [server (web/start {:port (:port cfg)
                             :conn conn
                             :cfg  cfg})
          sched  (scheduler/start conn)]
      (log/info "ai-memory started on port" (:port cfg))
      {:conn conn :server server :metrics registry :scheduler sched})))

(defn -main [& _args]
  (let [config (config/load-config)]
    (start-system config)
    @(promise)))
