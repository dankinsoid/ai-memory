;; Node utilities — embedding-related helpers used by the write pipeline.
;; Pure DB operations have moved to ai-memory.store.datomic-store.
;; See ADR-009 for retrieval architecture.

(ns ai-memory.graph.node
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.blob.store :as blob-store]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; Kept for callers that need the pull spec shape (e.g. tag/query.clj).
(def node-pull-spec
  [:db/id :node/content :node/weight :node/cycle :node/sources
   :node/blob-dir :node/updated-at :node/tags])

(def ^:private min-score 0.2)
(def ^:private min-file-score 0.15)

(defn search
  "Finds nodes semantically similar to `text`.
   Searches both fact vectors (long IDs) and blob file vectors (UUID IDs).
   Deduplicates by entity: if same fact found via both, keeps max score.
   Returns nodes from FactStore enriched with :search/score, sorted by score desc.
   `fact-store`       — implements FactStore protocol
   `vector-store`     — implements VectorStore protocol
   `embedding`        — implements EmbeddingProvider protocol"
  [fact-store vector-store embedding text top-k]
  (let [qvec (p/embed-query embedding text)
        hits (p/search vector-store qvec top-k nil)]
    (->> hits
         (mapv (fn [{:keys [id score payload]}]
                 (let [file-hit? (string? id)
                       threshold (if file-hit? min-file-score min-score)]
                   (when (>= score threshold)
                     (let [eid  (if file-hit?
                                  (:fact_eid payload)
                                  (long id))
                           node (when eid (p/find-node-by-eid fact-store eid))]
                       (when (:node/content node)
                         (assoc node :search/score score)))))))
         (filterv some?)
         ;; Deduplicate: same fact may appear from fact vector + file vector; keep max score
         (group-by :db/id)
         vals
         (mapv #(apply max-key :search/score %)))))

(defn find-duplicate
  "Returns the best semantic match if score >= threshold, or nil."
  [fact-store vector-store embedding content threshold]
  (let [results (search fact-store vector-store embedding content 1)]
    (when (and (seq results)
               (>= (:search/score (first results)) threshold))
      (first results))))

(defn embed-file!
  "Vectorizes a blob file into vector store under a stable UUID point ID derived from blob-dir+file-path.
   payload contains fact-eid, blob-dir and file-path for reverse lookup.
   Logs warning on failure, never throws."
  [vector-store embedding fact-eid blob-dir file-path content]
  (try
    (let [point-id (str (UUID/nameUUIDFromBytes (.getBytes (str blob-dir "/" file-path) "UTF-8")))
          vector   (p/embed-document embedding content)]
      (p/upsert! vector-store point-id vector
                 {:fact_eid  fact-eid
                  :blob_dir  blob-dir
                  :file_path file-path}))
    (catch Exception e
      (log/warn e "Failed to vectorize file" blob-dir "/" file-path))))

(defn reindex-all!
  "Re-embeds all nodes into the vector store. Also re-embeds compact.md for blob nodes.
   Synchronous, sequential. Returns {:reindexed N :files-reindexed K}.
   `fact-store`   — implements FactStore protocol
   `vector-store` — implements VectorStore protocol
   `embedding`    — implements EmbeddingProvider protocol
   `blob-path`    — filesystem base path for blobs"
  [fact-store vector-store embedding blob-path]
  (let [node-eids (p/all-nodes fact-store)]
    (reduce (fn [acc eid]
              (let [node (p/find-node fact-store eid)]
                (when (:node/content node)
                  (try
                    (let [vector (p/embed-document embedding (:node/content node))]
                      (p/upsert! vector-store eid vector {}))
                    (catch Exception e
                      (log/warn e "Failed to vectorize node" eid))))
                (let [acc' (update acc :reindexed inc)]
                  (if-let [blob-dir-short (:node/blob-dir node)]
                    (let [resolved     (or (blob-store/resolve-blob-dir blob-path blob-dir-short)
                                           blob-dir-short)
                          compact-file (io/file blob-path resolved "compact.md")]
                      (if (.exists compact-file)
                        (do (embed-file! vector-store embedding eid blob-dir-short "compact.md"
                                         (slurp compact-file))
                            (update acc' :files-reindexed inc))
                        acc'))
                    acc'))))
            {:reindexed 0 :files-reindexed 0}
            node-eids)))
