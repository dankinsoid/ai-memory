;; Node CRUD — ACTIVE (used by write pipeline).
;; See ADR-009 for retrieval architecture.

(ns ai-memory.graph.node
  (:require [datalevin.core :as d]
            [ai-memory.db.core :as db]
            [ai-memory.tag.core :as tag]
            [ai-memory.embedding.core :as embedding]
            [ai-memory.embedding.local-vector-store :as lvs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util Date]))

(defn- now [] (Date.))

(defn- has-entity-tag?
  "Checks tag-refs (lookup refs or pulled maps) for entity tag."
  [tag-refs]
  (some (fn [ref]
          (cond
            (vector? ref) (= (second ref) "entity")
            (map? ref)    (= (:tag/name ref) "entity")
            :else         false))
        tag-refs))

(defn- skip-embedding?
  "Entity nodes (have entity tag) skip vectorization."
  [{:keys [tag-refs]}]
  (has-entity-tag? tag-refs))

(defn- embed-async!
  "Embeds content into Datalevin vector index using entity ID. Logs warning on failure, never throws."
  [conn cfg eid content]
  (try
    (let [vector (embedding/embed-document (:openai-api-key cfg) content)]
      (lvs/upsert-fact-vec! conn eid vector))
    (catch Exception e
      (log/warn e "Failed to vectorize node" eid))))

(defn embed-file!
  "Vectorizes a blob file into Datalevin under a file-vec entity.
   Payload contains fact-eid, blob-dir and file-path for reverse lookup.
   Logs warning on failure, never throws."
  [conn cfg fact-eid blob-dir file-path content]
  (try
    (let [vector (embedding/embed-document (:openai-api-key cfg) content)]
      (lvs/upsert-file-vec! conn fact-eid blob-dir file-path vector))
    (catch Exception e
      (log/warn e "Failed to vectorize file" blob-dir "/" file-path))))

(defn- inc-tag-counts!
  "Client-side tag count increments. delta = 1 (add) or -1 (remove)."
  [conn tag-refs delta]
  (when (seq tag-refs)
    (let [db (d/db conn)]
      (doseq [ref tag-refs]
        (let [tag-name (second ref)
              current  (or (:tag/node-count (d/pull db [:tag/node-count] [:tag/name tag-name])) 0)]
          (d/transact! conn [[:db/add [:tag/name tag-name] :tag/node-count (+ current delta)]]))))))

