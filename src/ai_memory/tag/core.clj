(ns ai-memory.tag.core
  "Flat atomic tags. :tag/name (unique/identity) is the tag identifier."
  (:import [java.util UUID]))

(defn tag-point-id
  "Deterministic UUID for a tag name, used as vector store point ID.
   Uses v3 (MD5-based) UUID with 'tag:' prefix to avoid collisions."
  [tag-name]
  (str (UUID/nameUUIDFromBytes (.getBytes (str "tag:" tag-name) "UTF-8"))))
