(ns ai-memory.graph.write
  "Orchestrates memory write pipeline: dedup, batch edges, context edges.
   Context state lives in RAM with TTL — no DB pollution."
  (:require [ai-memory.graph.node :as node]
            [ai-memory.graph.edge :as edge]
            [ai-memory.db.core :as db]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]))

;; --- Defaults ---

(def ^:private defaults
  {:association-factor       0.7
   :min-association-weight   0.05
   :dedup-threshold          0.85
   :reinforcement-delta      0.2
   :edge-reinforcement-delta 0.1
   :context-ttl-seconds      7200}) ;; 2 hours

;; --- Context cache (RAM) ---
;; {context-id {:entries [[uuid1 uuid2] [uuid3] ...]
;;              :last-access #inst "..."}}

(def ^:private contexts (atom {}))

(defn- now [] (Instant/now))

(defn- expired? [ctx-entry ttl-seconds]
  (let [last-access ^Instant (:last-access ctx-entry)
        elapsed (.getSeconds (java.time.Duration/between last-access (now)))]
    (> elapsed ttl-seconds)))

(defn evict-expired!
  "Removes contexts older than TTL. Called lazily."
  [ttl-seconds]
  (swap! contexts
    (fn [m]
      (persistent!
        (reduce-kv (fn [acc k v]
                     (if (expired? v ttl-seconds)
                       acc
                       (assoc! acc k v)))
                   (transient {})
                   m)))))

(defn- get-context [context-id]
  (get @contexts context-id))

(defn- update-context! [context-id node-ids ttl-seconds]
  (evict-expired! ttl-seconds)
  (swap! contexts
    (fn [m]
      (let [existing (get m context-id)
            entries  (conj (or (:entries existing) []) node-ids)]
        (assoc m context-id {:entries entries :last-access (now)})))))

(defn reset-contexts!
  "Clears all context state. For testing."
  []
  (reset! contexts {}))

;; --- Core ---

(defn- association-weight [factor delta]
  (Math/pow factor (double delta)))

(defn- max-delta
  "Max Δ where factor^Δ >= min-weight. Used to bound DB queries for :global."
  [factor min-weight]
  (long (Math/floor (/ (Math/log min-weight) (Math/log factor)))))

(defn- process-node
  "Dedup check + create or reinforce. Returns {:id uuid :status :created/:reinforced}."
  [conn cfg node-data tick opts]
  (let [{:keys [dedup-threshold reinforcement-delta]} opts
        db         (db/db conn)
        duplicate  (try
                     (node/find-duplicate db cfg (:content node-data) dedup-threshold)
                     (catch Exception e
                       (log/warn e "Dedup search failed, creating new node")
                       nil))]
    (if duplicate
      (let [node-uuid (:node/id duplicate)]
        (node/reinforce-node conn cfg node-uuid
                             (:content node-data)
                             reinforcement-delta
                             tick)
        {:id node-uuid :status :reinforced})
      (let [result (node/create-node conn cfg (assoc node-data :tick tick))]
        {:id (:node-uuid result) :status :created}))))

(defn- create-batch-edges
  "Creates bidirectional edges between all nodes in the batch (weight=1.0)."
  [conn node-ids tick]
  (let [pairs (for [i (range (count node-ids))
                    j (range (inc i) (count node-ids))]
                [(nth node-ids i) (nth node-ids j)])]
    (doseq [[a b] pairs]
      (edge/find-or-create-edge conn a b 1.0 tick)
      (edge/find-or-create-edge conn b a 1.0 tick))
    (* 2 (count pairs))))

(defn- create-context-edges
  "Creates unidirectional edges from new nodes to previous context nodes.
   Weight = association-factor ^ Δseq (Δseq = distance in entries)."
  [conn entries current-seq node-ids tick opts]
  (let [{:keys [association-factor min-association-weight]} opts
        node-id-set (set node-ids)
        cnt         (atom 0)]
    (doseq [[seq-idx batch-ids] (map-indexed vector entries)
            :let [delta-seq (- current-seq seq-idx)
                  w         (association-weight association-factor delta-seq)]
            :when (>= w min-association-weight)
            new-id  node-ids
            prev-id batch-ids
            :when (not (node-id-set prev-id))]
      (edge/find-or-create-edge conn new-id prev-id w tick)
      (swap! cnt inc))
    @cnt))

(defn- create-global-edges
  "Creates unidirectional edges from new nodes to all recent nodes in DB.
   Weight = association-factor ^ (current_tick - node.cycle)."
  [conn tick node-ids opts]
  (let [{:keys [association-factor min-association-weight]} opts
        md          (max-delta association-factor min-association-weight)
        min-tick    (max 0 (- tick md))
        db          (db/db conn)
        recent-ids  (node/find-recent db min-tick)
        node-id-set (set node-ids)
        cnt         (atom 0)]
    (doseq [new-id  node-ids
            prev-id recent-ids
            :when (not (node-id-set prev-id))
            :let [prev-node (node/find-by-id db prev-id)
                  delta     (- tick (:node/cycle prev-node 0))
                  w         (association-weight association-factor delta)]
            :when (>= w min-association-weight)]
      (edge/find-or-create-edge conn new-id prev-id w tick)
      (swap! cnt inc))
    @cnt))

(defn remember
  "Writes memory nodes with automatic associations.
   Increments global tick on each call.
   `params`:
     :nodes      — vec of {:content, :node-type, :scope, :tags}
     :context-id — string: session-scoped linking (RAM cache)
                   :global: link to all recent nodes (DB query by tick)
                   nil: no context edges, only batch"
  [conn cfg params]
  (let [opts       (merge defaults cfg)
        tick       (db/increment-tick! conn)
        context-id (:context-id params)
        results    (mapv #(process-node conn cfg % tick opts) (:nodes params))
        node-ids   (mapv :id results)
        ;; 1. Batch edges (bidirectional, weight=1.0)
        batch-edges (if (> (count node-ids) 1)
                      (create-batch-edges conn node-ids tick)
                      0)
        ;; 2. Context edges
        context-edges
        (cond
          ;; :global — link to all recent nodes from DB
          (= :global context-id)
          (create-global-edges conn tick node-ids opts)

          ;; string — session-scoped via RAM cache
          (string? context-id)
          (let [ctx          (get-context context-id)
                prev-entries (or (:entries ctx) [])
                current-seq  (count prev-entries)]
            (if (seq prev-entries)
              (create-context-edges conn prev-entries current-seq node-ids tick opts)
              0))

          ;; nil — no context edges
          :else 0)]
    ;; 3. Update RAM context cache (only for string context-id)
    (when (string? context-id)
      (update-context! context-id node-ids (:context-ttl-seconds opts)))
    {:nodes         results
     :tick          tick
     :edges-created (+ batch-edges context-edges)}))
