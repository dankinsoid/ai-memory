;; @ai-generated(guided)
(ns ai-memory.service.tags
  "Tag operations: creation with Qdrant embedding, browsing, counting, resolution.
   Single entry point for tag creation — all callers must go through `ensure!`."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.tag.core :as tag]
            [ai-memory.tag.query :as tag-query]
            [ai-memory.tag.resolve :as tag-resolve]
            [clojure.tools.logging :as log]))

(defn ensure!
  "Ensures tags exist in Datomic and embeds new ones into the tag vector store.
   This is THE single code path for tag creation — no other code should call
   p/ensure-tag! + embed directly.
   `stores` — map with :fact-store, :tag-vector-store, :embedding
   `tag-names` — seq of tag name strings
   Returns tag-names."
  [stores tag-names]
  (let [fs  (:fact-store stores)
        tvs (:tag-vector-store stores)
        emb (:embedding stores)]
    (doseq [t tag-names]
      (when (p/ensure-tag! fs t)
        (try
          (let [vector (p/embed-document emb t)]
            (p/upsert! tvs (tag/tag-point-id t) vector {:tag_name t}))
          (catch Exception e
            (log/warn e "Failed to vectorize tag" t)))))
    tag-names))

(defn browse
  "Returns paginated tag list sorted by node count.
   `stores` — map with :fact-store
   `opts`   — {:limit N :offset N}"
  [stores opts]
  (tag-query/browse (:fact-store stores) opts))

(defn count-by-sets
  "Counts facts for each tag set.
   `ctx`      — service context with :fact-store, :metrics
   `tag-sets` — vec of tag-name vecs"
  [ctx tag-sets]
  (tag-query/count-by-tag-sets (:fact-store ctx) (:metrics ctx) tag-sets))

(defn resolve-tags
  "Fuzzy tag resolution via vector similarity search in Qdrant.
   `stores`     — map with :tag-vector-store, :embedding
   `candidates` — seq of candidate tag strings
   `opts`       — {:threshold N :top-k N}"
  [stores candidates opts]
  (tag-resolve/resolve-tags (:tag-vector-store stores)
                            (:embedding stores)
                            candidates
                            opts))

(defn seed!
  "Backfills tag vectors into Qdrant for tags that exist in Datomic but
   are missing from the vector store. Called at startup and on reindex.
   `stores` — map with :fact-store, :tag-vector-store, :embedding"
  [stores]
  (tag-resolve/seed-tags! (:fact-store stores)
                          (:tag-vector-store stores)
                          (:embedding stores)))
