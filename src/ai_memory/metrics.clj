(ns ai-memory.metrics
  "Prometheus metrics registry and nil-safe recording helpers.
   Registry flows via cfg[:metrics]. All record-* fns are no-ops when registry is nil."
  (:require [iapetos.core :as prometheus]
            [iapetos.collector.ring :as ring-collector]))

;; --- Metric definitions ---

(def write-duration
  (prometheus/histogram
    :memory/write-duration-seconds
    {:description "Write pipeline phase duration"
     :labels      [:phase]
     :buckets     [0.001 0.005 0.01 0.05 0.1 0.25 0.5 1.0 5.0]}))

(def write-edges
  (prometheus/histogram
    :memory/write-edges
    {:description "Edges created per remember call"
     :labels      [:type]
     :buckets     [0 1 5 10 25 50 100 250 500 1000]}))

(def write-batch-size
  (prometheus/histogram
    :memory/write-batch-size
    {:description "Nodes per remember call"
     :buckets     [1 2 3 5 10 20 50]}))

(def write-edges-total
  (prometheus/counter
    :memory/write-edges-total
    {:description "Cumulative edges created"
     :labels      [:type]}))

(def write-nodes-total
  (prometheus/counter
    :memory/write-nodes-total
    {:description "Cumulative nodes processed"
     :labels      [:status]}))

(def write-db-ops-total
  (prometheus/counter
    :memory/write-db-ops-total
    {:description "Estimated DB operations (query + transact)"}))

(def context-cache-size
  (prometheus/gauge
    :memory/context-cache-size
    {:description "Active contexts in RAM cache"}))

(def read-duration
  (prometheus/histogram
    :memory/read-duration-seconds
    {:description "Read query duration"
     :labels      [:operation]
     :buckets     [0.001 0.005 0.01 0.05 0.1 0.25 0.5 1.0 5.0]}))

;; --- Registry ---

(defn create-registry []
  (-> (prometheus/collector-registry)
      (prometheus/register
        write-duration
        write-edges
        write-batch-size
        write-edges-total
        write-nodes-total
        write-db-ops-total
        context-cache-size
        read-duration)
      (ring-collector/initialize)))

;; --- Nil-safe helpers ---

(defmacro timed
  "Measures body execution time. Observes to `histogram` with `labels`.
   No-op when registry is nil — just evaluates body.
   Usage: (timed registry write-duration {:phase \"nodes\"} (do-work))"
  [registry histogram labels & body]
  `(let [reg# ~registry]
     (if reg#
       (let [start#   (System/nanoTime)
             result#  (do ~@body)
             elapsed# (/ (double (- (System/nanoTime) start#)) 1e9)]
         (prometheus/observe reg# ~histogram ~labels elapsed#)
         result#)
       (do ~@body))))

(defn record-edges!
  "Records edge creation stats from a create-*-edges result map."
  [registry edge-type {:keys [edges db-ops] :or {edges 0 db-ops 0}}]
  (when registry
    (prometheus/observe registry write-edges {:type edge-type} edges)
    (prometheus/inc registry write-edges-total {:type edge-type} edges)
    (prometheus/inc registry write-db-ops-total db-ops)))

(defn record-nodes! [registry results]
  (when registry
    (doseq [[status cnt] (frequencies (map :status results))]
      (prometheus/inc registry write-nodes-total {:status (name status)} cnt))))

(defn record-batch-size! [registry n]
  (when registry
    (prometheus/observe registry write-batch-size n)))

(defn set-context-cache-size! [registry n]
  (when registry
    (prometheus/set registry context-cache-size n)))

(defn observe-duration! [registry phase elapsed-seconds]
  (when registry
    (prometheus/observe registry write-duration {:phase phase} elapsed-seconds)))
