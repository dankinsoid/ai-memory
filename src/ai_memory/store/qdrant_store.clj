(ns ai-memory.store.qdrant-store
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.embedding.vector-store :as vs]))

;; `dim-atom` stores the embedding dimension so delete-all! can recreate
;; the collection with the correct vector size after it was set by ensure-store!.
(defrecord QdrantStore [base-url dim-atom]
  p/VectorStore
  (ensure-store! [_ dim]
    (reset! dim-atom dim)
    (vs/ensure-collection! base-url dim))
  (upsert!       [_ id vector payload]    (vs/upsert-point! base-url id vector payload))
  (search        [_ qvec top-k _opts]     (vs/search base-url qvec top-k))
  (delete!       [_ id]                   (vs/delete-point! base-url id))
  (delete-all!   [_]                      (vs/delete-all-points! base-url (or @dim-atom 1536)))
  (store-info    [_]                      (vs/collection-info base-url)))

(defn create [cfg]
  (->QdrantStore (:qdrant-url cfg) (atom nil)))
