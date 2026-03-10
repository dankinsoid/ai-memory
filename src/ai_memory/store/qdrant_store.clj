(ns ai-memory.store.qdrant-store
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.embedding.vector-store :as vs]))

;; `dim-atom` stores the embedding dimension so delete-all! can recreate
;; the collection with the correct vector size after it was set by ensure-store!.
(defrecord QdrantStore [qdrant-cfg collection dim-atom]
  p/VectorStore
  (ensure-store! [_ dim]
    (reset! dim-atom dim)
    (vs/ensure-collection! qdrant-cfg collection dim))
  (upsert!       [_ id vector payload]    (vs/upsert-point! qdrant-cfg collection id vector payload))
  (search        [_ qvec top-k _opts]     (vs/search qdrant-cfg collection qvec top-k))
  (delete!       [_ id]                   (vs/delete-point! qdrant-cfg collection id))
  (delete-all!   [_]                      (vs/delete-all-points! qdrant-cfg collection (or @dim-atom 1536)))
  (store-info    [_]                      (vs/collection-info qdrant-cfg collection))
  (scroll-all    [_]                      (vs/scroll-all-points qdrant-cfg collection)))

(defn create
  "Creates a QdrantStore for the given Qdrant collection.
   `cfg` — app config map with :qdrant-url and optional :qdrant-api-key.
   `collection` — Qdrant collection name (e.g. \"nodes\", \"tags\")."
  [cfg collection]
  (->QdrantStore {:url     (:qdrant-url cfg)
                   :api-key (:qdrant-api-key cfg)}
                  collection (atom nil)))
