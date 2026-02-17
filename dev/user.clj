(ns user
  (:require [ai-memory.core :as core]
            [ai-memory.config :as config]
            [ai-memory.db.core :as db]
            [datomic.api :as d]))

(defonce system (atom nil))

(defn start []
  (let [cfg (config/load-config)]
    (reset! system (core/start-system cfg))
    :started))

(defn stop []
  (when-let [{:keys [server]} @system]
    (.stop server)
    (reset! system nil)
    :stopped))

(defn restart []
  (stop)
  (start))

(defn conn [] (:conn @system))
(defn registry [] (:metrics @system))

(comment
  (start)
  (stop)
  (restart)

  ;; Get current db value
  (db/db (conn))

  ;; Query all nodes
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :node/id]]
       (db/db (conn))))
