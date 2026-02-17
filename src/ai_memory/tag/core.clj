(ns ai-memory.tag.core
  "Tag taxonomy CRUD. Tags form a tree via :tag/parent refs.
   :tag/path (unique/identity) is the tag identifier."
  (:require [datomic.api :as d]
            [clojure.string :as str]))

(defn find-by-path
  "Returns tag entity map or nil."
  [db path]
  (let [result (d/pull db '[:tag/name :tag/path {:tag/parent [:tag/path :tag/name]}]
                       [:tag/path path])]
    (when (:tag/path result) result)))

(defn find-by-name
  "Returns all tags with the given leaf name (may be in different branches)."
  [db name]
  (d/q '[:find [(pull ?t [:tag/name :tag/path {:tag/parent [:tag/path :tag/name]}]) ...]
         :in $ ?name
         :where [?t :tag/name ?name]]
       db name))

(defn create-tag!
  "Creates a tag. Returns the tag path.
   `parent-path` — path of parent tag, or nil for root."
  [conn {:keys [name parent-path]}]
  (let [path (if parent-path
               (str parent-path "/" name)
               name)
        tx-data (cond-> {:tag/name name
                         :tag/path path}
                  parent-path (assoc :tag/parent [:tag/path parent-path]))]
    @(d/transact conn [tx-data])
    path))

(defn ensure-tag!
  "Gets or creates a tag by path. Creates intermediate parents if needed.
   Returns the tag path."
  [conn path]
  (let [db (d/db conn)]
    (if (find-by-path db path)
      path
      (let [segments (str/split path #"/")]
        (loop [i 0]
          (when (< i (count segments))
            (let [current-path (str/join "/" (take (inc i) segments))
                  parent-path  (when (pos? i)
                                 (str/join "/" (take i segments)))
                  db           (d/db conn)]
              (when-not (find-by-path db current-path)
                (create-tag! conn {:name        (nth segments i)
                                   :parent-path parent-path}))
              (recur (inc i)))))
        path))))

(defn root-tags
  "Returns all root categories (tags with no parent)."
  [db]
  (d/q '[:find [(pull ?t [:tag/name :tag/path]) ...]
         :where
         [?t :tag/path]
         (not [?t :tag/parent])]
       db))

(defn children
  "Returns direct children of a tag."
  [db tag-path]
  (d/q '[:find [(pull ?c [:tag/name :tag/path]) ...]
         :in $ ?parent-path
         :where
         [?p :tag/path ?parent-path]
         [?c :tag/parent ?p]]
       db tag-path))

(defn subtree
  "Returns all descendant tag entity IDs (not including the tag itself)."
  [db tag-path]
  (let [direct (children db tag-path)]
    (into direct
          (mapcat #(subtree db (:tag/path %)))
          direct)))

(defn- tag-with-children [db tag]
  (let [kids (children db (:tag/path tag))]
    (assoc tag :children
           (mapv #(tag-with-children db %) kids))))

(defn full-taxonomy
  "Returns the entire tag tree from roots as nested maps."
  [db]
  (let [roots (root-tags db)]
    (mapv #(tag-with-children db %) roots)))
