(ns ai-memory.tag.resolve
  "Resolves candidate tag names to similar existing tags via embedding cosine similarity.
   Maintains an in-memory cache of tag embeddings, refreshed incrementally on new tags."
  (:require [ai-memory.store.protocols :as p]))

;; --- Cosine similarity ---

;; @ai-generated(guided)
(defn- dot-product
  "Dot product of two double arrays. OpenAI embeddings are L2-normalized,
   so dot product equals cosine similarity."
  ^double [^doubles a ^doubles b]
  (let [n (alength a)]
    (loop [i 0, acc 0.0]
      (if (< i n)
        (recur (inc i) (+ acc (* (aget a i) (aget b i))))
        acc))))

;; --- Tag embedding cache ---
;; {::names #{"tag1" "tag2"}, ::vectors {"tag1" double[] ...}}

(defonce ^:private cache (atom {::names #{} ::vectors {}}))

(defn- ensure-cache!
  "Ensures all current tags are embedded. Embeds only the delta (new tags).
   Returns the current vectors map."
  [fact-store embedding]
  (let [all-tags    (set (map :tag/name (p/all-tags fact-store)))
        cached      (::names @cache)
        new-tags    (into [] (remove cached) all-tags)
        ;; Remove deleted tags from cache
        removed     (into [] (remove all-tags) cached)]
    (when (or (seq new-tags) (seq removed))
      (let [new-vecs (when (seq new-tags)
                       (let [raw (p/embed-batch embedding new-tags)]
                         (zipmap new-tags (map double-array raw))))]
        (swap! cache (fn [c]
                       (-> c
                           (update ::vectors #(as-> % v
                                                (apply dissoc v removed)
                                                (merge v new-vecs)))
                           (assoc ::names all-tags))))))
    (::vectors @cache)))

;; --- Public API ---

(defn resolve-tags
  "For each candidate tag, finds similar existing tags by cosine similarity.
   Returns seq of {:candidate str :matches [{:tag str :score double} ...]},
   sorted by score descending, filtered by threshold."
  [fact-store embedding candidates
   {:keys [threshold top-k] :or {threshold 0.7 top-k 5}}]
  (let [vectors      (ensure-cache! fact-store embedding)
        ;; Embed all candidates in one batch call
        cand-raw     (p/embed-batch embedding candidates)
        cand-vectors (mapv double-array cand-raw)]
    (mapv (fn [candidate cand-vec]
            (let [matches (->> vectors
                               (keep (fn [[tag-name tag-vec]]
                                       (let [score (dot-product cand-vec tag-vec)]
                                         (when (>= score threshold)
                                           {:tag tag-name :score score}))))
                               (sort-by :score >)
                               (take top-k)
                               vec)]
              {:candidate candidate :matches matches}))
          candidates cand-vectors)))
