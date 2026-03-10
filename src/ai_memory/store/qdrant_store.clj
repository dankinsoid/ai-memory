(ns ai-memory.store.qdrant-store
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.embedding.vector-store :as vs]))

;; `dim-atom` stores the embedding dimension so delete-all! can recreate
;; the collection with the correct vector size after it was set by ensure-store!.
(defrecord QdrantStore [base-url collection dim-atom]
  p/VectorStore
  (ensure-store! [_ dim]
    (reset! dim-atom dim)
    (vs/ensure-collection! base-url collection dim))
  (upsert!       [_ id vector payload]    (vs/upsert-point! base-url collection id vector payload))
  (search        [_ qvec top-k _opts]     (vs/search base-url collection qvec top-k))
  (delete!       [_ id]                   (vs/delete-point! base-url collection id))
  (delete-all!   [_]                      (vs/delete-all-points! base-url collection (or @dim-atom 1536)))
  (store-info    [_]                      (vs/collection-info base-url collection))
  (scroll-all    [_]                      (vs/scroll-all-points base-url collection)))

(defn create
  "Creates a QdrantStore for the given Qdrant collection.
   `cfg` — config map with :qdrant-url.
   `collection` — Qdrant collection name (e.g. \"nodes\", \"tags\")."
  [cfg collection]
  (->QdrantStore (:qdrant-url cfg) collection (atom nil)))