(defn create-node
  "Creates a memory node in Datalevin. Embeds content unless entity.
   Returns {:tx-result ... :node-eid <entity-id>}.
   `cfg` — {:openai-api-key}.
   `tag-refs` — vec of lookup refs like [[:tag/name \"clj\"]].
   Optional keys: :blob-dir (string), :sources (set of strings)."
  [conn cfg {:keys [content tag-refs blob-dir sources session-id]}]
  (let [tick     (db/next-tick (d/db conn))
        tempid   "new-node"
        ts       (now)
        base-tx  (cond-> {:db/id           tempid
                           :node/content    content
                           :node/weight     0.0
                           :node/cycle      tick
                           :node/created-at ts
                           :node/updated-at ts}
                   (seq tag-refs) (assoc :node/tag-refs tag-refs)
                   blob-dir       (assoc :node/blob-dir blob-dir)
                   (seq sources)  (assoc :node/sources sources)
                   session-id     (assoc :node/session-id session-id))
        tx-result (db/transact! conn [base-tx] tick)
        eid       (get-in tx-result [:tempids tempid])]
    (inc-tag-counts! conn tag-refs 1)
    (when-not (skip-embedding? {:tag-refs tag-refs})
      (embed-async! conn cfg eid content))
    {:tx-result tx-result
     :node-eid  eid}))

(defn find-by-id [db eid]
  (d/pull db '[*] eid))

(defn search
  "Finds nodes semantically similar to `text`.
   `db` — Datalevin db snapshot.
   Searches both fact vectors and blob file vectors.
   Deduplicates by entity: if same fact found via both, keeps max score.
   Returns nodes enriched with :search/score, sorted by score desc."
  [db cfg text top-k]
  (let [qvec      (embedding/embed-query (:openai-api-key cfg) text)
        fact-hits (lvs/search-facts db qvec top-k)
        file-hits (lvs/search-files db qvec top-k)
        all-hits  (concat
                    (map (fn [{:keys [eid score]}]
                           (let [node (d/pull db '[*] eid)]
                             (when (:node/content node)
                               (assoc node :search/score score))))
                         fact-hits)
                    (map (fn [{:keys [fact-eid score]}]
                           (let [node (when fact-eid (d/pull db '[*] fact-eid))]
                             (when (:node/content node)
                               (assoc node :search/score score))))
                         file-hits))]
    (->> all-hits
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
                   [?e :node/tag-refs ?tag]
                   [?tag :tag/name "entity"]]
                 db content)]
    (when eid
      (d/pull db '[*] eid))))

(defn update-content!
  "Updates node content and updated-at. Re-embeds unless entity."
  [conn cfg eid new-content]
  (let [db   (d/db conn)
        node (d/pull db [{:node/tag-refs [:tag/name]}] eid)]
    (db/transact! conn
       [[:db/add eid :node/content    new-content]
        [:db/add eid :node/updated-at (now)]])
    (when-not (skip-embedding? {:tag-refs (:node/tag-refs node)})
      (embed-async! conn cfg eid new-content))))

(defn- apply-score
  "Asymptotic approach to 1.0 for positive score; linear decrease for negative.
   base ∈ [0.0, 1.0). Result never reaches 1.0 via regular reinforce."
  [current score factor]
  (if (pos? score)
    (+ current (* score factor (- 1.0 current)))
    (max 0.0 (+ current (* score factor)))))

(defn reinforce-node
  "Reinforces existing node: bumps weight, cycle, updated-at. Re-embeds unless entity."
  [conn cfg eid new-content score factor]
  (let [db   (d/db conn)
        tick (db/next-tick db)
        node (d/pull db [:node/weight :node/blob-dir {:node/tag-refs [:tag/name]}] eid)
        current-weight (or (:node/weight node) 0.0)
        new-weight     (apply-score current-weight score factor)]
    (db/transact! conn
       [[:db/add eid :node/content    new-content]
        [:db/add eid :node/weight     new-weight]
        [:db/add eid :node/cycle      tick]
        [:db/add eid :node/updated-at (now)]]
       tick)
    (when-not (skip-embedding? {:tag-refs (:node/tag-refs node)})
      (embed-async! conn cfg eid new-content))))

(defn reinforce-weight
  "Adjusts node base weight using score. Positive score: asymptotic toward 1.0.
   Negative score: linear decrease (floor 0.0). Updates cycle. No content change."
  [conn eid score factor]
  (let [db   (d/db conn)
        tick (db/next-tick db)
        current-weight (or (:node/weight (d/pull db [:node/weight] eid)) 0.0)
        new-weight     (apply-score current-weight score factor)]
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

(defn update-tag-refs
  "Adds tag refs to an existing node (additive — cardinality/many).
   Increments tag counts only for tags not already on the node. Updates updated-at."
  [conn eid tag-refs]
  (when (seq tag-refs)
    (let [db       (d/db conn)
          existing (set (d/q '[:find [?name ...]
                               :in $ ?eid
                               :where
                               [?eid :node/tag-refs ?t]
                               [?t :tag/name ?name]]
                             db eid))
          new-refs (remove #(existing (second %)) tag-refs)]
      (db/transact! conn
         [{:db/id           eid
           :node/tag-refs   tag-refs
           :node/updated-at (now)}])
      (inc-tag-counts! conn new-refs 1))))

(defn replace-tags!
  "Replaces all tags on a node with new-tag-names (seq of strings).
   Decrements counts for removed tags, increments for added. Updates updated-at."
  [conn eid new-tag-names]
  (let [db            (d/db conn)
        current-names (set (d/q '[:find [?name ...]
                                  :in $ ?eid
                                  :where
                                  [?eid :node/tag-refs ?t]
                                  [?t :tag/name ?name]]
                                db eid))
        new-names-set (set (map str/trim new-tag-names))
        to-add        (remove current-names new-names-set)
        to-remove     (remove new-names-set current-names)]
    (doseq [n to-add]
      (tag/ensure-tag! conn n))
    (let [retract-txs (mapv (fn [n] [:db/retract eid :node/tag-refs [:tag/name n]]) to-remove)
          add-txs     (mapv (fn [n] [:db/add eid :node/tag-refs [:tag/name n]]) to-add)]
      (db/transact! conn
        (into (concat retract-txs add-txs)
              [[:db/add eid :node/updated-at (now)]])))
    (inc-tag-counts! conn (mapv #(vector :tag/name %) to-add) 1)
    (inc-tag-counts! conn (mapv #(vector :tag/name %) to-remove) -1)))

(defn set-weight!
  "Directly sets node base weight, clamped to [0.0, 1.0]. Updates updated-at."
  [conn eid weight]
  (let [w (max 0.0 (min 1.0 (double weight)))]
    (db/transact! conn
      [[:db/add eid :node/weight     w]
       [:db/add eid :node/updated-at (now)]])))

(defn reindex-all!
  "Re-embeds all non-entity nodes into Datalevin. Also re-embeds compact.md for blob nodes.
   Synchronous, sequential. Returns {:reindexed N :skipped M :files-reindexed K}."
  [conn cfg]
  (let [db    (d/db conn)
        nodes (d/q '[:find [(pull ?e [:db/id :node/content :node/blob-dir
                                      {:node/tag-refs [:tag/name]}]) ...]
                     :where [?e :node/content]]
                   db)
        base  (:blob-path cfg)]
    (reduce (fn [acc node]
              (if (skip-embedding? {:tag-refs (:node/tag-refs node)})
                (update acc :skipped inc)
                (do
                  (embed-async! conn cfg (:db/id node) (:node/content node))
                  (let [acc' (update acc :reindexed inc)]
                    (if-let [blob-dir (:node/blob-dir node)]
                      (let [compact-file (io/file base blob-dir "compact.md")]
                        (if (.exists compact-file)
                          (do (embed-file! conn cfg (:db/id node) blob-dir "compact.md"
                                           (slurp compact-file))
                              (update acc' :files-reindexed inc))
                          acc'))
                      acc')))))
            {:reindexed 0 :skipped 0 :files-reindexed 0}
            nodes)))

(defn find-blobs
  "Finds blob nodes (have :node/blob-dir) sorted by created-at desc."
  [db {:keys [limit] :or {limit 20}}]
  (let [pull-expr [:db/id :node/content
                   :node/created-at :node/updated-at :node/blob-dir
                   {:node/tag-refs [:tag/name]}]
        results   (d/q '[:find [(pull ?n pull-expr) ...]
                         :in $ pull-expr
                         :where
                         [?n :node/blob-dir _]]
                       db pull-expr)]
    (->> results
         (sort-by :node/created-at #(compare %2 %1))
         (take limit)
         vec)))
