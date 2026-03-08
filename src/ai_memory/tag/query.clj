(ns ai-memory.tag.query
  "Tag-based retrieval: set operations over tagged nodes."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.graph.node :as node]
            [ai-memory.decay.core :as decay]
            [ai-memory.metrics :as metrics]
            [ai-memory.util.date :as date]))

;; --- Low-level helpers ---

(defn- sort-by-date [facts]
  (sort-by :node/updated-at #(compare %2 %1) facts))

(defn- compute-effective-weight
  [current-tick decay-k fact]
  (decay/effective-weight
    (or (:node/weight fact) 0.0)
    (or (:node/cycle fact) 0)
    current-tick
    decay-k))

(defn- enrich-effective-weight
  [fact-store facts]
  (let [current-tick (p/current-tick fact-store)
        decay-k      decay/default-decay-k]
    (mapv #(assoc % :node/effective-weight
                    (compute-effective-weight current-tick decay-k %))
          facts)))

(defn- sort-by-weight
  [fact-store facts]
  (sort-by :node/effective-weight #(compare %2 %1)
           (enrich-effective-weight fact-store facts)))

(defn- sort-facts
  "Dispatches sorting: \"weight\" → by effective weight, \"date\" or nil (default) → by updated-at.
   Always enriches facts with :node/effective-weight."
  [fact-store sort-by-param facts]
  (case sort-by-param
    "weight" (sort-by-weight fact-store facts)
    (enrich-effective-weight fact-store (sort-by-date facts))))

;; --- Public query functions ---

(defn by-tag
  "Nodes with a specific tag. Optional :since/:until (java.util.Date) for date filtering."
  ([fact-store tag-name] (by-tag fact-store tag-name nil))
  ([fact-store tag-name opts]
   (p/find-nodes-by-tag fact-store tag-name opts)))

(defn by-tags
  "Nodes matching ALL given tags (intersection).
   Optional :since/:until (java.util.Date) for date filtering on :node/updated-at."
  [fact-store {:keys [tags] :as opts}]
  (when (seq tags)
    (if (and (= 1 (count tags)) (not (:since opts)) (not (:until opts)))
      (by-tag fact-store (first tags))
      (p/find-nodes-by-tags fact-store tags opts))))

(defn by-any-tags
  "Nodes matching ANY of the given tags (union)."
  [fact-store {:keys [tags]}]
  (when (seq tags)
    (p/find-nodes-by-any-tags fact-store tags)))

(defn by-date-range
  "Nodes within a date range on :node/updated-at, no tag filter."
  [fact-store {:keys [since until]}]
  (p/find-nodes-by-date fact-store since until))

(defn all-nodes
  "All nodes (for 'latest N' without any filter)."
  [fact-store]
  (let [eids (p/all-nodes fact-store)]
    (mapv #(p/find-node fact-store %) eids)))

;; --- Browse (flat list sorted by count) ---

(defn browse
  "Returns all tags sorted by node-count desc. Supports limit/offset."
  [fact-store {:keys [limit offset] :or {limit 50 offset 0}}]
  (->> (p/all-tags fact-store)
       (sort-by :tag/node-count (fn [a b] (compare (or b 0) (or a 0))))
       (drop offset)
       (take limit)
       vec))

;; --- Count by tag sets (computed intersection) ---

(defn count-by-tag-sets
  "Returns counts for each tag set (intersection).
   Input:  [[\"clj\" \"error-handling\"] [\"python\"]]
   Output: [{:tags [...] :count N} ...]"
  [fact-store registry tag-sets]
  (metrics/timed registry metrics/read-duration {:operation "count_by_tag_sets"}
    (p/count-nodes-by-tag-sets fact-store tag-sets)))

;; --- Fetch by tag sets (batch with limit) ---

(defn fetch-by-tag-sets
  "Fetches facts for each tag set (intersection). Per-set limit.
   Optional :since/:until (java.util.Date) for date filtering.
   Optional :sort-by — \"date\" (default) or \"weight\".
   When tag-sets is nil/empty, returns all matching facts in a single group.
   Output: [{:tags [...] :facts [...]} ...]"
  [fact-store registry tag-sets {:keys [limit since until sort-by] :or {limit 50}}]
  (metrics/timed registry metrics/read-duration {:operation "fetch_by_tag_sets"}
    (if (seq tag-sets)
      ;; Tags provided: group by tag set, apply date filter globally
      (mapv (fn [tags]
              {:tags  tags
               :facts (->> (by-tags fact-store {:tags tags :since since :until until})
                           (sort-facts fact-store sort-by)
                           (take limit)
                           vec)})
            tag-sets)
      ;; No tags: date-only or "latest N"
      (let [results (if (or since until)
                      (by-date-range fact-store {:since since :until until})
                      (all-nodes fact-store))]
        [{:tags  []
          :facts (->> results (sort-facts fact-store sort-by) (take limit) vec)}]))))

;; --- Unified filter-based retrieval ---

(defn- has-all-tags?
  "Returns true if node has ALL specified tag names."
  [tag-names node]
  (let [node-tags (set (:node/tags node))]
    (every? node-tags tag-names)))

(defn- has-any-of-tags?
  "Returns true if node has ANY of the specified tag names."
  [tag-names node]
  (let [node-tags (set (:node/tags node))]
    (some node-tags tag-names)))

(defn- count-matching-tags
  "Returns the number of tag-names that the node has."
  [tag-names node]
  (let [node-tags (set (:node/tags node))]
    (count (filter node-tags tag-names))))

(defn- in-date-range?
  "Returns true if node's updated-at falls within [since, until)."
  [since until node]
  (let [updated (:node/updated-at node)]
    (and (or (nil? since) (not (.before updated since)))
         (or (nil? until) (.before updated until)))))

(defn- execute-filter
  "Executes a single filter. Returns {:filter <input> :facts [...] :total N}.
   Tags filter: :tags (ALL must match), :any-tags (at least one must match).
   When both provided: node must have ALL of :tags AND ANY of :any-tags.
   When :any-tags present, results include :match-count and sort by overlap (desc)
   as primary sort, with :sort-by as tiebreaker."
  [fact-store vector-store embedding registry {:keys [id session-id tags any-tags query exclude-tags since until limit offset sort-by] :as filter-spec}]
  (cond
    ;; Direct session-id lookup
    session-id
    (let [fact  (p/find-nodes-by-session fact-store session-id)
          facts (if (:node/content fact)
                  (enrich-effective-weight fact-store [fact])
                  [])]
      {:filter filter-spec
       :facts  facts
       :total  (count facts)})

    ;; Direct ID lookup (Datomic entity ID)
    id
    (let [fact  (p/find-node-by-eid fact-store id)
          facts (if (:node/content fact)
                  (enrich-effective-weight fact-store [fact])
                  [])]
      {:filter filter-spec
       :facts  facts
       :total  (count facts)})

    ;; Vector search path — post-filter by tags and dates, sorted by search score
    query
    (let [since-d       (date/parse-date-param since)
          until-d       (date/parse-date-param until)
          default-limit (or limit 10)
          ;; Fetch more from vector store to allow for post-filtering + offset
          search-limit  (* (+ default-limit (or offset 0))
                           (if (or tags any-tags since until) 5 1))
          hits          (node/search fact-store vector-store embedding query search-limit)
          filtered      (cond->> hits
                          tags         (filter (partial has-all-tags? tags))
                          any-tags     (filter (partial has-any-of-tags? any-tags))
                          exclude-tags (remove (partial has-any-of-tags? exclude-tags))
                          since-d      (filter (partial in-date-range? since-d nil))
                          until-d      (filter (partial in-date-range? nil until-d)))
          total         (count (vec filtered))
          page          (->> filtered (drop (or offset 0)) (take default-limit)
                             vec (enrich-effective-weight fact-store))]
      {:filter filter-spec
       :facts  page
       :total  total})

    ;; DB path — tag intersection/union + date filter
    :else
    (let [since-d       (date/parse-date-param since)
          until-d       (date/parse-date-param until)
          default-limit (or limit 50)
          ;; Fetch candidates: tags (intersection) narrowed by any-tags (union),
          ;; or any-tags alone (union), or date/all fallback
          results       (cond
                          ;; Both: intersection first, then post-filter by any-tags
                          (and (seq tags) (seq any-tags))
                          (->> (by-tags fact-store {:tags tags :since since-d :until until-d})
                               (filter (partial has-any-of-tags? any-tags)))

                          ;; Only required tags: intersection (existing behavior)
                          (seq tags)
                          (by-tags fact-store {:tags tags :since since-d :until until-d})

                          ;; Only any-tags: union, then post-filter by date
                          (seq any-tags)
                          (cond->> (by-any-tags fact-store {:tags any-tags})
                            since-d (filter (partial in-date-range? since-d nil))
                            until-d (filter (partial in-date-range? nil until-d)))

                          (or since-d until-d)
                          (by-date-range fact-store {:since since-d :until until-d})

                          :else (all-nodes fact-store))
          ;; When any-tags present, primary sort = overlap count (desc),
          ;; tiebreaker = sort-by (date or weight)
          sorted        (if (seq any-tags)
                          (let [enriched (sort-facts fact-store sort-by results)]
                            (->> enriched
                                 (map #(assoc % :match-count
                                              (count-matching-tags any-tags %)))
                                 (sort-by :match-count #(compare %2 %1))))
                          (sort-facts fact-store sort-by results))
          excluded      (cond->> sorted
                          exclude-tags (remove (partial has-any-of-tags? exclude-tags)))
          total         (count excluded)
          page          (->> excluded (drop (or offset 0)) (take default-limit) vec)]
      {:filter filter-spec
       :facts  page
       :total  total})))

(defn fetch-by-filters
  "Executes an array of filters. Each filter is a map with optional keys:
   :tags, :query, :since, :until, :limit.
   Returns [{:filter {...} :facts [...]} ...]."
  [fact-store vector-store embedding registry filters]
  (metrics/timed registry metrics/read-duration {:operation "fetch_by_filters"}
    (mapv (partial execute-filter fact-store vector-store embedding registry) filters)))

;; --- Reconciliation ---

(defn reconcile-counts!
  "Recomputes all :tag/node-count from actual data. Idempotent.
   Returns {:tags-updated N :duration-ms N}."
  [fact-store]
  (let [start (System/nanoTime)]
    (p/reconcile-tag-counts! fact-store)
    {:duration-ms (/ (double (- (System/nanoTime) start)) 1e6)}))

;; --- Node info ---

(defn node-tags
  "Returns all tag names for a given node (by entity ID)."
  [fact-store eid]
  (p/node-tags fact-store eid))
