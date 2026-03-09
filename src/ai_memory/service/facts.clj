;; @ai-generated(guided)
(ns ai-memory.service.facts
  "Domain operations on facts (memory nodes): CRUD, search, reinforcement.
   Orchestrates FactStore, VectorStore, and EmbeddingProvider protocols.
   All tag creation goes through service.tags/ensure!."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.service.tags :as tags]
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

(defn- apply-tags!
  "Ensures tags exist and applies them to a node.
   `tag-mode` — :replace (default, removes old tags not in new set) or :merge (adds without removing)."
  [stores eid tags tag-mode]
  (when (some? tags)
    (let [fs       (:fact-store stores)
          tag-names (mapv str/trim tags)]
      (tags/ensure! stores tag-names)
      (if (= :merge tag-mode)
        (p/update-node-tags! fs eid tag-names)
        (p/replace-node-tags! fs eid tag-names)))))

(defn patch!
  "Direct DB update: overwrites content/tags without affecting weight or cycle.
   Use for technical metadata updates (session summaries, project facts, tag corrections).
   Re-embeds content if changed.
   `stores` — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `eid`    — entity id
   `opts`   — {:content str, :tags [str], :tag-mode :replace|:merge}
              All fields optional. tag-mode defaults to :replace."
  [stores eid {:keys [content tags tag-mode]}]
  (let [fs (:fact-store stores)]
    (when (and content (not (str/blank? content)))
      (p/update-node-content! fs eid content)
      (embed-node! stores eid content))
    (apply-tags! stores eid tags tag-mode)
    {:status "patched"}))

(defn- entity-node?
  "Entity nodes use exact content match for dedup instead of vector search."
  [node-data]
  (some #(= % "entity") (:tags node-data)))

(defn- find-duplicate
  "Entity nodes: exact content match. Other nodes: vector similarity search."
  [stores content node-data dedup-threshold]
  (if (entity-node? node-data)
    (p/find-node-by-content (:fact-store stores) content)
    (try
      (node/find-duplicate (:fact-store stores) (:vector-store stores)
                           (:embedding stores) content dedup-threshold)
      (catch Exception e
        (log/warn e "Dedup search failed, will create new node")
        nil))))

(defn remember!
  "Semantic upsert: dedup search → reinforce (found) or create (new).
   For the remember pipeline — the fact was accessed/used, so weight/cycle are bumped.
   Delegates content/tags/embedding to patch! (reinforced) or create! (new).
   `stores`          — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `node-data`       — {:content str, :tags [str], ...}
   `opts`            — {:reinforcement-factor N, :dedup-threshold N}
   Returns {:id eid, :status :created|:reinforced}."
  [stores node-data opts]
  (let [fs        (:fact-store stores)
        content   (:content node-data)
        factor    (or (:reinforcement-factor opts) 0.5)
        threshold (or (:dedup-threshold opts) 0.85)
        tags      (when (seq (:tags node-data))
                    (mapv str/trim (:tags node-data)))
        duplicate (find-duplicate stores content node-data threshold)]
    (if duplicate
      (let [eid (:db/id duplicate)]
        (p/reinforce-weight! fs eid 1.0 factor)
        (patch! stores eid {:content  content
                            :tags     tags
                            :tag-mode :merge})
        {:id eid :status :reinforced})
      (let [result (create! stores (-> node-data
                                       (dissoc :node-type)
                                       (assoc :tags tags)))]
        {:id (:id result) :status :created}))))

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


(defn promote-eternal!
  "Promotes an edge to eternal (weight = 1.0, never decays).
   `stores` — map with :fact-store
   `edge-id` — edge UUID"
  [stores edge-id]
  (p/promote-edge-eternal! (:fact-store stores) edge-id))
