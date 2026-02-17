(ns ai-memory.tag.resolve
  "Resolves tag strings to tag entity lookup refs for the write pipeline."
  (:require [ai-memory.tag.core :as tag]
            [clojure.string :as str]
            [datomic.api :as d]))

(defn resolve-tag
  "Resolves a tag string to a lookup ref [:tag/path path].
   - Path (contains '/') → ensure-tag! creates hierarchy if needed.
   - Bare name → find by name. If unique, use it. If missing, create as root."
  [conn tag-str]
  (let [tag-str (str/trim tag-str)]
    (if (str/includes? tag-str "/")
      ;; Full path — ensure hierarchy exists
      (do (tag/ensure-tag! conn tag-str)
          [:tag/path tag-str])
      ;; Bare name — look up or create as root
      (let [db      (d/db conn)
            matches (tag/find-by-name db tag-str)]
        (if (= 1 (count matches))
          [:tag/path (:tag/path (first matches))]
          (do (tag/ensure-tag! conn tag-str)
              [:tag/path tag-str]))))))

(defn resolve-tags
  "Resolves a seq of tag strings to lookup refs."
  [conn tag-strs]
  (mapv #(resolve-tag conn %) tag-strs))
