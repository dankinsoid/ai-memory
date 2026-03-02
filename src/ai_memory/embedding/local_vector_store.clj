(ns ai-memory.embedding.local-vector-store
  "Datalevin-backed vector store. Replaces Qdrant HTTP client.
   Fact vectors stored as :node/vector on node entities.
   File vectors stored as separate :file-vec/* entities."
  (:require [datalevin.core :as d]
            [clojure.tools.logging :as log]))

(def ^:private vector-dim 1536)
(def ^:private min-score 0.2)
(def ^:private min-file-score 0.15)

;; Cosine distance from usearch is in [0, 2] range for normalized vectors.
;; score = 1.0 - (dist / 2.0) maps distance to similarity [0, 1].
(defn- dist->score [dist]
  (- 1.0 (/ dist 2.0)))

(defn upsert-fact-vec!
  "Stores embedding vector on the node entity."
  [conn eid vector]
  (try
    (d/transact! conn [{:db/id eid :node/vector (float-array vector)}])
    (catch Exception e
      (log/warn e "Failed to upsert fact vector for" eid))))

(defn upsert-file-vec!
  "Creates/updates a file-vec entity for a blob file.
   Uses stable identity: blob-dir + file-path."
  [conn fact-eid blob-dir file-path vector]
  (try
    ;; Find existing file-vec entity for this path, if any
    (let [db     (d/db conn)
          existing (d/q '[:find ?e .
                          :in $ ?bd ?fp
                          :where
                          [?e :file-vec/blob-dir ?bd]
                          [?e :file-vec/file-path ?fp]]
                        db blob-dir file-path)
          tx-map (cond-> {:file-vec/blob-dir  blob-dir
                          :file-vec/file-path file-path
                          :file-vec/fact-ref  fact-eid
                          :file-vec/vector    (float-array vector)}
                   existing (assoc :db/id existing))]
      (d/transact! conn [tx-map]))
    (catch Exception e
      (log/warn e "Failed to upsert file vector" blob-dir "/" file-path))))

(defn search-facts
  "Returns top-k fact nodes by vector similarity.
   `db` — Datalevin db snapshot.
   Returns [{:eid <long> :score <float>}] filtered by min-score, sorted by score desc."
  [db query-vector top-k]
  (try
    (let [results (d/q '[:find ?e ?dist
                         :in $ ?q ?k
                         :where [(vec-neighbors $ :node/vector ?q {:top ?k :display :refs+dists})
                                 [[?e ?dist]]]]
                       db (float-array query-vector) top-k)]
      (->> results
           (map (fn [[eid dist]] {:eid eid :score (dist->score dist)}))
           (filter #(>= (:score %) min-score))
           (sort-by :score >)))
    (catch Exception e
      (log/warn e "Vector search (facts) failed")
      [])))

(defn search-files
  "Returns top-k file-vec entities by vector similarity.
   `db` — Datalevin db snapshot.
   Returns [{:fact-eid <long> :blob-dir <str> :file-path <str> :score <float>}]."
  [db query-vector top-k]
  (try
    (let [results (d/q '[:find ?e ?dist
                         :in $ ?q ?k
                         :where [(vec-neighbors $ :file-vec/vector ?q {:top ?k :display :refs+dists})
                                 [[?e ?dist]]]]
                       db (float-array query-vector) top-k)]
      (->> results
           (map (fn [[fv-eid dist]]
                  (let [fv (d/pull db [:file-vec/fact-ref :file-vec/blob-dir :file-vec/file-path] fv-eid)]
                    {:fact-eid  (get-in fv [:file-vec/fact-ref :db/id])
                     :blob-dir  (:file-vec/blob-dir fv)
                     :file-path (:file-vec/file-path fv)
                     :score     (dist->score dist)})))
           (filter #(>= (:score %) min-file-score))
           (sort-by :score >)))
    (catch Exception e
      (log/warn e "Vector search (files) failed")
      [])))

(defn delete-node-vecs!
  "Removes vector data for a node and its associated file-vec entities.
   Called before or during node deletion."
  [conn eid]
  (try
    (let [db      (d/db conn)
          fv-eids (d/q '[:find [?e ...]
                         :in $ ?fact
                         :where [?e :file-vec/fact-ref ?fact]]
                       db eid)
          tx-data (into [[:db/retract eid :node/vector]]
                        (map #(vector :db/retractEntity %) fv-eids))]
      (when (seq tx-data)
        (d/transact! conn tx-data)))
    (catch Exception e
      (log/warn e "Failed to delete node vectors for" eid))))

(defn delete-all-vecs!
  "Removes all vector data (fact vectors + file-vec entities).
   Used by reset-all!."
  [conn]
  (try
    (let [db      (d/db conn)
          fv-eids (d/q '[:find [?e ...] :where [?e :file-vec/blob-dir]] db)]
      (when (seq fv-eids)
        (d/transact! conn (mapv #(vector :db/retractEntity %) fv-eids))))
    ;; Fact vectors are retracted with the node entities by reset-all!
    (catch Exception e
      (log/warn e "Failed to delete all file-vec entities"))))

(defn vector-stats
  "Returns basic stats: fact-vector-count, file-vector-count."
  [conn]
  (let [db (d/db conn)]
    {:fact-vector-count (or (d/q '[:find (count ?e) . :where [?e :node/vector]] db) 0)
     :file-vector-count (or (d/q '[:find (count ?e) . :where [?e :file-vec/blob-dir]] db) 0)}))
