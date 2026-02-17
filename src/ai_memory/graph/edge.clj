;; Edge CRUD — ACTIVE (used by write pipeline).
;; Edges accumulate for future graph retrieval experiments.
;; Current read path uses tag taxonomy only (see ADR-009).

(ns ai-memory.graph.edge
  (:require [datomic.api :as d]))

(def ^:private max-weight 5.0)

(defn create-edge
  "Creates a weighted edge between two nodes."
  [conn {:keys [from to weight cycle]}]
  @(d/transact conn
     [{:db/id       (d/tempid :db.part/user)
       :edge/id     (d/squuid)
       :edge/from   [:node/id from]
       :edge/to     [:node/id to]
       :edge/weight (or weight 1.0)
       :edge/cycle  (or cycle 0)}]))

(defn find-edges-from [db node-id]
  (d/q '[:find [(pull ?e [* {:edge/to [:node/id :node/content :node/type]}]) ...]
         :in $ ?from-id
         :where
         [?e :edge/from ?from]
         [?from :node/id ?from-id]]
       db node-id))

(defn find-edge-between
  "Finds edge entity ID from `from-id` to `to-id`, or nil."
  [db from-id to-id]
  (d/q '[:find ?eid .
         :in $ ?from-id ?to-id
         :where
         [?e :edge/from ?from]
         [?from :node/id ?from-id]
         [?e :edge/to ?to]
         [?to :node/id ?to-id]
         [?e :edge/id ?eid]]
       db from-id to-id))

(defn find-all [db]
  (d/q '[:find [(pull ?e [:edge/id :edge/weight :edge/cycle
                          {:edge/from [:node/id]}
                          {:edge/to   [:node/id]}]) ...]
         :where [?e :edge/id]]
       db))

(defn strengthen
  "Adds `delta` to edge weight (capped at max-weight) and updates cycle."
  [conn edge-id delta current-cycle]
  (let [db (d/db conn)
        current-weight (or (:edge/weight (d/pull db [:edge/weight] [:edge/id edge-id])) 0.0)
        new-weight (min (+ current-weight delta) max-weight)]
    @(d/transact conn
       [{:edge/id     edge-id
         :edge/weight new-weight
         :edge/cycle  current-cycle}])))

(defn find-or-create-edge
  "Creates edge if not exists, strengthens if it does."
  [conn from-id to-id weight current-cycle]
  (let [db       (d/db conn)
        existing (find-edge-between db from-id to-id)]
    (if existing
      (strengthen conn existing weight current-cycle)
      (create-edge conn {:from from-id :to to-id :weight weight :cycle current-cycle}))))
