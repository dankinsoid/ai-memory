;; @ai-generated(guided)
(ns ai-memory.service.facts
  "Domain operations on facts (memory nodes): CRUD, search, reinforcement.
   Orchestrates FactStore, VectorStore, and EmbeddingProvider protocols.
   Blob disk I/O delegated to service.blobs (facts owns the lifecycle).
   All tag creation goes through service.tags/ensure!."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.service.tags :as tags]
            [ai-memory.service.blobs :as blobs]
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
   Optionally creates a blob on disk when :blob-content or :path is present.
   `stores` — map with :fact-store, :vector-store, :tag-vector-store, :embedding, :blob-path
   `data`   — {:content str, :tags [str], ...} passed to p/create-node!
              Blob params (optional): :blob-content (text) or :path (file), :title, :summary, :type, :project
              When blob, :summary (or :content) becomes fact content; :blob-content goes to disk.
   Returns {:id eid, :blob-dir str|nil, :status :created}."
  [stores data]
  (let [blob?    (or (:blob-content data) (:path data))
        blob-dir (when blob?
                   (blobs/write-to-disk! (:blob-path stores) data))
        content  (if blob?
                   (or (:summary data) (:content data))
                   (:content data))
        tags     (when (seq (:tags data))
                   (tags/ensure! stores (mapv str/trim (:tags data))))
        result   (p/create-node! (:fact-store stores)
                                 (cond-> (assoc data :tags tags :content content)
                                   blob-dir (assoc :blob-dir blob-dir)))
        eid      (:id result)]
    (embed-node! stores eid content)
    {:id eid :blob-dir blob-dir :status :created}))

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
  "Upsert: if eid is non-nil, updates existing node; if nil, creates new node.
   Re-embeds content if changed. Weight update bumps cycle (tick) as well.
   If the fact has a blob-dir, also updates blob on disk when :blob-content or :summary present.
   `stores` — map with :fact-store, :vector-store, :tag-vector-store, :embedding, :blob-path
   `eid`    — entity id, or nil to create a new node
   `opts`   — {:content str, :tags [str], :tag-mode :replace|:merge, :weight N,
               :blob-content str, :summary str,
               :session-id str, :blob-dir str, :sources str}
              tag-mode defaults to :replace. session-id/blob-dir/sources only used on create.
              :blob-content updates file on disk. :summary updates blob meta + becomes fact content."
  [stores eid {:keys [content tags tag-mode weight blob-content summary] :as opts}]
  (if (nil? eid)
    (create! stores opts)
    (let [fs      (:fact-store stores)
          ;; Check if fact has a blob — update disk if blob params provided
          node-bd (when (or blob-content summary)
                    (:node/blob-dir (p/find-node-by-eid fs eid)))
          _       (when node-bd
                    (blobs/update-on-disk! (:blob-path stores) node-bd
                                           {:content blob-content :summary summary :tags tags}))
          ;; For blobs: summary becomes fact content; for plain facts: content as-is
          fact-content (if node-bd (or summary content) content)]
      (when (some? weight)
        (p/update-node-weight! fs eid weight))
      (when (and fact-content (not (str/blank? fact-content)))
        (p/update-node-content! fs eid fact-content)
        (embed-node! stores eid fact-content))
      (apply-tags! stores eid tags tag-mode)
      {:id eid :blob-dir node-bd :status :patched})))

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
   When :id is present, updates existing fact directly (skips dedup).
   When :blob-content is present, creates/updates blob on disk.
   `stores`          — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `node-data`       — {:content str, :tags [str], :id long?, :blob-content str?}
   `opts`            — {:reinforcement-factor N, :dedup-threshold N}
   Returns {:id eid, :status :created|:reinforced|:patched}."
  [stores node-data opts]
  (let [explicit-id (:id node-data)
        content     (:content node-data)
        factor      (or (:reinforcement-factor opts) 0.5)
        tags        (when (seq (:tags node-data))
                      (mapv str/trim (:tags node-data)))]
    (if explicit-id
      ;; Explicit ID → direct update, skip dedup
      (let [current-w  (or (:node/weight (p/find-node-by-eid (:fact-store stores) explicit-id)) 0.0)
            new-w      (decay/apply-score current-w 1.0 factor)]
        (patch! stores explicit-id
                (cond-> {:tags     tags
                         :tag-mode :merge
                         :weight   new-w}
                  content                  (assoc :content content)
                  (:blob-content node-data) (assoc :blob-content (:blob-content node-data))))
        {:id explicit-id :status :patched})
      ;; No ID → dedup search → reinforce or create
      (let [duplicate (when content
                        (find-duplicate stores content node-data
                                        (or (:dedup-threshold opts) 0.85)))]
        (if duplicate
          (let [eid        (:db/id duplicate)
                current-w  (or (:node/weight duplicate) 0.0)
                new-w      (decay/apply-score current-w 1.0 factor)]
            (patch! stores eid {:content  content
                                :tags     tags
                                :tag-mode :merge
                                :weight   new-w})
            {:id eid :status :reinforced})
          (let [result (patch! stores nil (-> node-data
                                              (dissoc :node-type :id)
                                              (assoc :tags tags)))]
            {:id (:id result) :status :created}))))))

(defn delete!
  "Deletes a fact from fact store, vector store, and filesystem.
   `ctx` — service context with :fact-store, :vector-store, :blob-path
   `eid` — entity id
   Returns {:deleted-id eid :blob-dir dir}. Throws if not found."
  [ctx eid]
  (delete/delete-node! (:fact-store ctx) (:vector-store ctx) (:blob-path ctx) eid))

(defn search
  "Filtered fact retrieval via tag-query filters.
   `ctx`     — service context with :fact-store, :vector-store, :embedding, :metrics
   `filters` — filter spec for tag-query/fetch-by-filters"
  [ctx filters]
  (let [tag-query (requiring-resolve 'ai-memory.tag.query/fetch-by-filters)]
    (tag-query (:fact-store ctx) (:vector-store ctx)
               (:embedding ctx) (:metrics ctx) filters)))

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
   `ctx`            — service context with :fact-store
   `reinforcements` — [{:id eid :score N} ...]
   Returns {:reinforced N :details [...]}"
  [ctx reinforcements]
  (let [fs     (:fact-store ctx)
        factor 0.5
        results (mapv (fn [{:keys [id score]}]
                        (let [node      (p/find-node fs id)
                              current-w (or (:node/weight node) 0.0)
                              new-w     (decay/apply-score current-w score factor)]
                          (p/update-node-weight! fs id new-w)
                          {:id id :score score}))
                      reinforcements)]
    {:reinforced (count results)
     :details    results}))


(defn promote-eternal!
  "Promotes an edge to eternal (weight = 1.0, never decays).
   `stores` — map with :fact-store
   `edge-id` — edge UUID"
  [stores edge-id]
  (p/promote-edge-eternal! (:fact-store stores) edge-id))
