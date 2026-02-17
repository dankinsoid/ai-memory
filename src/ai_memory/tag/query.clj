(ns ai-memory.tag.query
  "Tag-based retrieval: set operations over tagged nodes."
  (:require [ai-memory.tag.core :as tag]
            [datomic.api :as d]))

(def ^:private node-pull-spec
  [:node/id :node/content :node/weight :node/cycle
   {:node/type [:db/ident]}
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

(defn browse
  "Returns taxonomy subtree with node counts per tag.
   If path is nil, returns root categories."
  [db path]
  (let [tags     (if path
                   (tag/children db path)
                   (tag/root-tags db))
        counts   (into {}
                       (d/q '[:find ?path (count ?n)
                              :where
                              [?t :tag/path ?path]
                              [?n :node/tag-refs ?t]]
                            db))]
    (mapv (fn [t]
            (assoc t :node-count (get counts (:tag/path t) 0)))
          tags)))

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
