(ns ai-memory.graph.edge
  (:require [datomic.api :as d]))

(defn create-edge
  "Creates a weighted edge between two nodes."
  [conn {:keys [from to weight]}]
  @(d/transact conn
     [{:db/id       (d/tempid :db.part/user)
       :edge/id     (d/squuid)
       :edge/from   [:node/id from]
       :edge/to     [:node/id to]
       :edge/weight (or weight 1.0)
       :edge/cycle  0}]))

(defn find-edges-from [db node-id]
  (d/q '[:find [(pull ?e [* {:edge/to [:node/id :node/content :node/type]}]) ...]
         :in $ ?from-id
         :where
         [?e :edge/from ?from]
         [?from :node/id ?from-id]]
       db node-id))

(defn find-all [db]
  (d/q '[:find [(pull ?e [:edge/id :edge/weight :edge/cycle
                          {:edge/from [:node/id]}
                          {:edge/to   [:node/id]}]) ...]
         :where [?e :edge/id]]
       db))

(defn strengthen [conn edge-id amount current-cycle]
  @(d/transact conn
     [{:edge/id     edge-id
       :edge/weight amount
       :edge/cycle  current-cycle}]))
