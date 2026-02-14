(ns ai-memory.graph.node
  (:require [datomic.api :as d]
            [ai-memory.embedding.core :as embedding]
            [ai-memory.embedding.vector-store :as vs]
            [clojure.tools.logging :as log]))

(defn create-node
  "Creates a memory node in Datomic, embeds content, stores vector in Qdrant.
   `cfg` — {:embedding-url, :qdrant-url}."
  [conn cfg {:keys [content node-type scope tags]}]
  (let [node-uuid (d/squuid)
        node-type-kw (keyword "node.type" (name node-type))
        scope-kw (or scope :node.scope/global)
        tags (or tags [])
        ;; 1. Datomic — source of truth
        tx-result @(d/transact conn
                     [{:db/id        (d/tempid :db.part/user)
                       :node/id      node-uuid
                       :node/content content
                       :node/type    node-type-kw
                       :node/scope   scope-kw
                       :node/tags    tags
                       :node/weight  1.0
                       :node/cycle   0}])]
    ;; 2. Embed + Qdrant (best-effort)
    (try
      (let [vector (embedding/embed (:embedding-url cfg) content)]
        (vs/upsert-point! (:qdrant-url cfg)
                          (str node-uuid)
                          vector
                          {}))
      (catch Exception e
        (log/warn e "Failed to vectorize node" node-uuid "— will be synced later")))
    {:tx-result tx-result
     :node-uuid node-uuid}))

(defn find-by-id [db node-id]
  (d/pull db '[*] [:node/id node-id]))

(def ^:private min-score 0.2)

(defn search
  "Finds nodes semantically similar to `text`.
   Returns nodes from Datomic enriched with :search/score, sorted by score desc.
   Filters out results below `min-score`."
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

(defn find-by-type [db node-type]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?type
         :where [?e :node/type ?type]]
       db (keyword "node.type" (name node-type))))

