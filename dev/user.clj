(ns user
  (:require [integrant.repl :as ig-repl]
            [integrant.repl.state :refer [system]]
            [ai-memory.system :as sys]
            [ai-memory.db.core :as db]
            [ai-memory.tag.query :as tag-query]
            [datomic.api :as d]))

(ig-repl/set-prep! sys/read-config)

(def go ig-repl/go)
(def halt ig-repl/halt)
(def reset ig-repl/reset)
(def reset-all ig-repl/reset-all)

(defn conn [] (:db/conn system))
(defn registry [] (:metrics/registry system))
(defn stores []
  {:fact-store       (:store/fact system)
   :vector-store     (:store/vectors system)
   :tag-vector-store (:store/tag-vectors system)
   :embedding        (:store/embedding system)})

(defn reconcile-counts!
  "Manually trigger tag count reconciliation from REPL."
  []
  (tag-query/reconcile-counts! (conn)))

(comment
  (go)
  (halt)
  (reset)
  (reconcile-counts!)

  ;; Get current db value
  (db/db (conn))

  ;; Query all nodes
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :node/content]]
       (db/db (conn))))
