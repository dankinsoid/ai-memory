;; Edge CRUD — ACTIVE (used by write pipeline).
;; Edges accumulate for future graph retrieval experiments.
;; Current read path uses tag taxonomy only (see ADR-009).

(ns ai-memory.graph.edge
  (:require [datomic.api :as d]
            [ai-memory.db.core :as db]))

(def ^:private max-weight 5.0)

(defn create-edge
  "Creates a weighted edge between two nodes (by entity ID).
   Optional :type for typed edges (e.g. :continuation)."
  [conn {:keys [from to weight type]}]
  (let [tick (db/next-tick (d/db conn))]
    (db/transact! conn
       [(cond-> {:db/id       (d/tempid :db.part/user)
                 :edge/id     (d/squuid)
                 :edge/from   from
                 :edge/to     to
                 :edge/weight (or weight 1.0)
                 :edge/cycle  tick}
          type (assoc :edge/type type))]
       tick)))

(defn find-edges-from [db from-eid]
  (d/q '[:find [(pull ?e [* {:edge/to [:db/id :node/content]}]) ...]
         :in $ ?from-eid
         :where
         [?e :edge/from ?from-eid]]
       db from-eid))

(defn find-edge-between
  "Finds edge entity ID from `from-eid` to `to-eid`, or nil."
  [db from-eid to-eid]
  (d/q '[:find ?eid .
         :in $ ?from-eid ?to-eid
         :where
         [?e :edge/from ?from-eid]
         [?e :edge/to ?to-eid]
         [?e :edge/id ?eid]]
       db from-eid to-eid))

(defn find-typed-edge-from
  "Finds edge of given type from `from-eid`. Returns {:edge/id, :edge/to {:db/id}, :edge/weight}."
  [db from-eid edge-type]
  (d/q '[:find (pull ?e [:edge/id :edge/weight {:edge/to [:db/id :node/content :node/blob-dir :node/session-id]}]) .
         :in $ ?from-eid ?etype
         :where
         [?e :edge/from ?from-eid]
         [?e :edge/type ?etype]]
       db from-eid edge-type))

(defn find-all [db]
  (d/q '[:find [(pull ?e [:edge/id :edge/weight :edge/cycle
                          {:edge/from [:db/id]}
                          {:edge/to   [:db/id]}]) ...]
         :where [?e :edge/id]]
       db))

(defn strengthen
  "Adds `delta` to edge weight (capped at max-weight) and updates cycle."
  [conn edge-id delta]
  (let [db (d/db conn)
        tick (db/next-tick db)
        current-weight (or (:edge/weight (d/pull db [:edge/weight] [:edge/id edge-id])) 0.0)
        new-weight (min (+ current-weight delta) max-weight)]
    (db/transact! conn
       [{:edge/id     edge-id
         :edge/weight new-weight
         :edge/cycle  tick}]
       tick)))

(defn find-or-create-edge
  "Creates edge if not exists, strengthens if it does.
   Optional opts: {:type :continuation} for typed edges."
  ([conn from-eid to-eid weight]
   (find-or-create-edge conn from-eid to-eid weight nil))
  ([conn from-eid to-eid weight opts]
   (let [db       (d/db conn)
         existing (find-edge-between db from-eid to-eid)]
     (if existing
       (strengthen conn existing weight)
       (create-edge conn (cond-> {:from from-eid :to to-eid :weight weight}
                           (:type opts) (assoc :type (:type opts))))))))
