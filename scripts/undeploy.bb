#!/usr/bin/env bb
;; Removes ai-memory Claude config from ~/.claude/
;;
;; - Removes <!-- ai-memory:start/end --> section from ~/.claude/CLAUDE.md
;; - Removes ai-memory hook entries from ~/.claude/settings.json
;; - MCP permissions are left intact (harmless without a running server)
;;
;; Usage:
;;   bb scripts/undeploy.bb               — remove both prompt section and hooks
;;   bb scripts/undeploy.bb --prompt-only — remove only the CLAUDE.md section
;;   bb scripts/undeploy.bb --hooks-only  — remove only the hook entries

(require '[cheshire.core :as json]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def home (System/getenv "HOME"))
(def claude-dir (str home "/.claude"))
(def args *command-line-args*)

(def prompt-only? (some #{"--prompt-only"} args))
(def hooks-only?  (some #{"--hooks-only"} args))

(defn log [& args] (apply println "  " args))

;; --- Hook script names installed by deploy.bb ---

(def our-scripts
  #{"session-start.bb" "session-sync.bb" "session-end.bb"
    "session-reminder.bb" "memory-nudge.bb"})

(defn our-hook? [handler]
  (when-let [cmd (:command handler)]
    (some #(str/includes? cmd %) our-scripts)))

;; --- 1. Remove prompt section from CLAUDE.md ---

(def marker-start "<!-- ai-memory:start -->")
(def marker-end   "<!-- ai-memory:end -->")

(defn remove-section [text]
  (let [start-idx (.indexOf text marker-start)
        end-idx   (.indexOf text marker-end)]
    (if (and (>= start-idx 0) (>= end-idx 0))
      (str (str/trimr (.substring text 0 start-idx))
           (.substring text (+ end-idx (count marker-end))))
      text)))

(when-not hooks-only?
  (println "\n[prompt]")
  (let [dest-path (str claude-dir "/CLAUDE.md")]
    (if (fs/exists? dest-path)
      (let [existing (slurp dest-path)
            updated  (remove-section existing)]
        (if (= existing updated)
          (log "no ai-memory section found in" dest-path)
          (do (spit dest-path updated)
              (log "removed section from" dest-path))))
      (log "not found:" dest-path))))

;; --- 2. Remove our hooks from settings.json ---

(defn remove-our-hooks [hooks]
  (reduce-kv
    (fn [acc event groups]
      (let [filtered-groups
            (->> groups
                 (keep (fn [group]
                         (let [remaining (vec (remove our-hook? (:hooks group)))]
                           (when (seq remaining)
                             (assoc group :hooks remaining)))))
                 vec)]
        (if (seq filtered-groups)
          (assoc acc event filtered-groups)
          acc)))
    {}
    hooks))

(when-not prompt-only?
  (println "\n[hooks]")
  (let [settings-path (str claude-dir "/settings.json")]
    (if (fs/exists? settings-path)
      (let [settings      (json/parse-string (slurp settings-path) true)
            updated-hooks (remove-our-hooks (:hooks settings {}))
            updated       (assoc settings :hooks updated-hooks)]
        (spit settings-path (json/generate-string updated {:pretty true}))
        (log "hooks updated in" settings-path))
      (log "not found:" settings-path))))

(println "\n✓ undeploy complete")
