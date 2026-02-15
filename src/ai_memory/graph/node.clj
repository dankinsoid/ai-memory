(ns ai-memory.graph.node
  (:require [datomic.api :as d]
            [ai-memory.embedding.core :as embedding]
            [ai-memory.embedding.vector-store :as vs]
            [clojure.tools.logging :as log]))

(defn create-node
  "Creates a memory node in Datomic, embeds content, stores vector in Qdrant.
   `cfg` — {:embedding-url, :qdrant-url}."
  [conn cfg {:keys [content node-type scope tags tick]}]
  (let [node-uuid (d/squuid)
        node-type-kw (keyword "node.type" (name node-type))
        scope-kw (or scope :node.scope/global)
        tags (or tags [])
        tx-result @(d/transact conn
                     [{:db/id        (d/tempid :db.part/user)
                       :node/id      node-uuid
                       :node/content content
                       :node/type    node-type-kw
                       :node/scope   scope-kw
                       :node/tags    tags
                       :node/weight  1.0
                       :node/cycle   (or tick 0)}])]
    (try
      (let [vector (embedding/embed (:embedding-url cfg) content)]
        (vs/upsert-point! (:qdrant-url cfg)
                          (str node-uuid)
                          vector
                          {}))
      (catch Exception e
        (log/warn e "Failed to vectorize node" node-uuid)))
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

(defn reinforce-node
  "Reinforces existing node: updates content, re-embeds, bumps weight."
  [conn cfg node-uuid new-content delta current-cycle]
  (let [db (d/db conn)
        current-weight (or (:node/weight (d/pull db [:node/weight] [:node/id node-uuid])) 1.0)
        new-weight (+ current-weight delta)]
    @(d/transact conn
       [{:node/id      node-uuid
         :node/content new-content
         :node/weight  new-weight
         :node/cycle   current-cycle}])
    (try
      (let [vector (embedding/embed (:embedding-url cfg) new-content)]
        (vs/upsert-point! (:qdrant-url cfg)
                          (str node-uuid)
                          vector
                          {}))
      (catch Exception e
        (log/warn e "Failed to re-vectorize node" node-uuid)))))

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

(defn find-by-type [db node-type]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?type
         :where [?e :node/type ?type]]
       db (keyword "node.type" (name node-type))))
