;; @ai-generated(solo)
(ns ai-memory.store.edn-store
  "FactStore protocol implementation backed by an EDN file.
   All queries are plain Clojure data operations on the in-memory atom.
   No Datalog — just filter/reduce/sort over maps."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.db.edn-file :as db])
  (:import [java.util Date UUID]))

(defn- now [] (Date.))

(defn- node-out
  "Strips internal keys, returns node in the canonical shape expected by callers.
   Keeps :db/id and all :node/* keys."
  [node]
  (when node
    (select-keys node [:db/id :node/content :node/weight :node/cycle :node/sources
                        :node/blob-dir :node/created-at :node/updated-at
                        :node/session-id :node/tags])))

(defrecord EdnStore [db]
  p/FactStore

  ;; --- Nodes ---

  (create-node! [_ {:keys [content tags blob-dir sources session-id]}]
    (let [eid  (db/next-id! db)
          tick (inc (db/current-tick db))
          ts   (now)
          node (cond-> {:db/id           eid
                        :node/content    content
                        :node/weight     0.0
                        :node/cycle      tick
                        :node/created-at ts
                        :node/updated-at ts}
                 (seq tags)    (assoc :node/tags (set tags))
                 blob-dir      (assoc :node/blob-dir blob-dir)
                 (seq sources) (assoc :node/sources (set sources))
                 session-id    (assoc :node/session-id session-id))]
      (db/mutate! db (fn [s]
                        (-> s
                            (assoc-in [:nodes eid] node)
                            (assoc :tick tick))))
      {:id eid}))

  (find-node [_ eid]
    (node-out (get-in (db/state db) [:nodes eid])))

  (find-node-by-content [_ content]
    (->> (vals (:nodes (db/state db)))
         (filter #(and (= (:node/content %) content)
                       (contains? (:node/tags %) "entity")))
         first
         node-out))

  (update-node-content! [_ eid new-content]
    (db/mutate! db update-in [:nodes eid]
                assoc :node/content new-content :node/updated-at (now)))

  (update-node-tags! [_ eid tags]
    (when (seq tags)
      (db/mutate! db update-in [:nodes eid]
                  (fn [node]
                    (-> node
                        (update :node/tags (fnil into #{}) tags)
                        (assoc :node/updated-at (now)))))))

  (replace-node-tags! [_ eid new-tag-names]
    (db/mutate! db update-in [:nodes eid]
                (fn [node]
                  (-> node
                      (assoc :node/tags (set new-tag-names))
                      (assoc :node/updated-at (now))))))

  (update-node-weight! [_ eid weight]
    (let [tick (inc (db/current-tick db))]
      (db/mutate! db (fn [s]
                        (-> s
                            (update-in [:nodes eid] assoc
                                       :node/weight (double weight)
                                       :node/cycle tick
                                       :node/updated-at (now))
                            (assoc :tick tick))))))

  (set-node-blob-dir! [_ eid blob-dir]
    (db/mutate! db assoc-in [:nodes eid :node/blob-dir] blob-dir))

  (delete-node! [_ eid]
    (db/mutate! db (fn [s]
                      (let [;; Remove edges referencing this node
                            edges' (into {} (remove (fn [[_ e]]
                                                      (or (= (:edge/from e) eid)
                                                          (= (:edge/to e) eid)))
                                                    (:edges s)))]
                        (-> s
                            (update :nodes dissoc eid)
                            (assoc :edges edges'))))))

  (find-recent-nodes [_ min-tick]
    (->> (vals (:nodes (db/state db)))
         (filter #(and (:node/content %)
                       (>= (:node/cycle %) min-tick)))
         (mapv :db/id)))

  (find-blob-nodes [_ {:keys [limit] :or {limit 20}}]
    (->> (vals (:nodes (db/state db)))
         (filter :node/blob-dir)
         (sort-by :node/created-at #(compare %2 %1))
         (take limit)
         (mapv node-out)))

  (all-nodes [_]
    (vec (keys (:nodes (db/state db)))))

  (reset-nodes! [_]
    (db/mutate! db (fn [s]
                      (-> s
                          (assoc :nodes {} :edges {})
                          (update :tags (fn [tags]
                                          (into {} (map (fn [[k v]]
                                                          [k (assoc v :tag/node-count 0)])
                                                        tags))))))))

  ;; --- Tags ---

  (ensure-tag! [_ tag-name]
    (if (get-in (db/state db) [:tags tag-name])
      false
      (do (db/mutate! db assoc-in [:tags tag-name]
                      {:tag/name tag-name :tag/node-count 0})
          true)))

  (all-tags [_]
    (vec (vals (:tags (db/state db)))))

  (find-nodes-by-tag [_ tag-name {:keys [since until]}]
    (->> (vals (:nodes (db/state db)))
         (filter (fn [n]
                   (and (contains? (:node/tags n) tag-name)
                        (or (nil? since) (not (.before ^Date (:node/updated-at n) ^Date since)))
                        (or (nil? until) (.before ^Date (:node/updated-at n) ^Date until)))))
         (mapv node-out)))

  (find-nodes-by-tags [_ tags {:keys [since until]}]
    (let [tag-set (set tags)]
      (->> (vals (:nodes (db/state db)))
           (filter (fn [n]
                     (let [node-tags (:node/tags n)]
                       (and (every? #(contains? node-tags %) tag-set)
                            (or (nil? since) (not (.before ^Date (:node/updated-at n) ^Date since)))
                            (or (nil? until) (.before ^Date (:node/updated-at n) ^Date until))))))
           (mapv node-out))))

  (find-nodes-by-any-tags [_ tags]
    (let [tag-set (set tags)]
      (->> (vals (:nodes (db/state db)))
           (filter (fn [n] (some #(contains? (:node/tags n) %) tag-set)))
           (mapv node-out))))

  (find-nodes-by-session [_ session-id]
    (->> (vals (:nodes (db/state db)))
         (filter #(= (:node/session-id %) session-id))
         first
         node-out))

  (find-node-by-eid [_ id]
    (let [eid (cond (number? id) (long id) (string? id) (parse-long id))]
      (when eid
        (let [node (get-in (db/state db) [:nodes eid])]
          (when (:node/content node) (node-out node))))))

  (find-node-by-blob-dir [_ blob-dir]
    (->> (vals (:nodes (db/state db)))
         (filter #(= (:node/blob-dir %) blob-dir))
         first
         node-out))

  (find-nodes-by-date [_ since until]
    (->> (vals (:nodes (db/state db)))
         (filter (fn [n]
                   (and (or (nil? since) (not (.before ^Date (:node/updated-at n) ^Date since)))
                        (or (nil? until) (.before ^Date (:node/updated-at n) ^Date until)))))
         (mapv node-out)))

  (count-nodes-by-tag-sets [_ tag-sets]
    (let [nodes (vals (:nodes (db/state db)))]
      (mapv (fn [tags]
              {:tags  tags
               :count (count (filter (fn [n]
                                       (every? #(contains? (:node/tags n) %) tags))
                                     nodes))})
            tag-sets)))

  (node-tags [_ eid]
    (vec (or (:node/tags (get-in (db/state db) [:nodes eid])) [])))

  (reconcile-tag-counts! [_]
    (db/mutate! db (fn [s]
                      (let [nodes  (vals (:nodes s))
                            counts (frequencies (mapcat :node/tags nodes))]
                        (update s :tags
                                (fn [tags]
                                  (into {} (map (fn [[k v]]
                                                  [k (assoc v :tag/node-count (get counts k 0))])
                                                tags))))))))

  ;; --- Edges ---

  (create-edge! [_ {:keys [from to weight type]}]
    (let [eid  (db/next-id! db)
          tick (inc (db/current-tick db))
          edge (cond-> {:db/id       eid
                        :edge/id     (UUID/randomUUID)
                        :edge/from   from
                        :edge/to     to
                        :edge/weight (or weight 0.0)
                        :edge/cycle  tick}
                 type (assoc :edge/type type))]
      (db/mutate! db (fn [s]
                        (-> s
                            (assoc-in [:edges eid] edge)
                            (assoc :tick tick))))))

  (find-edges-from [_ from-eid]
    (let [state (db/state db)]
      (->> (vals (:edges state))
           (filter #(= (:edge/from %) from-eid))
           (mapv (fn [e]
                   (let [to-node (get-in state [:nodes (:edge/to e)])]
                     (assoc e :edge/to (select-keys to-node [:db/id :node/content]))))))))

  (find-edge-between [_ from-eid to-eid]
    (->> (vals (:edges (db/state db)))
         (filter #(and (= (:edge/from %) from-eid)
                       (= (:edge/to %) to-eid)))
         first
         ((fn [e] (when e [(:edge/id e) (:edge/weight e)])))))

  (find-typed-edge-from [_ from-eid edge-type]
    (let [state (db/state db)]
      (->> (vals (:edges state))
           (filter #(and (= (:edge/from %) from-eid)
                         (= (:edge/type %) edge-type)))
           first
           ((fn [e]
              (when e
                (let [to-node (get-in state [:nodes (:edge/to e)])]
                  {:edge/id     (:edge/id e)
                   :edge/weight (:edge/weight e)
                   :edge/to     (select-keys to-node [:db/id :node/content :node/blob-dir :node/session-id])})))))))

  (update-edge-weight! [_ edge-id weight]
    (let [tick (inc (db/current-tick db))]
      (db/mutate! db (fn [s]
                        (let [eid (->> (vals (:edges s))
                                       (filter #(= (:edge/id %) edge-id))
                                       first
                                       :db/id)]
                          (if eid
                            (-> s
                                (update-in [:edges eid] assoc
                                           :edge/weight (double weight)
                                           :edge/cycle tick)
                                (assoc :tick tick))
                            s))))))

  (promote-edge-eternal! [_ edge-id]
    (let [tick (inc (db/current-tick db))]
      (db/mutate! db (fn [s]
                        (let [eid (->> (vals (:edges s))
                                       (filter #(= (:edge/id %) edge-id))
                                       first
                                       :db/id)]
                          (if eid
                            (-> s
                                (update-in [:edges eid] assoc
                                           :edge/weight 1.0
                                           :edge/cycle tick)
                                (assoc :tick tick))
                            s))))))

  (all-edges [_]
    (->> (vals (:edges (db/state db)))
         (mapv (fn [e]
                 {:edge/id     (:edge/id e)
                  :edge/weight (:edge/weight e)
                  :edge/cycle  (:edge/cycle e)
                  :edge/from   {:db/id (:edge/from e)}
                  :edge/to     {:db/id (:edge/to e)}}))))

  ;; --- System ---

  (current-tick [_]
    (db/current-tick db))

  (next-tick! [_]
    (db/next-tick! db))

  ;; --- Migration ---

  (set-tick! [_ tick]
    (db/mutate! db assoc :tick tick))

  (import-node! [_ {:keys [content weight cycle tags blob-dir sources session-id
                             created-at updated-at]}]
    (let [eid (db/next-id! db)
          ts  (now)
          node (cond-> {:db/id           eid
                        :node/content    content
                        :node/weight     (or weight 0.0)
                        :node/cycle      (or cycle 0)
                        :node/created-at (or created-at ts)
                        :node/updated-at (or updated-at ts)}
                 (seq tags)    (assoc :node/tags (set tags))
                 blob-dir      (assoc :node/blob-dir blob-dir)
                 (seq sources) (assoc :node/sources (set sources))
                 session-id    (assoc :node/session-id session-id))]
      (db/mutate! db assoc-in [:nodes eid] node)
      {:id eid}))

  (import-edge! [_ {:keys [from to weight cycle type]}]
    (let [eid  (db/next-id! db)
          edge (cond-> {:db/id       eid
                        :edge/id     (UUID/randomUUID)
                        :edge/from   from
                        :edge/to     to
                        :edge/weight (or weight 0.0)
                        :edge/cycle  (or cycle 0)}
                 type (assoc :edge/type type))]
      (db/mutate! db assoc-in [:edges eid] edge)))

  (update-node-cycle! [_ eid cycle]
    (db/mutate! db assoc-in [:nodes eid :node/cycle] cycle))

  (update-edge-cycle! [_ edge-id cycle]
    (db/mutate! db (fn [s]
                      (let [eid (->> (vals (:edges s))
                                     (filter #(= (:edge/id %) edge-id))
                                     first
                                     :db/id)]
                        (if eid
                          (assoc-in s [:edges eid :edge/cycle] cycle)
                          s))))))

(defn create
  "Creates an EdnStore wrapping the given EdnDb."
  [db]
  (->EdnStore db))
