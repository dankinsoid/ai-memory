(ns ai-memory.db.core
  (:require [datomic.api :as d]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn connect [uri]
  (d/create-database uri)
  (d/connect uri))

(defn load-schema []
  (-> (io/resource "schema.edn")
      slurp
      edn/read-string))

(defn- load-seed-tags []
  (-> (io/resource "seed-tags.edn")
      slurp
      edn/read-string))

(defn ensure-schema [conn]
  @(d/transact conn (load-schema))
  ;; Tick singleton (must be separate tx — attribute must exist first)
  (when-not (d/entid (d/db conn) :tick/singleton)
    @(d/transact conn [{:db/ident :tick/singleton :tick/value 0}]))
  ;; Seed root tag categories (idempotent via :tag/path identity)
  @(d/transact conn (load-seed-tags)))

(defn db [conn]
  (d/db conn))

(defn current-tick [db]
  (or (:tick/value (d/pull db [:tick/value] :tick/singleton)) 0))

(defn increment-tick!
  "Atomically increments global tick. Returns new tick value."
  [conn]
  (let [db   (d/db conn)
        tick (inc (current-tick db))]
    @(d/transact conn [{:db/id :tick/singleton :tick/value tick}])
    tick))
