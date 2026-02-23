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

;; Debug log — always write, so we see every invocation
(let [state-dir (str (System/getenv "HOME") "/.claude/hooks/state")
      log-file  (str state-dir "/session-end.log")]
  (fs/create-dirs state-dir)
  (spit log-file
        (str (java.time.Instant/now)
             " | reason=" (pr-str hook-reason)
             " | session=" session-id
             " | cwd=" cwd
             "\n")
        :append true))

;; Only act on clear (matcher should filter, but double-check)
(when-not (= hook-reason "clear")
  (System/exit 0))

(when-not session-id
  (System/exit 0))

(let [project   (when cwd (last (str/split cwd #"/")))
      state-dir (str (System/getenv "HOME") "/.claude/hooks/state")]
  (when project
    (fs/create-dirs state-dir)
    (let [cache-file (str state-dir "/prev-session-" project ".edn")]
      (spit cache-file
            (pr-str {:session-id session-id
                     :project    project}))
      ;; Log cache write
      (spit (str state-dir "/session-end.log")
            (str (java.time.Instant/now)
                 " | WROTE cache " cache-file
                 " | " (pr-str {:session-id session-id :project project})
                 "\n")
            :append true))))

(System/exit 0)
