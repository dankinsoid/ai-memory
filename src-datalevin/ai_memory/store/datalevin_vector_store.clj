;; @ai-generated(guided)
(ns ai-memory.store.datalevin-vector-store
  "VectorStore backed by Datalevin's built-in HNSW vector index.
   Stores vector points as dedicated entities (:vp/* attributes) in the
   same Datalevin DB as facts — no external vector DB needed.

   Each point is keyed by :vp/point-id = \"<collection>:<id>\" (unique identity).
   Embedding stored as :vp/embedding (:db.type/vec, cosine metric).
   Payload serialized as EDN string in :vp/payload.

   Search uses Datalevin's `vec-neighbors` Datalog function, which performs
   HNSW approximate nearest-neighbor search and returns cosine distances.
   We oversample (5x top-k) to account for collection filtering, then
   convert distance → similarity score (1 - distance)."
  (:require [datalevin.core :as d]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [ai-memory.store.protocols :as p]))

(defn- point-id
  "Composite unique key: \"collection:id\".
   Encodes collection into the identity so different collections
   can have overlapping raw IDs (e.g. EID 42 in nodes vs tag \"42\")."
  [collection id]
  (str collection ":" id))

(defn- raw-id
  "Extracts the original ID from a composite point-id string.
   Returns long if parseable, string otherwise."
  [collection composite]
  (let [prefix (str collection ":")
        raw    (subs composite (count prefix))]
    (or (parse-long raw) raw)))

(defn- search-vectors
  "Runs HNSW search via Datalevin's vec-neighbors, filtered by collection.
   `search-k` — how many HNSW results to fetch (before collection filter).
   Returns [{:id raw-id :score double :payload map} ...] sorted by score desc."
  [db collection query-vector search-k]
  (let [;; Build query with dynamic search-k in vec-neighbors opts.
        ;; vec-neighbors returns [?e ?a ?v ?dist] tuples when :display :refs+dists.
        results (d/q (list :find '?pid '?payload-str '?dist
                           :in '$ '?query '?coll
                           :where
                           [(list 'vec-neighbors '$ :vp/embedding '?query
                                  {:top search-k :display :refs+dists})
                            '[[?e _ _ ?dist]]]
                           '[?e :vp/collection ?coll]
                           '[?e :vp/point-id ?pid]
                           '[?e :vp/payload ?payload-str])
                     db
                     (float-array query-vector)
                     collection)]
    (->> results
         (mapv (fn [[pid payload-str dist]]
                 {:id      (raw-id collection pid)
                  :score   (- 1.0 (double dist))
                  :payload (edn/read-string payload-str)}))
         (sort-by :score >))))

(defrecord DatalevinVectorStore [conn collection]
  p/VectorStore

  (ensure-store! [_ dim]
    ;; Vector dimensions set at DB connection time via :vector-opts.
    ;; Just log — no runtime action needed.
    (log/info "Datalevin vector store ready:" collection "dim=" dim))

  (upsert! [_ id vector payload]
    (d/transact! conn [{:vp/point-id   (point-id collection id)
                        :vp/collection  collection
                        :vp/embedding   (float-array vector)
                        :vp/payload     (pr-str payload)}]))

  (search [_ query-vector top-k _opts]
    ;; Oversample 5x because vec-neighbors searches ALL vectors globally,
    ;; then we post-filter by collection. With <2K total vectors this is fine.
    (let [search-k (max (* top-k 5) 50)]
      (into [] (take top-k) (search-vectors (d/db conn) collection query-vector search-k))))

  (delete! [_ id]
    (let [pid (point-id collection id)]
      (when-let [eid (d/entid (d/db conn) [:vp/point-id pid])]
        (d/transact! conn [[:db/retractEntity eid]]))))

  (delete-all! [_]
    (let [eids (d/q '[:find [?e ...]
                       :in $ ?coll
                       :where [?e :vp/collection ?coll]]
                    (d/db conn) collection)]
      (when (seq eids)
        (d/transact! conn (mapv (fn [eid] [:db/retractEntity eid]) eids)))
      (log/info "Datalevin vector store cleared:" collection
                "(" (count eids) "points)")))

  (store-info [_]
    (let [cnt (or (first (d/q '[:find [(count ?e)]
                                :in $ ?coll
                                :where [?e :vp/collection ?coll]]
                              (d/db conn) collection))
                  0)]
      {:reachable?   true
       :status       "datalevin-hnsw"
       :vector-count cnt
       :points-count cnt}))

  (scroll-all [_]
    (let [results (d/q '[:find ?pid ?payload-str ?embedding
                          :in $ ?coll
                          :where
                          [?e :vp/collection ?coll]
                          [?e :vp/point-id ?pid]
                          [?e :vp/payload ?payload-str]
                          [?e :vp/embedding ?embedding]]
                       (d/db conn) collection)]
      (mapv (fn [[pid payload-str embedding]]
              {:id      (raw-id collection pid)
               :vector  (vec embedding)
               :payload (edn/read-string payload-str)})
            results))))

(defn create
  "Creates a DatalevinVectorStore backed by an existing Datalevin connection.
   `conn` — Datalevin connection (must have been opened with :vector-opts).
   `collection` — logical name (e.g. \"nodes\", \"tags\")."
  [conn collection]
  (->DatalevinVectorStore conn collection))
