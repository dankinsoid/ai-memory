#!/usr/bin/env bb
;; UserPromptSubmit hook: project summary reminder.
;;
;; Reminds about memory_project once per N days per project.
;; Session metadata (title, summary, compact, chunks) is handled by
;; the agent hook on Stop event — see settings.json.
;;
;; Env-var toggles (set any to disable):
;;   AI_MEMORY_DISABLED=1     — master switch (all hooks)
;;   AI_MEMORY_NO_WRITE=1     — disable all writes/nudges
;;   AI_MEMORY_NO_SESSIONS=1  — disable session-specific features

(when (or (System/getenv "AI_MEMORY_DISABLED")
          (System/getenv "AI_MEMORY_NO_WRITE")
          (System/getenv "AI_MEMORY_NO_SESSIONS"))
  (System/exit 0))

(require '[cheshire.core :as json]
         '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str])

(def project-remind-interval-days
  "Remind about project summary at most once every N days per project."
  3)

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

(defn days-since [date-str]
  (try
    (let [past  (java.time.LocalDate/parse date-str)
          today (java.time.LocalDate/now)]
      (.between java.time.temporal.ChronoUnit/DAYS past today))
    (catch Exception _ Long/MAX_VALUE)))

(let [input        (json/parse-string (slurp *in*) true)
      session-id   (:session_id input)
      cwd          (:cwd input)
      project-name (derive-project cwd)
      state-dir    (str (System/getenv "HOME") "/.claude/hooks/state")
      ;; Track prompt count for the project-remind (only on first prompt)
      reminder-file (str state-dir "/" session-id "-reminder.edn")]

  (when (and session-id project-name)
    (let [reminder-state (if (fs/exists? reminder-file)
                           (try (read-string (slurp reminder-file))
                                (catch Exception _ {}))
                           {})
          prompt-count (inc (:prompt-count reminder-state 0))

          ;; Project summary reminder: once per N days per project, on first prompt
          project-remind-file (str state-dir "/project-remind-" project-name ".edn")
          project-remind-state (when (fs/exists? project-remind-file)
                                 (try (read-string (slurp project-remind-file))
                                      (catch Exception _ {})))
          need-project-remind? (and (= prompt-count 1)
                                    (>= (days-since (:last-reminded project-remind-state ""))
                                        project-remind-interval-days))]

      (fs/create-dirs state-dir)
      (spit reminder-file (pr-str {:prompt-count prompt-count}))

      (when need-project-remind?
        (spit project-remind-file
              (pr-str {:last-reminded (str (java.time.LocalDate/now))}))
        (println (str "Call memory_project(project=\"" project-name
                      "\", summary=\"...\") if the project description has changed or is not yet stored."))))))

(System/exit 0)
