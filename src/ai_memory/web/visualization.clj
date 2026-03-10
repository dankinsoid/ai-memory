;; @ai-generated(guided)
(ns ai-memory.web.visualization
  "D3 graph visualization endpoints for the web UI.
   Presentation layer — formats domain data for the frontend."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.tag.query :as tag-query]
            [ai-memory.decay.core :as decay]))

;; --- D3 formatting ---

(defn- infer-d3-type [n]
  (cond
    (:node/blob-dir n)    "blob"
    (:node/session-id n)  "session"
    :else                 "fact"))

(defn- node->d3 [n]
  {:id      (str (:db/id n))
   :content (:node/content n)
   :type    (infer-d3-type n)
   :weight  (:node/weight n)
   :tags    (vec (:node/tags n))})

(defn- node->d3-with-ew [tick n]
  (assoc (node->d3 n)
    :effective-weight (decay/effective-weight
                        (or (:node/weight n) 0.0)
                        (or (:node/cycle n) 0)
                        tick
                        decay/default-decay-k)))

(defn- edge->d3 [e]
  {:source (str (:edge/from e))
   :target (str (:edge/to e))
   :weight (:edge/weight e)})

;; --- Endpoints ---

(defn get-graph
  "Returns full graph for D3 visualization.
   `stores` — map with :fact-store"
  [stores]
  (let [fs    (:fact-store stores)
        nodes (tag-query/all-nodes fs)
        edges (p/all-edges fs)]
    {:nodes (mapv node->d3 nodes)
     :links (mapv edge->d3 edges)}))

(defn get-top-nodes
  "Returns highest effective-weight nodes as graph entry points.
   `stores` — map with :fact-store
   `opts`   — {:limit N :tag str}"
  [stores {:keys [limit tag]}]
  (let [fs    (:fact-store stores)
        limit (min 100 (or limit 20))
        tick  (p/current-tick fs)
        nodes (if tag
                (tag-query/by-tag fs tag)
                (tag-query/all-nodes fs))
        with-ew (mapv (fn [n]
                         (assoc n ::ew
                           (decay/effective-weight
                             (or (:node/weight n) 0.0)
                             (or (:node/cycle n) 0)
                             tick
                             decay/default-decay-k)))
                       nodes)
        top-n (->> with-ew (sort-by ::ew >) (take limit))]
    {:nodes (mapv (fn [n]
                    (-> (node->d3 n)
                        (assoc :effective-weight (::ew n))
                        (assoc :edge-count (count (p/find-edges-from fs (:db/id n))))))
                  top-n)}))

(defn- bfs-neighborhood
  "BFS traversal from center node up to `depth` hops."
  [fact-store center-eid depth limit tick]
  (loop [frontier #{center-eid}
         visited  #{center-eid}
         nodes    []
         edges    []
         d        0]
    (if (or (>= d depth) (empty? frontier))
      {:nodes nodes :edges edges :has-more (and (< d depth) (not (empty? frontier)))}
      (let [new-edges    (mapcat #(p/find-edges-from fact-store %) frontier)
            neighbor-ids (->> new-edges
                              (keep :edge/to)
                              (remove visited)
                              distinct
                              (take limit))
            new-nodes    (mapv #(p/find-node-by-eid fact-store %) neighbor-ids)
            edges-d3     (mapv edge->d3 new-edges)]
        (recur (set neighbor-ids)
               (into visited neighbor-ids)
               (into nodes (mapv (partial node->d3-with-ew tick) new-nodes))
               (into edges edges-d3)
               (inc d))))))

(defn get-graph-neighborhood
  "Returns subgraph around a center node.
   `stores` — map with :fact-store
   `opts`   — {:node-id N :depth N :limit N}"
  [stores {:keys [node-id depth limit]}]
  (let [fs    (:fact-store stores)
        depth (min 3 (or depth 1))
        limit (min 200 (or limit 50))
        tick  (p/current-tick fs)
        center (p/find-node-by-eid fs node-id)]
    (when (:node/content center)
      (let [result (bfs-neighborhood fs node-id depth limit tick)]
        (assoc result :center (node->d3-with-ew tick center))))))
