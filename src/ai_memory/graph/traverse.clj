;; ⚠ PAUSED — spreading activation retrieval.
;; Not used in current read path (tag taxonomy, see ADR-009).
;; Kept for future experiments once enough edge data accumulates.

(ns ai-memory.graph.traverse
  (:require [ai-memory.graph.edge :as edge]
            [ai-memory.decay.core :as decay]))

(defn spread-activation
  "Spreads activation from entry nodes through the graph.
   Returns a map of node-id -> accumulated activation."
  [db {:keys [entry-nodes threshold current-cycle decay-factor]
       :or   {threshold    0.1
              decay-factor 0.95}}]
  (loop [queue    (into clojure.lang.PersistentQueue/EMPTY
                        (map (fn [nid] [nid 1.0]) entry-nodes))
         visited  {}]
    (if (empty? queue)
      visited
      (let [[node-id activation] (peek queue)
            queue (pop queue)]
        (if (or (< activation threshold)
                (contains? visited node-id))
          (recur queue visited)
          (let [edges     (edge/find-edges-from db node-id)
                neighbors (for [e edges
                                :let [effective-w (decay/effective-weight
                                                    (:edge/weight e)
                                                    (:edge/cycle e)
                                                    current-cycle
                                                    decay-factor)
                                      next-activation (* activation effective-w)]
                                :when (> next-activation threshold)]
                            [(get-in e [:edge/to :node/id]) next-activation])]
            (recur (into queue neighbors)
                   (assoc visited node-id activation))))))))

(defn recall
  "Two-phase recall: starts from entry-nodes, spreads activation, returns top-k."
  [db {:keys [entry-nodes k current-cycle] :as opts
       :or   {k 10}}]
  (->> (spread-activation db opts)
       (sort-by val >)
       (take k)))
