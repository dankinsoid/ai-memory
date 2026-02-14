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

(defn ensure-schema [conn]
  @(d/transact conn (load-schema)))

(defn db [conn]
  (d/db conn))
