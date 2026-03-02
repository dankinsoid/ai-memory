(ns user
  (:require [ai-memory.core :as core]
            [ai-memory.config :as config]
            [ai-memory.db.core :as db]
            [ai-memory.scheduler :as scheduler]
            [ai-memory.tag.query :as tag-query]
            [datalevin.core :as d]))

(defonce system (atom nil))

(defn start []
  (let [cfg (config/load-config)]
    (reset! system (core/start-system cfg))
    :started))

(defn stop []
  (when-let [{:keys [server scheduler]} @system]
    (scheduler/stop scheduler)
    (.stop server)
    (reset! system nil)
    :stopped))

(defn restart []
  (stop)
  (start))

(defn conn [] (:conn @system))
(defn registry [] (:metrics @system))

(defn reconcile-counts!
  "Manually trigger tag count reconciliation from REPL."
  []
  (tag-query/reconcile-counts! (conn)))

(comment
  (start)
  (stop)
  (restart)
  (reconcile-counts!)

  ;; Get current db value
  (db/db (conn))

  ;; Query all nodes
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :node/content]]
       (db/db (conn))))
