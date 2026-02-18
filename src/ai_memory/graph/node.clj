;; Node CRUD — ACTIVE (used by write pipeline).
;; See ADR-009 for retrieval architecture.

(ns ai-memory.graph.node
  (:require [datomic.api :as d]
            [ai-memory.embedding.core :as embedding]
            [ai-memory.embedding.vector-store :as vs]
            [clojure.tools.logging :as log])
  (:import [java.util Date]))

(defn- now [] (Date.))

(defn- has-entity-tag?
  "Checks tag-refs (lookup refs or pulled maps) for entity tag."
  [tag-refs]
  (some (fn [ref]
          (cond
            (vector? ref) (= (second ref) "entity")
            (map? ref)    (= (:tag/name ref) "entity")
            :else         false))
        tag-refs))

(defn- skip-embedding?
  "Blob nodes (have blob-dir) and entity nodes (have entity tag) skip vectorization."
  [{:keys [blob-dir tag-refs]}]
  (or blob-dir (has-entity-tag? tag-refs)))

(defn- embed-async!
  "Embeds content in Qdrant. Logs warning on failure, never throws."
  [cfg node-uuid content]
  (try
    (let [vector (embedding/embed (:embedding-url cfg) content)]
      (vs/upsert-point! (:qdrant-url cfg) (str node-uuid) vector {}))
    (catch Exception e
      (log/warn e "Failed to vectorize node" node-uuid))))

(defn create-node
  "Creates a memory node in Datomic. Embeds content in Qdrant unless blob/entity.
   Automatically sets :node/created-at and :node/updated-at to now.
   `cfg` — {:embedding-url, :qdrant-url}.
   `tag-refs` — vec of lookup refs like [[:tag/name \"clj\"]].
   Optional keys: :blob-dir (string), :sources (set of strings)."
  [conn cfg {:keys [content tag-refs tick blob-dir sources session-id]}]
  (let [node-uuid    (d/squuid)
        ts           (now)
        base-tx      (cond-> {:db/id           (d/tempid :db.part/user)
                               :node/id         node-uuid
                               :node/content    content
                               :node/weight     1.0
                               :node/cycle      (or tick 0)
                               :node/created-at ts
                               :node/updated-at ts}
                       (seq tag-refs) (assoc :node/tag-refs tag-refs)
                       blob-dir       (assoc :node/blob-dir blob-dir)
                       (seq sources)  (assoc :node/sources sources)
                       session-id     (assoc :node/session-id session-id))
        count-txs    (mapv (fn [ref] [:fn/inc-tag-count (second ref) 1]) tag-refs)
        tx-result    @(d/transact conn (into [base-tx] count-txs))]
    (when-not (skip-embedding? {:blob-dir blob-dir :tag-refs tag-refs})
      (embed-async! cfg node-uuid content))
    {:tx-result tx-result
     :node-uuid node-uuid}))

(defn find-by-id [db node-id]
  (d/pull db '[*] [:node/id node-id]))

(def ^:private min-score 0.2)

(defn search
  "Finds nodes semantically similar to `text`.
   Returns nodes from Datomic enriched with :search/score, sorted by score desc."
  [db cfg text top-k]
  (let [qvec (embedding/embed (:embedding-url cfg) text)
        hits (vs/search (:qdrant-url cfg) qvec top-k)]
    (->> hits
         (filter #(>= (:score %) min-score))
         (mapv (fn [{:keys [id score]}]
                 (let [uuid (parse-uuid id)
                       node (d/pull db '[*] [:node/id uuid])]
                   (assoc node :search/score score))))
         (filterv :node/id))))

(defn find-duplicate
  "Returns the best semantic match if score >= threshold, or nil."
  [db cfg content threshold]
  (let [results (search db cfg content 1)]
    (when (and (seq results)
               (>= (:search/score (first results)) threshold))
      (first results))))

(defn find-entity-by-content
  "Finds an entity node (tagged entity) by exact content match. Returns entity map or nil."
  [db content]
  (let [eid (d/q '[:find ?e .
                   :in $ ?content
                   :where
                   [?e :node/content ?content]
                   [?e :node/tag-refs ?tag]
                   [?tag :tag/name "entity"]]
                 db content)]
    (when eid
      (d/pull db '[*] eid))))

(defn reinforce-node
  "Reinforces existing node: bumps weight, cycle, updated-at. Re-embeds unless blob/entity."
  [conn cfg node-uuid new-content delta current-cycle]
  (let [db   (d/db conn)
        node (d/pull db [:node/weight :node/blob-dir {:node/tag-refs [:tag/name]}]
                     [:node/id node-uuid])
        current-weight (or (:node/weight node) 1.0)
        new-weight     (+ current-weight delta)]
    @(d/transact conn
       [{:node/id         node-uuid
         :node/content    new-content
         :node/weight     new-weight
         :node/cycle      current-cycle
         :node/updated-at (now)}])
    (when-not (skip-embedding? {:blob-dir  (:node/blob-dir node)
                                :tag-refs  (:node/tag-refs node)})
      (embed-async! cfg node-uuid new-content))))

(defn find-recent
  "Returns node IDs with cycle >= min-tick."
  [db min-tick]
  (d/q '[:find [?nid ...]
         :in $ ?min-tick
         :where
         [?e :node/cycle ?c]
         [(>= ?c ?min-tick)]
         [?e :node/id ?nid]]
       db min-tick))

(defn update-tag-refs
  "Adds tag refs to an existing node (additive — cardinality/many).
   Increments tag counts only for tags not already on the node. Updates updated-at."
  [conn node-uuid tag-refs]
  (when (seq tag-refs)
    (let [db       (d/db conn)
          existing (set (d/q '[:find [?name ...]
                               :in $ ?nid
                               :where
                               [?n :node/id ?nid]
                               [?n :node/tag-refs ?t]
                               [?t :tag/name ?name]]
                             db node-uuid))
          new-refs (remove #(existing (second %)) tag-refs)
          count-txs (mapv (fn [ref] [:fn/inc-tag-count (second ref) 1]) new-refs)]
      @(d/transact conn
         (into [{:node/id         node-uuid
                 :node/tag-refs   tag-refs
                 :node/updated-at (now)}]
               count-txs)))))

(defn find-blobs
  "Finds blob nodes (have :node/blob-dir) sorted by created-at desc."
  [db {:keys [limit] :or {limit 20}}]
  (let [pull-expr [:node/id :node/content
                   :node/created-at :node/updated-at :node/blob-dir
                   {:node/tag-refs [:tag/name]}]
        results   (d/q '[:find [(pull ?n pull-expr) ...]
                         :in $ pull-expr
                         :where
                         [?n :node/blob-dir _]]
                       db pull-expr)]
    (->> results
         (sort-by :node/created-at #(compare %2 %1))
         (take limit)
         vec)))
