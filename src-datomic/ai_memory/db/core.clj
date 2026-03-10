(ns ai-memory.db.core
  (:require [datomic.api :as d]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai-memory.schema :as schema]))

(defn connect [uri]
  (d/create-database uri)
  (d/connect uri))

(defn load-schema []
  (-> (io/resource "schema.edn")
      slurp
      edn/read-string))

(defn ensure-schema [conn]
  @(d/transact conn (load-schema))
  ;; Tick singleton (must be separate tx — attribute must exist first)
  (when-not (d/entid (d/db conn) :tick/singleton)
    @(d/transact conn [{:db/ident :tick/singleton :tick/value 0}]))
  ;; Seed aspect tags (tier 2) — idempotent via :tag/name unique identity
  @(d/transact conn
    (mapv (fn [name] {:tag/name name :tag/tier :aspect :tag/node-count 0})
          schema/aspect-tags)))

(defn db [conn]
  (d/db conn))

(defn current-tick [db]
  (or (:tick/value (d/pull db [:tick/value] :tick/singleton)) 0))

(defn next-tick
  "Returns current-tick + 1 without writing. Use with transact!."
  [db]
  (inc (current-tick db)))

(defn transact!
  "Wraps d/transact with atomic tick increment.
   Auto-computes next tick unless provided.
   Returns deref'd tx-result."
  ([conn tx-data]
   (transact! conn tx-data (next-tick (d/db conn))))
  ([conn tx-data new-tick]
   @(d/transact conn (conj (vec tx-data)
                            {:db/id :tick/singleton :tick/value new-tick}))))

(defn recompute-tag-counts!
  "Recomputes all :tag/node-count from :node/tags. Upserts tag entities.
   Called at startup. Idempotent."
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
