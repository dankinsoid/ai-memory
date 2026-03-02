(ns ai-memory.db.core
  (:require [datalevin.core :as d]))

;; Schema in Datalevin keyword-map format.
;; Vector attributes configured via :vector-opts at connect time.
(def ^:private schema
  {:tag/name        {:db/valueType :db.type/string  :db/cardinality :db.cardinality/one
                     :db/unique :db.unique/identity}
   :tag/node-count  {:db/valueType :db.type/long    :db/cardinality :db.cardinality/one}
   :tag/tier        {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}

   :node/content    {:db/valueType :db.type/string  :db/cardinality :db.cardinality/one
                     :db/fulltext true}
   :node/tag-refs   {:db/valueType :db.type/ref     :db/cardinality :db.cardinality/many}
   :node/weight     {:db/valueType :db.type/double  :db/cardinality :db.cardinality/one}
   :node/cycle      {:db/valueType :db.type/long    :db/cardinality :db.cardinality/one}
   :node/created-at {:db/valueType :db.type/instant :db/cardinality :db.cardinality/one
                     :db/index true}
   :node/updated-at {:db/valueType :db.type/instant :db/cardinality :db.cardinality/one
                     :db/index true}
   :node/blob-dir   {:db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   :node/sources    {:db/valueType :db.type/string  :db/cardinality :db.cardinality/many}
   :node/session-id {:db/valueType :db.type/string  :db/cardinality :db.cardinality/one
                     :db/index true}
   :node/vector     {:db/valueType :db.type/vec}

   :edge/id         {:db/valueType :db.type/uuid    :db/cardinality :db.cardinality/one
                     :db/unique :db.unique/identity}
   :edge/from       {:db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   :edge/to         {:db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   :edge/weight     {:db/valueType :db.type/double  :db/cardinality :db.cardinality/one}
   :edge/cycle      {:db/valueType :db.type/long    :db/cardinality :db.cardinality/one}
   :edge/type       {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}

   ;; Global monotonic tick — :tick/id is the lookup key
   :tick/id         {:db/valueType :db.type/string  :db/cardinality :db.cardinality/one
                     :db/unique :db.unique/identity}
   :tick/value      {:db/valueType :db.type/long    :db/cardinality :db.cardinality/one}

   ;; File-level vectors for blob files (separate from node vectors)
   :file-vec/fact-ref  {:db/valueType :db.type/ref     :db/cardinality :db.cardinality/one}
   :file-vec/blob-dir  {:db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   :file-vec/file-path {:db/valueType :db.type/string  :db/cardinality :db.cardinality/one}
   :file-vec/vector    {:db/valueType :db.type/vec}})

(def aspect-tags
  "Tier 2 (aspect) tags — fixed vocabulary for knowledge categorization."
  ["architecture" "pattern" "idea" "decision" "preference"
   "debugging" "pitfall" "api" "data-model" "tooling"
   "workflow" "performance" "comparison" "testing" "insight"])

(defn connect [db-path]
  (d/get-conn db-path schema {:vector-opts {:dimensions 1536 :metric-type :cosine}}))

(def ^:private tick-lookup [:tick/id "singleton"])

(defn ensure-schema [conn]
  ;; Tick singleton — idempotent via :tick/id unique identity
  (d/transact! conn [{:tick/id "singleton" :tick/value 0}])
  ;; Seed aspect tags — idempotent via :tag/name unique identity
  (d/transact! conn
    (mapv (fn [n] {:tag/name n :tag/tier :aspect :tag/node-count 0})
          aspect-tags)))

(defn db [conn]
  (d/db conn))

(defn current-tick [db]
  (or (:tick/value (d/pull db [:tick/value] tick-lookup)) 0))

(defn next-tick
  "Returns current-tick + 1 without writing. Use with transact!."
  [db]
  (inc (current-tick db)))

(defn transact!
  "Wraps d/transact! with atomic tick increment.
   Auto-computes next tick unless provided.
   Returns tx-result map."
  ([conn tx-data]
   (transact! conn tx-data (next-tick (d/db conn))))
  ([conn tx-data new-tick]
   (d/transact! conn (conj (vec tx-data)
                            {:db/id tick-lookup :tick/value new-tick}))))
