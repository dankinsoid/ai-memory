;; Node CRUD — ACTIVE (used by write pipeline).
;; See ADR-009 for retrieval architecture.

(ns ai-memory.graph.node
  (:require [datomic.api :as d]
            [ai-memory.db.core :as db]
            [ai-memory.blob.store :as blob-store]
            [ai-memory.embedding.core :as embedding]
            [ai-memory.embedding.vector-store :as vs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util Date UUID]))

(defn- now [] (Date.))

(defn- embed-async!
  "Embeds content in Qdrant using entity ID as point ID. Logs warning on failure, never throws."
  [cfg eid content]
  (try
    (let [vector (embedding/embed-document (:openai-api-key cfg) content)]
      (vs/upsert-point! (:qdrant-url cfg) eid vector {}))
    (catch Exception e
      (log/warn e "Failed to vectorize node" eid))))

(defn embed-file!
  "Vectorizes a blob file into Qdrant under a stable UUID point ID derived from blob-dir+file-path.
   payload contains fact-eid, blob-dir and file-path for reverse lookup.
   Logs warning on failure, never throws."
  [cfg fact-eid blob-dir file-path content]
  (try
    (let [point-id (str (UUID/nameUUIDFromBytes (.getBytes (str blob-dir "/" file-path) "UTF-8")))
          vector   (embedding/embed-document (:embedding-url cfg) content)]
      (vs/upsert-point! (:qdrant-url cfg) point-id vector
                        {:fact_eid  fact-eid
                         :blob_dir  blob-dir
                         :file_path file-path}))
    (catch Exception e
      (log/warn e "Failed to vectorize file" blob-dir "/" file-path))))

(defn create-node
  "Creates a memory node in Datomic and embeds content in Qdrant.
   Returns {:tx-result ... :node-eid <entity-id>}.
   `cfg` — {:openai-api-key, :qdrant-url}.
   `tags` — vec of tag name strings.
   Optional keys: :blob-dir (string), :sources (set of strings)."
  [conn cfg {:keys [content tags blob-dir sources session-id]}]
  (let [tick      (db/next-tick (d/db conn))
        tempid    (d/tempid :db.part/user)
        ts        (now)
        base-tx   (cond-> {:db/id           tempid
                            :node/content    content
                            :node/weight     0.0
                            :node/cycle      tick
                            :node/created-at ts
                            :node/updated-at ts}
                    (seq tags)    (assoc :node/tags tags)
                    blob-dir      (assoc :node/blob-dir blob-dir)
                    (seq sources) (assoc :node/sources sources)
                    session-id    (assoc :node/session-id session-id))
        tx-result (db/transact! conn [base-tx] tick)
        eid       (d/resolve-tempid (:db-after tx-result) (:tempids tx-result) tempid)]
    (embed-async! cfg eid content)
    {:tx-result tx-result
     :node-eid  eid}))

(def node-pull-spec
  [:db/id :node/content :node/weight :node/cycle :node/sources
   :node/blob-dir :node/updated-at :node/tags])

