(ns ai-memory.graph.node
  (:require [datomic.api :as d]))

(defn create-node
  "Creates a memory node. Returns the transaction result."
  [conn {:keys [content node-type scope tags]}]
  @(d/transact conn
     [{:db/id          (d/tempid :db.part/user)
       :node/id        (d/squuid)
       :node/content   content
       :node/type      (keyword "node.type" (name node-type))
       :node/scope     (or scope :node.scope/global)
       :node/tags      (or tags [])
       :node/weight    1.0
       :node/cycle     0}]))

(defn find-by-id [db node-id]
  (d/pull db '[*] [:node/id node-id]))

(defn find-by-type [db node-type]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?type
         :where [?e :node/type ?type]]
       db (keyword "node.type" (name node-type))))
