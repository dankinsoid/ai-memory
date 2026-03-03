;; Write pipeline — ACTIVE.
;; Creates edges between facts (batch, context, global).
;; Edge data accumulates for future graph-based retrieval experiments.
;; Current read path uses tag taxonomy only (see ADR-009).

(ns ai-memory.graph.write
  "Orchestrates memory write pipeline: dedup, batch edges, context edges.
   Context state lives in RAM with TTL — no DB pollution."
  (:require [ai-memory.graph.node :as node]
            [ai-memory.graph.edge :as edge]
            [ai-memory.tag.resolve :as tag-resolve]
            [ai-memory.db.core :as db]
            [ai-memory.metrics :as metrics]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]))

;; --- Defaults ---

(def ^:private defaults
  {:association-factor     0.7
   :min-association-weight 0.05
   :dedup-threshold        0.85
   :reinforcement-factor   0.5
   :context-ttl-seconds    7200}) ;; 2 hours

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
  ;; Clamp to [0.0, 0.9999) — base=1.0 is reserved for eternal facts
  (min 0.9999 (Math/pow factor (double delta))))

(defn- max-delta
  "Max Δ where factor^Δ >= min-weight. Used to bound DB queries for :global."
  [factor min-weight]
  (long (Math/floor (/ (Math/log min-weight) (Math/log factor)))))

