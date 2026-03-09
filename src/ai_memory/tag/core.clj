(ns ai-memory.tag.core
  "Flat atomic tags. :tag/name (unique/identity) is the tag identifier."
  (:require [datomic.api :as d]
            [ai-memory.db.core :as db])
  (:import [java.util UUID]))

(defn tag-point-id
  "Deterministic UUID for a tag name, used as Qdrant point ID.
   Uses v3 (MD5-based) UUID with 'tag:' prefix to avoid collisions."
  [tag-name]
  (str (UUID/nameUUIDFromBytes (.getBytes (str "tag:" tag-name) "UTF-8"))))

(defn ensure-tag!
  "Creates tag if it doesn't exist. Returns tag name."
  [conn name]
  (when-not (d/entid (d/db conn) [:tag/name name])
    (db/transact! conn [{:tag/name name}]))
  name)

(defn all-tags
  "Returns all tags with materialized counts."
  [db]
  (d/q '[:find [(pull ?t [:tag/name :tag/node-count :tag/tier]) ...]
         :where [?t :tag/name]]
       db))
