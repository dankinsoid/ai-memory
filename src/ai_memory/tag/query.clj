(ns ai-memory.tag.query
  "Tag-based retrieval: set operations over tagged nodes."
  (:require [ai-memory.tag.core :as tag]
            [ai-memory.graph.node :as node]
            [ai-memory.metrics :as metrics]
            [ai-memory.util.date :as date]
            [datomic.api :as d]))

(def ^:private node-pull-spec
  [:db/id :node/content :node/weight :node/cycle :node/sources
   :node/blob-dir :node/updated-at
   {:node/tag-refs [:tag/name]}])

(defn- pull-nodes
  "Pulls full node data for a set of entity IDs."
  [db eids]
  (mapv #(d/pull db node-pull-spec %) eids))

(defn by-tag
  "Nodes with a specific tag. Optional :since/:until (java.util.Date) for date filtering."
  ([db tag-name] (by-tag db tag-name nil))
  ([db tag-name {:keys [since until]}]
   (if (or since until)
     (let [where-cls (cond-> [['?t :tag/name '?name]
                              ['?n :node/tag-refs '?t]
                              ['?n :node/updated-at '?updated]]
                       since (conj '[(>= ?updated ?since)])
                       until (conj '[(< ?updated ?until)]))
           in-cls    (cond-> '($ ?name)
                       since (conj '?since)
                       until (conj '?until))
           query     {:find  '[[?n ...]]
                      :in    (vec in-cls)
                      :where where-cls}
           args      (cond-> [db tag-name]
                       since (conj since)
                       until (conj until))
           eids      (apply d/q query args)]
       (pull-nodes db eids))
     (let [eids (d/q '[:find [?n ...]
                       :in $ ?name
                       :where
                       [?t :tag/name ?name]
                       [?n :node/tag-refs ?t]]
                     db tag-name)]
       (pull-nodes db eids)))))

(defn by-tags
  "Nodes matching ALL given tags (intersection).
   Optional :since/:until (java.util.Date) for date filtering on :node/updated-at."
  [db {:keys [tags since until]}]
  (when (seq tags)
    (if (and (= 1 (count tags)) (not since) (not until))
      (by-tag db (first tags))
      (let [tag-syms  (mapv #(symbol (str "?t" %)) (range (count tags)))
            where-cls (cond-> (into []
                                    (mapcat (fn [sym name]
                                              [[sym :tag/name name]
                                               ['?n :node/tag-refs sym]])
                                            tag-syms tags))
                        (or since until) (conj ['?n :node/updated-at '?updated])
                        since            (conj '[(>= ?updated ?since)])
                        until            (conj '[(< ?updated ?until)]))
            in-cls    (cond-> '[$]
                        since (conj '?since)
                        until (conj '?until))
            args      (cond-> [db]
                        since (conj since)
                        until (conj until))
            query     {:find  '[[?n ...]]
                       :in    (vec in-cls)
                       :where where-cls}
            eids      (apply d/q query args)]
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

(defn by-date-range
  "Nodes within a date range on :node/updated-at, no tag filter."
  [db {:keys [since until]}]
  (let [where-cls (cond-> [['?n :node/updated-at '?updated]]
                    since (conj '[(>= ?updated ?since)])
                    until (conj '[(< ?updated ?until)]))
        in-cls    (cond-> '[$]
                    since (conj '?since)
                    until (conj '?until))
        args      (cond-> [db]
                    since (conj since)
                    until (conj until))
        query     {:find  '[[?n ...]]
                   :in    (vec in-cls)
                   :where where-cls}
        eids      (apply d/q query args)]
    (pull-nodes db eids)))

(defn all-nodes
  "All nodes (for 'latest N' without any filter)."
  [db]
  (let [eids (d/q '[:find [?n ...]
                     :where [?n :node/updated-at]]
                   db)]
    (pull-nodes db eids)))

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

(defn- sort-by-date [facts]
  (sort-by :node/updated-at #(compare %2 %1) facts))

(defn fetch-by-tag-sets
  "Fetches facts for each tag set (intersection). Per-set limit.
   Optional :since/:until (java.util.Date) for date filtering.
   When tag-sets is nil/empty, returns all matching facts in a single group.
   Results always sorted by :node/updated-at desc.
   Output: [{:tags [...] :facts [...]} ...]"
  [db registry tag-sets {:keys [limit since until] :or {limit 50}}]
  (metrics/timed registry metrics/read-duration {:operation "fetch_by_tag_sets"}
    (if (seq tag-sets)
      ;; Tags provided: group by tag set, apply date filter globally
      (mapv (fn [tags]
              {:tags  tags
               :facts (->> (by-tags db {:tags tags :since since :until until})
                           sort-by-date
                           (take limit)
                           vec)})
            tag-sets)
      ;; No tags: date-only or "latest N"
      (let [results (if (or since until)
                      (by-date-range db {:since since :until until})
                      (all-nodes db))]
        [{:tags  []
          :facts (->> results sort-by-date (take limit) vec)}]))))

;; --- Unified filter-based retrieval ---

(defn- has-all-tags?
  "Returns true if node has ALL specified tag names."
  [tag-names node]
  (let [node-tags (set (map :tag/name (:node/tag-refs node)))]
    (every? node-tags tag-names)))

(defn- in-date-range?
  "Returns true if node's updated-at falls within [since, until)."
  [since until node]
  (let [updated (:node/updated-at node)]
    (and (or (nil? since) (not (.before updated since)))
         (or (nil? until) (.before updated until)))))

(defn- execute-filter
  "Executes a single filter. Returns {:filter <input> :facts [...]}."
  [db cfg registry {:keys [id tags query since until limit] :as filter-spec}]
  (cond
    ;; Direct ID lookup (Datomic entity ID)
    id
    (let [eid  (cond (number? id) (long id)
                     (string? id) (parse-long id))
          fact (when eid (d/pull db node-pull-spec eid))]
      {:filter filter-spec
       :facts  (if (:node/content fact) [fact] [])})

    ;; Vector search path — post-filter by tags and dates
    query
    (let [since-d       (date/parse-date-param since)
          until-d       (date/parse-date-param until)
          default-limit (or limit 10)
          ;; Fetch more from Qdrant to allow for post-filtering
          search-limit  (* default-limit (if (or tags since until) 5 1))
          hits          (node/search db cfg query search-limit)
          filtered      (cond->> hits
                          tags    (filter (partial has-all-tags? tags))
                          since-d (filter (partial in-date-range? since-d nil))
                          until-d (filter (partial in-date-range? nil until-d)))]
      {:filter filter-spec
       :facts  (vec (take default-limit filtered))})

    ;; Datomic path — tag intersection + date filter
    :else
    (let [since-d       (date/parse-date-param since)
          until-d       (date/parse-date-param until)
          default-limit (or limit 50)
          results       (cond
                          (seq tags) (by-tags db {:tags tags :since since-d :until until-d})
                          (or since-d until-d) (by-date-range db {:since since-d :until until-d})
                          :else (all-nodes db))]
      {:filter filter-spec
       :facts  (->> results sort-by-date (take default-limit) vec)})))

(defn fetch-by-filters
  "Executes an array of filters. Each filter is a map with optional keys:
   :tags, :query, :since, :until, :limit.
   Returns [{:filter {...} :facts [...]} ...]."
  [db cfg registry filters]
  (metrics/timed registry metrics/read-duration {:operation "fetch_by_filters"}
    (mapv (partial execute-filter db cfg registry) filters)))

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
  "Returns all tag names for a given node (by entity ID)."
  [db eid]
  (d/q '[:find [?name ...]
         :in $ ?eid
         :where
         [?eid :node/tag-refs ?t]
         [?t :tag/name ?name]]
       db eid))