(defn find-by-id [db eid]
  (d/pull db '[*] eid))

(def ^:private min-score 0.2)
(def ^:private min-file-score 0.15)

(defn search
  "Finds nodes semantically similar to `text`.
   Searches both fact vectors (long IDs) and blob file vectors (UUID IDs).
   Deduplicates by entity: if same fact found via both, keeps max score.
   Returns nodes from Datomic enriched with :search/score, sorted by score desc."
  [db cfg text top-k]
  (let [qvec (embedding/embed-query (:openai-api-key cfg) text)
        hits (vs/search (:qdrant-url cfg) qvec top-k)]
    (->> hits
         (mapv (fn [{:keys [id score payload]}]
                 (let [file-hit? (string? id)
                       threshold (if file-hit? min-file-score min-score)]
                   (when (>= score threshold)
                     (let [eid  (if file-hit?
                                  (:fact_eid payload)
                                  (long id))
                           node (when eid (d/pull db node-pull-spec eid))]
                       (when (:node/content node)
                         (assoc node :search/score score)))))))
         (filterv some?)
         ;; Deduplicate: same fact may appear from fact vector + file vector; keep max score
         (group-by :db/id)
         vals
         (mapv #(apply max-key :search/score %)))))

(defn find-duplicate
  "Returns the best semantic match if score >= threshold, or nil."
  [db cfg content threshold]
  (let [results (search db cfg content 1)]
    (when (and (seq results)
               (>= (:search/score (first results)) threshold))
      (first results))))

(defn find-entity-by-content
  "Finds an entity node (tagged entity) by exact content match. Returns entity map or nil."
  [db content]
  (let [eid (d/q '[:find ?e .
                   :in $ ?content
                   :where
                   [?e :node/content ?content]
                   [?e :node/tags "entity"]]
                 db content)]
    (when eid
      (d/pull db '[*] eid))))

(defn update-content!
  "Updates node content and updated-at. Re-embeds in Qdrant."
  [conn cfg eid new-content]
  (db/transact! conn
     [[:db/add eid :node/content    new-content]
      [:db/add eid :node/updated-at (now)]])
  (embed-async! cfg eid new-content))

(defn- apply-score
  "Asymptotic approach to 1.0 for positive score; linear decrease for negative.
   base ∈ [0.0, 1.0). Result never reaches 1.0 via regular reinforce."
  [current score factor]
  (if (pos? score)
    (+ current (* score factor (- 1.0 current)))
    (max 0.0 (+ current (* score factor)))))

(defn reinforce-node
  "Reinforces existing node: bumps weight, cycle, updated-at. Re-embeds in Qdrant."
  [conn cfg eid new-content score factor]
  (let [db   (d/db conn)
        tick (db/next-tick db)
        node (d/pull db [:node/weight] eid)
        current-weight (or (:node/weight node) 0.0)
        new-weight     (apply-score current-weight score factor)]
    (db/transact! conn
       [[:db/add eid :node/content    new-content]
        [:db/add eid :node/weight     new-weight]
        [:db/add eid :node/cycle      tick]
        [:db/add eid :node/updated-at (now)]]
       tick)
    (embed-async! cfg eid new-content)))

(defn reinforce-weight
  "Adjusts node base weight using score. Positive score: asymptotic toward 1.0.
   Negative score: linear decrease (floor 0.0). Updates cycle. No content change."
  [conn eid score factor]
  (let [db (d/db conn)
        tick (db/next-tick db)
        current-weight (or (:node/weight (d/pull db [:node/weight] eid)) 0.0)
        new-weight (apply-score current-weight score factor)]
    (db/transact! conn
       [[:db/add eid :node/weight     new-weight]
        [:db/add eid :node/cycle      tick]
        [:db/add eid :node/updated-at (now)]]
       tick)))

(defn promote-eternal!
  "Sets node weight to 1.0, making it eternal (never decays).
   Cannot be achieved via regular reinforce — admin-only operation."
  [conn eid]
  (let [db   (d/db conn)
        tick (db/next-tick db)]
    (db/transact! conn
       [[:db/add eid :node/weight     1.0]
        [:db/add eid :node/cycle      tick]
        [:db/add eid :node/updated-at (now)]]
       tick)))

(defn find-recent
  "Returns entity IDs with cycle >= min-tick."
  [db min-tick]
  (d/q '[:find [?e ...]
         :in $ ?min-tick
         :where
         [?e :node/cycle ?c]
         [(>= ?c ?min-tick)]
         [?e :node/content]]
       db min-tick))

(defn update-tags
  "Adds tags (strings) to an existing node (additive — cardinality/many). Updates updated-at."
  [conn eid tags]
  (when (seq tags)
    (db/transact! conn
       [{:db/id           eid
         :node/tags       (vec tags)
         :node/updated-at (now)}])))

(defn replace-tags!
  "Replaces all tags on a node with new-tag-names (seq of strings). Updates updated-at."
  [conn eid new-tag-names]
  (let [db            (d/db conn)
        current-names (set (d/q '[:find [?name ...]
                                  :in $ ?eid
                                  :where [?eid :node/tags ?name]]
                                db eid))
        new-names-set (set (map str/trim new-tag-names))
        to-add        (remove current-names new-names-set)
        to-remove     (remove new-names-set current-names)
        retract-txs   (mapv (fn [name] [:db/retract eid :node/tags name]) to-remove)
        add-txs       (mapv (fn [name] [:db/add eid :node/tags name]) to-add)]
    (db/transact! conn
      (conj (into retract-txs add-txs)
            [:db/add eid :node/updated-at (now)]))))

(defn set-weight!
  "Directly sets node base weight, clamped to [0.0, 1.0]. Updates updated-at."
  [conn eid weight]
  (let [w (max 0.0 (min 1.0 (double weight)))]
    (db/transact! conn
      [[:db/add eid :node/weight     w]
       [:db/add eid :node/updated-at (now)]])))

(defn reindex-all!
  "Re-embeds all nodes into Qdrant. Also re-embeds compact.md for blob nodes.
   Synchronous, sequential. Returns {:reindexed N :files-reindexed K}."
  [db cfg]
  (let [nodes (d/q '[:find [(pull ?e [:db/id :node/content :node/blob-dir]) ...]
                     :where [?e :node/content]]
                   db)
        base  (:blob-path cfg)]
    (reduce (fn [acc node]
              (do
                (embed-async! cfg (:db/id node) (:node/content node))
                (let [acc' (update acc :reindexed inc)]
                    (if-let [blob-dir-short (:node/blob-dir node)]
                      (let [resolved     (or (blob-store/resolve-blob-dir base blob-dir-short)
                                             blob-dir-short)
                            compact-file (io/file base resolved "compact.md")]
                        (if (.exists compact-file)
                          (do (embed-file! cfg (:db/id node) blob-dir-short "compact.md"
                                           (slurp compact-file))
                              (update acc' :files-reindexed inc))
                          acc'))
                      acc'))))
            {:reindexed 0 :files-reindexed 0}
            nodes)))

(defn find-blobs
  "Finds blob nodes (have :node/blob-dir) sorted by created-at desc."
  [db {:keys [limit] :or {limit 20}}]
  (let [pull-expr [:db/id :node/content
                   :node/created-at :node/updated-at :node/blob-dir :node/tags]
        results   (d/q '[:find [(pull ?n pull-expr) ...]
                         :in $ pull-expr
                         :where
                         [?n :node/blob-dir _]]
                       db pull-expr)]
    (->> results
         (sort-by :node/created-at #(compare %2 %1))
         (take limit)
         vec)))
