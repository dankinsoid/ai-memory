;; @ai-generated(guided)
(ns ai-memory.service.facts
  "Domain operations on facts (memory nodes): CRUD, search, reinforcement, remember pipeline.
   Orchestrates FactStore, VectorStore, and EmbeddingProvider protocols.
   All tag creation goes through service.tags/ensure!."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.service.tags :as tags]
            [ai-memory.graph.write :as write]
            [ai-memory.graph.node :as node]
            [ai-memory.graph.delete :as delete]
            [ai-memory.decay.core :as decay]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defn- embed-node!
  "Embeds node content into vector store. Logs warning on failure, never throws."
  [stores eid content]
  (try
    (let [vector (p/embed-document (:embedding stores) content)]
      (p/upsert! (:vector-store stores) eid vector {}))
    (catch Exception e
      (log/warn e "Failed to embed node" eid))))

(defn create!
  "Creates a new fact node with tags and vector embedding.
   `stores` — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `data`   — {:content str, :tags [str], ...} passed to p/create-node!
   Returns {:id eid :status :created}."
  [stores data]
  (let [tags (when (seq (:tags data))
               (tags/ensure! stores (mapv str/trim (:tags data))))
        result (p/create-node! (:fact-store stores)
                               (assoc data :tags tags))
        eid (:id result)]
    (embed-node! stores eid (:content data))
    {:id eid :status :created}))

(defn get-by-id
  "Returns a single fact with full metadata, edges, and effective weight.
   `stores` — map with :fact-store
   `eid`    — entity id (long)
   Returns map with :fact, :effective-weight, :edges, etc. or nil if not found."
  [stores eid]
  (let [fs   (:fact-store stores)
        node (p/find-node-by-eid fs eid)]
    (when (:node/content node)
      (let [tick  (p/current-tick fs)
            eff-w (decay/effective-weight
                    (or (:node/weight node) 0.0)
                    (or (:node/cycle node) 0)
                    tick decay/default-decay-k)
            edges (p/find-edges-from fs eid)]
        {:fact             node
         :effective-weight eff-w
         :edges            edges}))))

(defn update!
  "Updates a fact's content, tags, and/or weight. Reinforces on access.
   `stores` — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `cfg`    — {:reinforcement-factor N}
   `eid`    — entity id
   `opts`   — {:content str, :tags [str], :weight double} — all optional"
  [stores cfg eid {:keys [content tags weight]}]
  (let [fs     (:fact-store stores)
        factor (or (:reinforcement-factor cfg) 0.5)]
    (if (and content (not (str/blank? content)))
      (do
        (p/reinforce-node! fs eid content 1.0 factor)
        (embed-node! stores eid content))
      (p/reinforce-weight! fs eid 1.0 factor))
    (when (some? tags)
      (let [tag-names (mapv str/trim tags)]
        (tags/ensure! stores tag-names)
        (p/replace-node-tags! fs eid tag-names)))
    (when (some? weight)
      (p/set-node-weight! fs eid weight))
    {:status "updated"}))

(defn delete!
  "Deletes a fact from fact store, vector store, and filesystem.
   `stores`    — map with :fact-store, :vector-store
   `blob-path` — filesystem base path for blobs
   `eid`       — entity id
   Returns {:deleted-id eid :blob-dir dir}. Throws if not found."
  [stores blob-path eid]
  (delete/delete-node! (:fact-store stores) (:vector-store stores) blob-path eid))

(defn search
  "Filtered fact retrieval via tag-query filters.
   `stores`  — map with :fact-store, :vector-store, :embedding
   `metrics` — prometheus registry or nil
   `filters` — filter spec for tag-query/fetch-by-filters"
  [stores metrics filters]
  (let [tag-query (requiring-resolve 'ai-memory.tag.query/fetch-by-filters)]
    (tag-query (:fact-store stores) (:vector-store stores)
               (:embedding stores) metrics filters)))

(defn recall
  "Returns facts matching ALL given tags.
   `stores` — map with :fact-store
   `tags`   — seq of tag name strings
   Returns seq of nodes or [] if no tags."
  [stores tags]
  (if (seq tags)
    (let [tag-query (requiring-resolve 'ai-memory.tag.query/by-tags)]
      (tag-query (:fact-store stores) {:tags tags}))
    []))

(defn reinforce!
  "Batch reinforcement: bumps weight/cycle for multiple facts.
   `stores`         — map with :fact-store
   `cfg`            — {:reinforcement-factor N}
   `reinforcements` — [{:id eid :score N} ...]
   Returns {:reinforced N :details [...]}"
  [stores cfg reinforcements]
  (let [fs     (:fact-store stores)
        factor (or (:reinforcement-factor cfg) 0.5)
        results (mapv (fn [{:keys [id score]}]
                        (p/reinforce-weight! fs id score factor)
                        {:id id :score score})
                      reinforcements)]
    {:reinforced (count results)
     :details    results}))

(defn remember!
  "Full write pipeline: dedup, edges, context linking.
   `stores` — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `cfg`    — {:metrics registry}
   `params` — {:nodes [...] :context-id ... :project ...}
   Returns {:nodes [...] :edges-created N ...}."
  [stores cfg params]
  (let [full-params (assoc params :metrics (:metrics cfg))]
    (write/remember stores full-params)))

(defn promote-eternal!
  "Promotes an edge to eternal (weight = 1.0, never decays).
   `stores` — map with :fact-store
   `edge-id` — edge UUID"
  [stores edge-id]
  (p/promote-edge-eternal! (:fact-store stores) edge-id))
