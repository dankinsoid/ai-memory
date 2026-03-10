#!/usr/bin/env bb
;; SessionEnd hook: caches current session info for continuation linking.
;;
;; Fires on: clear (only). Writes prev-session cache so that the next
;; SessionStart (clear) can link the new session to this one.
;;
;; Cache file: ~/.claude/hooks/state/prev-session-{project}.edn
;;   {:session-id "..." :project "..."}
;;
;; Env-var toggles (set any to disable):
;;   AI_MEMORY_DISABLED=1     — master switch (all hooks)
;;   AI_MEMORY_NO_WRITE=1     — disable all writes
;;   AI_MEMORY_NO_SESSIONS=1  — disable session-specific features

(when (or (System/getenv "AI_MEMORY_DISABLED")
          (System/getenv "AI_MEMORY_NO_WRITE")
          (System/getenv "AI_MEMORY_NO_SESSIONS"))
  (System/exit 0))

(require '[cheshire.core :as json]
         '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str])

(defn git-project-name [cwd]
  (when cwd
    (try
      (let [result (process/sh "git" "-C" cwd "remote" "get-url" "origin")]
        (when (zero? (:exit result))
          (-> (str/trim (:out result))
              (str/replace #"\.git$" "")
              (str/split #"[/:]")
              last)))
      (catch Exception _ nil))))

(defn derive-project [cwd]
  (or (git-project-name cwd)
      (when cwd (last (str/split cwd #"/")))))

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

(let [project   (derive-project cwd)
      state-dir (str (System/getenv "HOME") "/.claude/hooks/state")]
  (when project
    (fs/create-dirs state-dir)
    (let [cache-file (str state-dir "/prev-session-" project ".edn")]
      (spit cache-file
            (pr-str {:session-id session-id
                     :project    project
                     :timestamp  (str (java.time.Instant/now))}))
      ;; Log cache write
      (spit (str state-dir "/session-end.log")
            (str (java.time.Instant/now)
                 " | WROTE cache " cache-file
                 " | " (pr-str {:session-id session-id :project project})
                 "\n")
            :append true))))

(System/exit 0)
