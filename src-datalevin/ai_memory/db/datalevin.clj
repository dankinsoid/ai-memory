;; @ai-generated(guided)
(ns ai-memory.db.datalevin
  "Datalevin connection management, schema, and tick counter.
   Equivalent of ai-memory.db.core for the Datalevin backend.
   Uses Datalevin's embedded Datalog DB — no external server needed."
  (:require [datalevin.core :as d]
            [clojure.tools.logging :as log]
            [ai-memory.schema :as schema]))

;; Default embedding dimension (text-embedding-3-small = 1536).
;; Passed at connection time via vector-opts; cannot change after DB creation.
(def ^:const default-embedding-dim 1536)

;; --- Schema ---
;; Datalevin uses a map-based schema: {attr {:db/valueType ... :db/cardinality ...}}
;; Translated from Datomic's schema.edn vector format.

(def schema
  "Datalevin schema — translated from Datomic schema.edn.
   Key differences from Datomic:
   - Map format (attr -> opts) instead of vector of maps
   - No :db/doc (documentation only, not stored)
   - :db/fulltext supported natively
   - :db/ident is built-in, not declared in schema"
  {;; --- Tags ---
   :tag/name       {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one
                    :db/unique      :db.unique/identity}
   :tag/node-count {:db/valueType   :db.type/long
                    :db/cardinality :db.cardinality/one}
   :tag/tier       {:db/valueType   :db.type/keyword
                    :db/cardinality :db.cardinality/one}

   ;; --- Nodes ---
   :node/content    {:db/valueType   :db.type/string
                     :db/cardinality :db.cardinality/one
                     :db/fulltext    true}
   :node/tags       {:db/valueType   :db.type/string
                     :db/cardinality :db.cardinality/many}
   :node/weight     {:db/valueType   :db.type/double
                     :db/cardinality :db.cardinality/one}
   :node/cycle      {:db/valueType   :db.type/long
                     :db/cardinality :db.cardinality/one}
   :node/created-at {:db/valueType   :db.type/instant
                     :db/cardinality :db.cardinality/one}
   :node/updated-at {:db/valueType   :db.type/instant
                     :db/cardinality :db.cardinality/one}
   :node/blob-dir   {:db/valueType   :db.type/string
                     :db/cardinality :db.cardinality/one}
   :node/sources    {:db/valueType   :db.type/string
                     :db/cardinality :db.cardinality/many}
   :node/session-id {:db/valueType   :db.type/string
                     :db/cardinality :db.cardinality/one}

   ;; --- Edges ---
   :edge/id     {:db/valueType   :db.type/uuid
                 :db/cardinality :db.cardinality/one
                 :db/unique      :db.unique/identity}
   :edge/from   {:db/valueType   :db.type/ref
                 :db/cardinality :db.cardinality/one}
   :edge/to     {:db/valueType   :db.type/ref
                 :db/cardinality :db.cardinality/one}
   :edge/weight {:db/valueType   :db.type/double
                 :db/cardinality :db.cardinality/one}
   :edge/cycle  {:db/valueType   :db.type/long
                 :db/cardinality :db.cardinality/one}
   :edge/type   {:db/valueType   :db.type/keyword
                 :db/cardinality :db.cardinality/one}

   ;; --- Global tick ---
   :tick/value  {:db/valueType   :db.type/long
                 :db/cardinality :db.cardinality/one}

   ;; --- Vector points (VectorStore entities) ---
   ;; Dedicated entities for vector search, keyed by "collection:id".
   ;; Keeps VectorStore decoupled from FactStore — blob file vectors
   ;; have UUID string IDs that don't map to node entities.
   :vp/point-id   {:db/valueType   :db.type/string
                    :db/unique      :db.unique/identity}
   :vp/collection  {:db/valueType   :db.type/string}
   :vp/embedding   {:db/valueType   :db.type/vec}
   :vp/payload     {:db/valueType   :db.type/string}})


;; --- Connection ---

(defn connect
  "Opens (or creates) a Datalevin database at `db-path`.
   `db-path` — filesystem directory for the DB, or nil for in-memory.
   `opts` — optional map, supports :embedding-dim (default 1536).
   Returns a Datalevin connection."
  ([db-path] (connect db-path {}))
  ([db-path {:keys [embedding-dim]}]
   (let [dim  (or embedding-dim default-embedding-dim)
         opts {:vector-opts {:dimensions dim :metric-type :cosine}}
         conn (d/get-conn db-path schema opts)]
     (log/info "Datalevin connected:" (or db-path "<in-memory>")
               "vector-dim:" dim)
     conn)))

(defn close
  "Closes Datalevin connection, releasing resources."
  [conn]
  (d/close conn))

;; --- Tick counter ---
;; Uses a well-known entity with :db/ident :tick/singleton.
;; Datalevin supports :db/ident for named entity lookup.

(defn- tick-eid
  "Returns the entity ID of the tick singleton, or nil if not yet created."
  [db]
  (d/entid db :tick/singleton))

(defn current-tick
  "Returns the current global tick value, or 0 if not initialized."
  [db]
  (if-let [eid (tick-eid db)]
    (or (:tick/value (d/pull db [:tick/value] eid)) 0)
    0))

(defn next-tick
  "Returns current-tick + 1 without writing. Use with transact!."
  [db]
  (inc (current-tick db)))

;; --- Transactions ---

(defn transact!
  "Wraps d/transact! with atomic tick increment.
   Auto-computes next tick unless provided.
   Returns Datalevin tx-report {:tx-data [...] :tempids {...} :db-after ...}."
  ([conn tx-data]
   (transact! conn tx-data (next-tick (d/db conn))))
  ([conn tx-data new-tick]
   (let [tick-eid (tick-eid (d/db conn))
         tick-tx  (if tick-eid
                    {:db/id tick-eid :tick/value new-tick}
                    {:db/ident :tick/singleton :tick/value new-tick})]
     (d/transact! conn (conj (vec tx-data) tick-tx)))))

;; --- Schema setup ---

(defn ensure-schema
  "Seeds the tick singleton and aspect tags. Idempotent.
   Schema itself is applied at connection time via `connect`."
  [conn]
  ;; Tick singleton — create if absent
  (when-not (tick-eid (d/db conn))
    (d/transact! conn [{:db/ident :tick/singleton :tick/value 0}]))
  ;; Seed aspect tags — idempotent via :tag/name unique identity
  (d/transact! conn
    (mapv (fn [name] {:tag/name name :tag/tier :aspect :tag/node-count 0})
          schema/aspect-tags)))

;; --- Tag count recomputation ---

(defn recompute-tag-counts!
  "Recomputes all :tag/node-count from :node/tags. Called at startup. Idempotent."
  [conn]
  (let [db        (d/db conn)
        actual    (into {} (d/q '[:find ?name (count ?n)
                                  :where [?n :node/tags ?name]]
                                db))
        all-names (distinct (concat (keys actual)
                                    (d/q '[:find [?name ...] :where [?t :tag/name ?name]] db)))
        tx-data   (mapv (fn [name]
                          {:tag/name name :tag/node-count (get actual name 0)})
                        all-names)]
    (when (seq tx-data)
      (transact! conn tx-data))))