(defn- entity-node? [node-data]
  (some #(= % "entity") (:tags node-data)))

(defn- find-duplicate-node
  "Entity nodes: exact content match. Other nodes: vector search."
  [db cfg content node-data opts]
  (if (entity-node? node-data)
    (node/find-entity-by-content db content)
    (try
      (node/find-duplicate db cfg content (:dedup-threshold opts))
      (catch Exception e
        (log/warn e "Dedup search failed, creating new node")
        nil))))

(defn- process-node
  "Dedup check + create or reinforce. Resolves tags to entity refs.
   Returns {:id entity-id :status :created/:reinforced}."
  [conn cfg node-data opts]
  (let [db         (db/db conn)
        tag-refs   (when (seq (:tags node-data))
                     (tag-resolve/resolve-tags conn (:tags node-data)))
        duplicate  (find-duplicate-node db cfg (:content node-data) node-data opts)]
    (if duplicate
      (let [eid (:db/id duplicate)]
        (node/reinforce-node conn cfg eid
                             (:content node-data)
                             1.0
                             (:reinforcement-factor opts))
        (node/update-tag-refs conn eid tag-refs)
        {:id eid :status :reinforced})
      (let [result (node/create-node conn cfg
                     (-> node-data
                         (dissoc :tags :node-type)
                         (assoc :tag-refs tag-refs)))]
        {:id (:node-eid result) :status :created}))))

(defn- create-batch-edges
  "Creates bidirectional edges between all nodes in the batch (initial-weight=0.9).
   Returns {:edges N :pairs N :db-ops N}."
  [conn node-ids]
  (let [pairs (for [i (range (count node-ids))
                    j (range (inc i) (count node-ids))]
                [(nth node-ids i) (nth node-ids j)])]
    (doseq [[a b] pairs]
      (edge/find-or-create-edge conn a b 0.9)
      (edge/find-or-create-edge conn b a 0.9))
    (let [edge-count (* 2 (count pairs))]
      {:edges   edge-count
       :pairs   (count pairs)
       :db-ops  (* 2 edge-count)})))

(defn- create-context-edges
  "Creates unidirectional edges from new nodes to previous context nodes.
   Weight = association-factor ^ Δseq (Δseq = distance in entries).
   Returns {:edges N :candidates N :prev-batches N :db-ops N}."
  [conn entries current-seq node-ids opts]
  (let [{:keys [association-factor min-association-weight]} opts
        node-id-set   (set node-ids)
        prev-node-ids (into #{} (comp (mapcat identity) (remove node-id-set)) entries)
        cnt           (atom 0)]
    (doseq [[seq-idx batch-ids] (map-indexed vector entries)
            :let [delta-seq (- current-seq seq-idx)
                  w         (association-weight association-factor delta-seq)]
            :when (>= w min-association-weight)
            new-id  node-ids
            prev-id batch-ids
            :when (not (node-id-set prev-id))]
      (edge/find-or-create-edge conn new-id prev-id w)
      (swap! cnt inc))
    (let [edges @cnt]
      {:edges        edges
       :candidates   (* (count node-ids) (count prev-node-ids))
       :prev-batches (count entries)
       :db-ops       (* 2 edges)})))

(defn- create-global-edges
  "Creates unidirectional edges from new nodes to all recent nodes in DB.
   Weight = association-factor ^ (current_tick - node.cycle).
   Returns {:edges N :candidates N :recent-count N :db-ops N :tick-window N}."
  [conn node-ids opts]
  (let [{:keys [association-factor min-association-weight]} opts
        db          (db/db conn)
        tick        (db/current-tick db)
        md          (max-delta association-factor min-association-weight)
        min-tick    (max 0 (- tick md))
        recent-ids  (node/find-recent db min-tick)
        node-id-set (set node-ids)
        recent-ext  (remove node-id-set recent-ids)
        cnt         (atom 0)]
    (doseq [new-id  node-ids
            prev-id recent-ext
            :let [prev-node (node/find-by-id db prev-id)
                  delta     (- tick (:node/cycle prev-node 0))
                  w         (association-weight association-factor delta)]
            :when (>= w min-association-weight)]
      (edge/find-or-create-edge conn new-id prev-id w)
      (swap! cnt inc))
    (let [edges       @cnt
          recent-cnt  (count (seq recent-ext))]
      {:edges        edges
       :candidates   (* (count node-ids) recent-cnt)
       :recent-count recent-cnt
       :db-ops       (+ (* 2 edges)        ;; find-or-create: query + transact
                        recent-cnt)         ;; find-by-id per recent node
       :tick-window  (- tick min-tick)})))

(defn- edges-count [stats]
  (if (map? stats) (:edges stats 0) (or stats 0)))

(def ^:private empty-edge-stats {:edges 0 :candidates 0 :db-ops 0})

(defn remember
  "Writes memory nodes with automatic associations.
   Tick auto-increments on every DB write.
   `params`:
     :nodes      — vec of {:content, :tags}
     :context-id — string: session-scoped linking (RAM cache)
                   :global: link to all recent nodes (DB query by tick)
                   nil: no context edges, only batch"
  [conn cfg params]
  (let [registry   (:metrics cfg)
        opts       (merge defaults cfg)
        start-ns   (System/nanoTime)
        context-id (:context-id params)
        project    (:project params)
        nodes      (if project
                     (mapv #(update % :tags (fnil conj []) (str "project/" project))
                           (:nodes params))
                     (:nodes params))

        ;; Phase: nodes
        results
        (metrics/timed registry metrics/write-duration {:phase "nodes"}
          (mapv #(process-node conn cfg % opts) nodes))

        node-ids (mapv :id results)

        ;; Phase: batch edges
        batch-stats
        (metrics/timed registry metrics/write-duration {:phase "batch_edges"}
          (if (> (count node-ids) 1)
            (create-batch-edges conn node-ids)
            empty-edge-stats))

        ;; Phase: context edges
        context-stats
        (metrics/timed registry metrics/write-duration {:phase "context_edges"}
          (cond
            (= :global context-id)
            (create-global-edges conn node-ids opts)

            (string? context-id)
            (let [ctx          (get-context context-id)
                  prev-entries (or (:entries ctx) [])
                  current-seq  (count prev-entries)]
              (if (seq prev-entries)
                (create-context-edges conn prev-entries current-seq node-ids opts)
                empty-edge-stats))

            :else empty-edge-stats))]

    ;; Update RAM context cache
    (when (string? context-id)
      (update-context! context-id node-ids (:context-ttl-seconds opts)))

    ;; Record metrics
    (metrics/record-batch-size! registry (count node-ids))
    (metrics/record-nodes! registry results)
    (metrics/record-edges! registry "batch" batch-stats)
    (when-let [ctx-type (cond (= :global context-id) "global"
                              (string? context-id)    "context"
                              :else                   nil)]
      (metrics/record-edges! registry ctx-type context-stats))
    (metrics/set-context-cache-size! registry (count @contexts))

    ;; Total duration
    (when registry
      (let [elapsed (/ (double (- (System/nanoTime) start-ns)) 1e9)]
        (metrics/observe-duration! registry "total" elapsed)))

    {:nodes         results
     :tick          (db/current-tick (db/db conn))
     :batch-edges   batch-stats
     :context-edges context-stats
     :edges-created (+ (edges-count batch-stats)
                       (edges-count context-stats))}))
