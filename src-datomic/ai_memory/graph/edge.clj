;; Edge CRUD — ACTIVE (used by write pipeline).
;; Edges accumulate for future graph retrieval experiments.
;; Current read path uses tag taxonomy only (see ADR-009).

(ns ai-memory.graph.edge
  (:require [datomic.api :as d]
            [ai-memory.db.core :as db]))

(def ^:private edge-pull-spec
  [:db/id :edge/id :edge/weight :edge/cycle :edge/type
   {:edge/from [:db/id]} {:edge/to [:db/id]}])

(defn- flatten-edge-refs
  "Unwraps Datomic ref maps {:db/id N} to plain long EIDs."
  [edge]
  (when edge
    (-> edge
        (update :edge/from :db/id)
        (update :edge/to :db/id))))

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
                 :edge/weight (or weight 0.0)
                 :edge/cycle  tick}
          type (assoc :edge/type type))]
       tick)))

(defn find-edges-from
  "Returns edges from `from-eid` with :edge/from and :edge/to as plain longs."
  [db from-eid]
  (mapv flatten-edge-refs
        (d/q '[:find [(pull ?e pull-spec) ...]
               :in $ ?from-eid pull-spec
               :where [?e :edge/from ?from-eid]]
             db from-eid edge-pull-spec)))

(defn find-edge-between
  "Finds edge from `from-eid` to `to-eid`, returns full edge map or nil."
  [db from-eid to-eid]
  (flatten-edge-refs
    (d/q '[:find (pull ?e pull-spec) .
           :in $ ?from-eid ?to-eid pull-spec
           :where
           [?e :edge/from ?from-eid]
           [?e :edge/to ?to-eid]]
         db from-eid to-eid edge-pull-spec)))

(defn find-typed-edge-from
  "Finds edge of given type from `from-eid`. Returns full edge map or nil."
  [db from-eid edge-type]
  (flatten-edge-refs
    (d/q '[:find (pull ?e pull-spec) .
           :in $ ?from-eid ?etype pull-spec
           :where
           [?e :edge/from ?from-eid]
           [?e :edge/type ?etype]]
         db from-eid edge-type edge-pull-spec)))

(defn find-all
  "Returns all edges with :edge/from and :edge/to as plain longs."
  [db]
  (mapv flatten-edge-refs
        (d/q '[:find [(pull ?e pull-spec) ...]
               :in $ pull-spec
               :where [?e :edge/id]]
             db edge-pull-spec)))

(defn promote-eternal!
  "Sets edge weight to 1.0, making it eternal (never decays).
   Cannot be achieved via regular strengthen — explicit promotion only."
  [conn edge-id]
  (let [db   (d/db conn)
        tick (db/next-tick db)]
    (db/transact! conn
       [{:edge/id     edge-id
         :edge/weight 1.0
         :edge/cycle  tick}]
       tick)))

(defn strengthen
  "Adjusts edge base weight toward 1.0 asymptotically (positive score)
   or linearly (negative score). base ∈ [0.0, 1.0)."
  [conn edge-id score factor]
  (let [db (d/db conn)
        tick (db/next-tick db)
        current (or (:edge/weight (d/pull db [:edge/weight] [:edge/id edge-id])) 0.0)
        new-weight (if (pos? score)
                     (+ current (* score factor (- 1.0 current)))
                     (max 0.0 (+ current (* score factor))))]
    (db/transact! conn
       [{:edge/id     edge-id
         :edge/weight new-weight
         :edge/cycle  tick}]
       tick)))

(defn find-or-create-edge
  "Creates edge if not exists, strengthens if it does.
   `initial-weight` used on creation (base ∈ [0.0, 1.0)).
   On strengthen: score=1.0, factor=initial-weight (closes that fraction of gap).
   Optional opts: {:type :continuation} for typed edges."
  ([conn from-eid to-eid initial-weight]
   (find-or-create-edge conn from-eid to-eid initial-weight nil))
  ([conn from-eid to-eid initial-weight opts]
   (let [db   (d/db conn)
         edge (find-edge-between db from-eid to-eid)]
     (if edge
       (strengthen conn (:edge/id edge) 1.0 initial-weight)
       (create-edge conn (cond-> {:from from-eid :to to-eid :weight initial-weight}
                           (:type opts) (assoc :type (:type opts))))))))
