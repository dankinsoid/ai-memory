#!/usr/bin/env bb
;; Deploys ai-memory Claude config files to ~/.claude/
;;
;; - prompt.md       → ~/.claude/CLAUDE.md (replace section between markers)
;; - scripts/*.bb    → ~/.claude/hooks/ (overwrite)
;; - skills/*/       → ~/.claude/skills/*/ (overwrite matching only)
;; - settings.json   → ~/.claude/settings.json (merge, no duplicates)

(require '[cheshire.core :as json]
         '[babashka.fs :as fs]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def home (System/getenv "HOME"))
(def claude-dir (str home "/.claude"))
(def repo-dir (str (fs/parent (fs/parent *file*))))
(def src-dir (str repo-dir "/claude_config_files"))

(defn log [& args] (apply println "  " args))

;; --- 1. prompt.md → CLAUDE.md (section replace) ---

(def marker-start "<!-- ai-memory:start -->")
(def marker-end   "<!-- ai-memory:end -->")

(defn replace-section
  "Replace content between markers. Appends section if markers absent."
  [existing-text new-section]
  (let [start-idx (.indexOf existing-text marker-start)
        end-idx   (.indexOf existing-text marker-end)]
    (if (and (>= start-idx 0) (>= end-idx 0))
      (str (.substring existing-text 0 start-idx)
           new-section
           (.substring existing-text (+ end-idx (count marker-end))))
      (str existing-text "\n\n" new-section "\n"))))

(println "\n[prompt]")
(let [src-path  (str src-dir "/prompt.md")
      dest-path (str claude-dir "/CLAUDE.md")
      section   (slurp src-path)
      existing  (if (fs/exists? dest-path) (slurp dest-path) "")]
  (spit dest-path (replace-section existing section))
  (log "section replaced in" dest-path))

;; --- 2. scripts → hooks ---

(println "\n[hooks]")
(let [hooks-dir (str claude-dir "/hooks")]
  (fs/create-dirs hooks-dir)
  (doseq [f (fs/glob src-dir "scripts/*.bb")]
    (let [dest (str hooks-dir "/" (fs/file-name f))]
      (fs/copy f dest {:replace-existing true})
      (log (str f) "→" dest))))

;; --- 3. skills (overwrite matching, leave others) ---

(println "\n[skills]")
(let [skills-src  (str src-dir "/skills")
      skills-dest (str claude-dir "/skills")]
  (fs/create-dirs skills-dest)
  (doseq [skill-dir (fs/list-dir skills-src)
          :when (fs/directory? skill-dir)]
    (let [name     (str (fs/file-name skill-dir))
          dest-dir (str skills-dest "/" name)]
      (fs/create-dirs dest-dir)
      (doseq [f (fs/glob skill-dir "**")]
        (when (fs/regular-file? f)
          (let [rel  (str (fs/relativize skill-dir f))
                dest (str dest-dir "/" rel)]
            (fs/create-dirs (fs/parent dest))
            (fs/copy f dest {:replace-existing true})
            (log (str f) "→" dest)))))))

;; --- 4. settings.json (merge) ---

(println "\n[settings]")

(defn read-json [path]
  (when (fs/exists? path)
    (json/parse-string (slurp (str path)) true)))

(defn merge-permissions
  "Union of allow lists, preserving order, no duplicates."
  [existing incoming]
  (let [existing-allow (set (get-in existing [:permissions :allow]))
        incoming-allow (get-in incoming [:permissions :allow])
        merged (into (vec (get-in existing [:permissions :allow]))
                     (remove existing-allow incoming-allow))]
    (assoc-in existing [:permissions :allow] merged)))

(defn hook-key
  "Identity key for a hook handler — its command string."
  [handler]
  (:command handler))

(defn merge-hook-group
  "Merge matcher groups for one event. Dedup by matcher+command."
  [existing-groups incoming-groups]
  (let [existing-index (reduce (fn [acc group]
                                 (let [m (:matcher group "")]
                                   (assoc acc m group)))
                               {} existing-groups)]
    (vals
      (reduce (fn [acc group]
                (let [m     (:matcher group "")
                      prev  (get acc m)
                      hooks (if prev
                              (let [prev-cmds (set (map hook-key (:hooks prev)))]
                                (into (vec (:hooks prev))
                                      (remove #(prev-cmds (hook-key %))
                                              (:hooks group))))
                              (:hooks group))]
                  (assoc acc m (assoc group :hooks hooks))))
              existing-index incoming-groups))))

(defn merge-hooks
  "Merge hooks by event name, then by matcher group, then dedup handlers."
  [existing incoming]
  (let [existing-hooks (:hooks existing {})
        incoming-hooks (:hooks incoming {})]
    (assoc existing :hooks
           (reduce-kv (fn [acc event groups]
                        (assoc acc event
                               (vec (merge-hook-group (get acc event []) groups))))
                      existing-hooks incoming-hooks))))

(defn pretty-json
  "JSON with arrays formatted one element per line."
  [data]
  (-> (json/generate-string data {:pretty true})
      ;; Cheshire inlines short arrays — expand them to one-per-line
      (str/replace #"\[ \"([^]]+)\" \]"
                   (fn [[_ inner]]
                     (let [items (str/split inner #"\", \"")]
                       (str "[\n"
                            (str/join ",\n" (map #(str "      \"" % "\"") items))
                            "\n    ]"))))))

(let [src-path  (str src-dir "/settings.json")
      dest-path (str claude-dir "/settings.json")
      incoming  (read-json src-path)
      existing  (or (read-json dest-path) {})
      merged    (-> existing
                    (merge-permissions incoming)
                    (merge-hooks incoming))]
  (spit dest-path (pretty-json merged))
  (log "merged" src-path "→" dest-path))

(println "\n✓ deploy complete")
