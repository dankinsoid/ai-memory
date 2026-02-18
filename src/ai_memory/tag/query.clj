(ns ai-memory.tag.query
  "Tag-based retrieval: set operations over tagged nodes."
  (:require [ai-memory.tag.core :as tag]
            [ai-memory.metrics :as metrics]
            [datomic.api :as d]))

(def ^:private node-pull-spec
  [:node/id :node/content :node/weight :node/cycle :node/sources
   {:node/tag-refs [:tag/name]}])

(defn- pull-nodes
  "Pulls full node data for a set of entity IDs."
  [db eids]
  (mapv #(d/pull db node-pull-spec %) eids))

(defn by-tag
  "Nodes with a specific tag."
  [db tag-name]
  (let [eids (d/q '[:find [?n ...]
                    :in $ ?name
                    :where
                    [?t :tag/name ?name]
                    [?n :node/tag-refs ?t]]
                  db tag-name)]
    (pull-nodes db eids)))

(defn by-tags
  "Nodes matching ALL given tags (intersection)."
  [db {:keys [tags]}]
  (when (seq tags)
    (if (= 1 (count tags))
      (by-tag db (first tags))
      (let [tag-syms  (mapv #(symbol (str "?t" %)) (range (count tags)))
            where-cls (into []
                            (mapcat (fn [sym name]
                                      [[sym :tag/name name]
                                       ['?n :node/tag-refs sym]])
                                    tag-syms tags))
            query     {:find  '[[?n ...]]
                       :in    '[$]
                       :where where-cls}
            eids      (d/q query db)]
        (pull-nodes db eids)))))

(defn by-any-tags
  "Nodes matching ANY of the given tags (union)."
  [db {:keys [tags]}]
  (when (seq tags)
    (let [eids (d/q '[:find [?n ...]
                      :in $ [?name ...]
                      :where
                      [?t :tag/name ?name]
                      [?n :node/tag-refs ?t]]
                    db tags)]
      (pull-nodes db eids))))

;; --- Browse (flat list sorted by count) ---

(defn browse
  "Returns all tags sorted by node-count desc. Supports limit/offset."
  [db {:keys [limit offset] :or {limit 50 offset 0}}]
  (->> (tag/all-tags db)
       (sort-by :tag/node-count (fn [a b] (compare (or b 0) (or a 0))))
       (drop offset)
       (take limit)
       vec))

;; --- Count by tag sets (computed intersection) ---

(defn- count-intersection
  "Counts nodes matching ALL tags in the set. No entity pulls."
  [db tags]
  (if (= 1 (count tags))
    (or (d/q '[:find (count ?n) .
               :in $ ?name
               :where
               [?t :tag/name ?name]
               [?n :node/tag-refs ?t]]
             db (first tags))
        0)
    (let [tag-syms  (mapv #(symbol (str "?t" %)) (range (count tags)))
          where-cls (into []
                          (mapcat (fn [sym name]
                                    [[sym :tag/name name]
                                     ['?n :node/tag-refs sym]])
                                  tag-syms tags))
          query     {:find  '[(count ?n) .]
                     :in    '[$]
                     :where where-cls}]
      (or (d/q query db) 0))))

(defn count-by-tag-sets
  "Returns counts for each tag set (intersection).
   Input:  [[\"clj\" \"error-handling\"] [\"python\"]]
   Output: [{:tags [...] :count N} ...]"
  [db registry tag-sets]
  (metrics/timed registry metrics/read-duration {:operation "count_by_tag_sets"}
    (mapv (fn [tags]
            {:tags  tags
             :count (count-intersection db tags)})
          tag-sets)))

;; --- Fetch by tag sets (batch with limit) ---

(defn fetch-by-tag-sets
  "Fetches facts for each tag set (intersection). Per-set limit.
   Output: [{:tags [...] :facts [...]} ...]"
  [db registry tag-sets {:keys [limit] :or {limit 50}}]
  (metrics/timed registry metrics/read-duration {:operation "fetch_by_tag_sets"}
    (mapv (fn [tags]
            {:tags  tags
             :facts (let [results (by-tags db {:tags tags})]
                      (if limit
                        (vec (take limit results))
                        results))})
          tag-sets)))

;; --- Reconciliation ---

(defn reconcile-counts!
  "Recomputes all :tag/node-count from actual data. Idempotent.
   Returns {:tags-updated N :duration-ms N}."
  [conn]
  (let [start  (System/nanoTime)
        db     (d/db conn)
        actual (into {}
                     (d/q '[:find ?name (count ?n)
                            :where
                            [?t :tag/name ?name]
                            [?n :node/tag-refs ?t]]
                          db))
        all-names (d/q '[:find [?name ...]
                         :where [?t :tag/name ?name]]
                       db)
        tx-data (into []
                      (keep (fn [name]
                              (let [expected (get actual name 0)
                                    current  (or (:tag/node-count (d/entity db [:tag/name name])) 0)]
                                (when (not= current expected)
                                  [:db/add [:tag/name name] :tag/node-count expected]))))
                      all-names)]
    (when (seq tx-data)
      @(d/transact conn tx-data))
    {:tags-updated (count tx-data)
     :duration-ms  (/ (double (- (System/nanoTime) start)) 1e6)}))

;; --- Node info ---

(defn node-tags
  "Returns all tag names for a given node."
  [db node-id]
  (d/q '[:find [?name ...]
         :in $ ?nid
         :where
         [?n :node/id ?nid]
         [?n :node/tag-refs ?t]
         [?t :tag/name ?name]]
       db node-id))
