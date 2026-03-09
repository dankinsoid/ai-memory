(ns ai-memory.tag.resolve
  "Resolves candidate tag names to similar existing tags via Qdrant vector search.
   Tags are embedded into a dedicated 'tags' Qdrant collection at creation time.
   seed-tags! backfills any existing tags that are missing from Qdrant."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.tag.core :as tag]
            [clojure.tools.logging :as log]))

;; --- Public API ---

(defn seed-tags!
  "Ensures all existing tags in Datomic have vectors in the tag vector store.
   Embeds missing tags in batch. Called at startup to backfill."
  [fact-store tag-vector-store embedding]
  (let [all-tags   (mapv :tag/name (p/all-tags fact-store))
        store-info (p/store-info tag-vector-store)
        stored     (or (:points-count store-info) 0)
        ;; If Qdrant has fewer points than tags, re-embed all.
        ;; Qdrant upsert is idempotent so safe to re-embed.
        to-embed   (if (< stored (count all-tags))
                     all-tags
                     [])]
    (when (seq to-embed)
      (log/info "Seeding" (count to-embed) "tag vectors into Qdrant")
      (let [vectors (p/embed-batch embedding to-embed)]
        (doseq [[tag-name vector] (map vector to-embed vectors)]
          (p/upsert! tag-vector-store (tag/tag-point-id tag-name) vector {:tag_name tag-name})))
      (log/info "Tag vector seeding complete"))
    {:seeded (count to-embed) :total (count all-tags)}))

(defn resolve-tags
  "For each candidate tag, finds similar existing tags via Qdrant vector search.
   Returns seq of {:candidate str :matches [{:tag str :score double} ...]},
   sorted by score descending, filtered by threshold.

   `tag-vector-store` — VectorStore pointing to the 'tags' collection.
   `embedding`        — EmbeddingProvider for embedding candidates.
   `candidates`       — seq of tag name strings to resolve.
   `opts`             — {:threshold double (default 0.7), :top-k int (default 5)}."
  [tag-vector-store embedding candidates
   {:keys [threshold top-k] :or {threshold 0.7 top-k 5}}]
  (let [cand-vectors (p/embed-batch embedding candidates)]
    (mapv (fn [candidate cand-vec]
            (let [hits    (p/search tag-vector-store cand-vec top-k nil)
                  matches (->> hits
                               (filter #(>= (:score %) threshold))
                               (mapv (fn [hit]
                                       {:tag   (get-in hit [:payload :tag_name])
                                        :score (:score hit)})))]
              {:candidate candidate :matches matches}))
          candidates cand-vectors)))
