(ns ai-memory.tag.query
  "Tag-based retrieval: set operations over tagged nodes."
  (:require [ai-memory.tag.core :as tag]
            [ai-memory.metrics :as metrics]
            [datomic.api :as d]))

(def ^:private node-pull-spec
  [:node/id :node/content :node/weight :node/cycle
   {:node/tag-refs [:tag/path :tag/name]}])

(defn- pull-nodes
  "Pulls full node data for a set of entity IDs."
  [db eids]
  (mapv #(d/pull db node-pull-spec %) eids))

(defn by-tag
  "Nodes with a specific tag."
  [db tag-path]
  (let [eids (d/q '[:find [?n ...]
                    :in $ ?path
                    :where
                    [?t :tag/path ?path]
                    [?n :node/tag-refs ?t]]
                  db tag-path)]
    (pull-nodes db eids)))

(defn by-tags
  "Nodes matching ALL given tag paths (intersection)."
  [db {:keys [tags]}]
  (when (seq tags)
    (if (= 1 (count tags))
      (by-tag db (first tags))
      (let [tag-syms  (mapv #(symbol (str "?t" %)) (range (count tags)))
            where-cls (into []
                            (mapcat (fn [sym path]
                                      [[sym :tag/path path]
                                       ['?n :node/tag-refs sym]])
                                    tag-syms tags))
            query     {:find  '[[?n ...]]
                       :in    '[$]
                       :where where-cls}
            eids      (d/q query db)]
        (pull-nodes db eids)))))

(defn by-any-tags
  "Nodes matching ANY of the given tag paths (union)."
  [db {:keys [tags]}]
  (when (seq tags)
    (let [eids (d/q '[:find [?n ...]
                      :in $ [?path ...]
                      :where
                      [?t :tag/path ?path]
                      [?n :node/tag-refs ?t]]
                    db tags)]
      (pull-nodes db eids))))

(defn by-subtree
  "Nodes with any tag that is a descendant of the given tag (or the tag itself)."
  [db tag-path]
  (let [descendants (tag/subtree db tag-path)
        all-paths   (cons tag-path (map :tag/path descendants))]
    (by-any-tags db {:tags all-paths})))

;; --- Browse (materialized counts) ---

(defn browse
  "Returns direct children of a tag (or roots) with materialized node counts."
  [db path]
  (let [tags (if path
               (tag/children db path)
               (tag/root-tags db))]
    (mapv (fn [t]
            (let [e (d/entity db [:tag/path (:tag/path t)])]
              (assoc t :node-count (or (:tag/node-count e) 0))))
          tags)))

;; --- Taxonomy (depth-limited tree) ---

(defn- tag-with-count [db tag]
  (let [e (d/entity db [:tag/path (:tag/path tag)])]
    (assoc tag :node-count (or (:tag/node-count e) 0))))

(defn- taxonomy-node [db tag depth max-depth]
  (let [node (tag-with-count db tag)]
    (if (>= depth max-depth)
      (let [kids (tag/children db (:tag/path tag))]
        (assoc node :truncated (boolean (seq kids))))
      (let [kids (tag/children db (:tag/path tag))]
        (if (seq kids)
          (assoc node :children
                 (mapv #(taxonomy-node db % (inc depth) max-depth) kids))
          node)))))

(defn taxonomy
  "Returns tag tree from `path` (nil = roots) limited to `max-depth` levels.
   Each node has :node-count (materialized). Truncated branches marked :truncated true."
  [db path max-depth]
  (let [tags (if path
               (tag/children db path)
               (tag/root-tags db))]
    (mapv #(taxonomy-node db % 1 max-depth) tags)))

;; --- Count by tag sets (computed intersection) ---

(defn- count-intersection
  "Counts nodes matching ALL tags in the set. No entity pulls."
  [db tags]
  (if (= 1 (count tags))
    (or (d/q '[:find (count ?n) .
               :in $ ?path
               :where
               [?t :tag/path ?path]
               [?n :node/tag-refs ?t]]
             db (first tags))
        0)
    (let [tag-syms  (mapv #(symbol (str "?t" %)) (range (count tags)))
          where-cls (into []
                          (mapcat (fn [sym path]
                                    [[sym :tag/path path]
                                     ['?n :node/tag-refs sym]])
                                  tag-syms tags))
          query     {:find  '[(count ?n) .]
                     :in    '[$]
                     :where where-cls}]
      (or (d/q query db) 0))))

(defn count-by-tag-sets
  "Returns counts for each tag set (intersection).
   Input:  [[\"lang/clj\" \"pat/err\"] [\"lang/py\"]]
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
                     (d/q '[:find ?path (count ?n)
                            :where
                            [?t :tag/path ?path]
                            [?n :node/tag-refs ?t]]
                          db))
        all-paths (d/q '[:find [?path ...]
                         :where [?t :tag/path ?path]]
                       db)
        tx-data (into []
                      (keep (fn [path]
                              (let [expected (get actual path 0)
                                    current  (or (:tag/node-count (d/entity db [:tag/path path])) 0)]
                                (when (not= current expected)
                                  [:db/add [:tag/path path] :tag/node-count expected]))))
                      all-paths)]
    (when (seq tx-data)
      @(d/transact conn tx-data))
    {:tags-updated (count tx-data)
     :duration-ms  (/ (double (- (System/nanoTime) start)) 1e6)}))

;; --- Node info ---

(defn node-tag-paths
  "Returns all tag paths for a given node."
  [db node-id]
  (d/q '[:find [?path ...]
         :in $ ?nid
         :where
         [?n :node/id ?nid]
         [?n :node/tag-refs ?t]
         [?t :tag/path ?path]]
       db node-id))
