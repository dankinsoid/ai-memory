#!/usr/bin/env bb
;; SessionEnd hook: caches current session info for continuation linking.
;;
;; Fires on: clear (only). Writes prev-session cache so that the next
;; SessionStart (clear) can link the new session to this one.
;;
;; Cache file: ~/.claude/hooks/state/prev-session-{project}.edn
;;   {:session-id "..." :project "..."}

(require '[cheshire.core :as json]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def input (json/parse-string (slurp *in*) true))
(def session-id (:session_id input))
(def hook-reason (:reason input))
(def cwd (:cwd input))

;; Only act on clear
(when-not (= hook-reason "clear")
  (System/exit 0))

(when-not session-id
  (System/exit 0))

(let [project   (when cwd (last (str/split cwd #"/")))
      state-dir (str (System/getenv "HOME") "/.claude/hooks/state")]
  (when project
    (fs/create-dirs state-dir)
    (spit (str state-dir "/prev-session-" project ".edn")
          (pr-str {:session-id session-id
                   :project    project}))))

(System/exit 0)
