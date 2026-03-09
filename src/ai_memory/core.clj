(ns ai-memory.core
  (:require [ai-memory.config :as config]
            [ai-memory.db.core :as db]
            [ai-memory.store.protocols :as p]
            [ai-memory.service.tags :as tags]
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
    (db/recompute-tag-counts! conn)
    (let [stores (config/create-stores cfg conn)
          dim    (p/embedding-dim (:embedding stores))
          _      (p/ensure-store! (:vector-store stores) dim)
          _      (p/ensure-store! (:tag-vector-store stores) dim)
          _      (tags/seed! stores)
          server (web/start {:port   (:port cfg)
                             :conn   conn
                             :cfg    cfg
                             :stores stores})
          sched  (scheduler/start (:fact-store stores))]
      (log/info "ai-memory started on port" (:port cfg))
      {:conn conn :server server :metrics registry :scheduler sched :stores stores})))

(defn -main [& _args]
  (let [config (config/load-config)]
    (start-system config)
    @(promise)))
