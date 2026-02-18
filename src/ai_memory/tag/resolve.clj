(ns ai-memory.tag.resolve
  "Resolves tag strings to tag entity lookup refs for the write pipeline."
  (:require [ai-memory.tag.core :as tag]
            [clojure.string :as str]))

(defn resolve-tag
  "Resolves a tag string to a lookup ref [:tag/name name].
   Ensures the tag exists (creates if missing)."
  [conn tag-str]
  (let [name (str/trim tag-str)]
    (tag/ensure-tag! conn name)
    [:tag/name name]))

(defn resolve-tags
  "Resolves a seq of tag strings to lookup refs."
  [conn tag-strs]
  (mapv #(resolve-tag conn %) tag-strs))
