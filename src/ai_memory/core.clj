(ns ai-memory.core
  (:require [ai-memory.config :as config]
            [ai-memory.db.core :as db]
            [ai-memory.web.handler :as web]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn start-system [config]
  (let [conn (db/connect (:datomic-uri config))]
    (db/ensure-schema conn)
    (let [server (web/start {:port (:port config)
                             :conn conn
                             :cfg  config})]
      (log/info "ai-memory started on port" (:port config))
      {:conn conn :server server})))

(defn -main [& _args]
  (let [config (config/load-config)]
    (start-system config)
    @(promise)))
