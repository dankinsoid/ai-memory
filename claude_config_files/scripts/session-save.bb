#!/usr/bin/env bb
;; session-save.bb — called by the session-init agent hook on first user message.
;;
;; Args: <session-id> <summary> <tags-csv> <cwd>
;;
;; - Checks lock file (exits 0 immediately if already done)
;; - Derives project from git remote URL (reliable across worktrees)
;; - Creates lock file
;; - POSTs to /api/session/update with session-id, summary, tags, project
;;
;; Env-var toggles:
;;   AI_MEMORY_DISABLED=1     — master switch
;;   AI_MEMORY_NO_WRITE=1     — disable all writes
;;   AI_MEMORY_NO_SESSIONS=1  — disable session features

(when (or (System/getenv "AI_MEMORY_DISABLED")
          (System/getenv "AI_MEMORY_NO_WRITE")
          (System/getenv "AI_MEMORY_NO_SESSIONS"))
  (System/exit 0))

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str])

(def args *command-line-args*)

(when (< (count args) 4)
  (binding [*out* *err*]
    (println "session-save: usage: session-save.bb <session-id> <summary> <tags-csv> <cwd>"))
  (System/exit 1))

(let [session-id (nth args 0)
      summary    (nth args 1)
      tags-csv   (nth args 2)
      cwd        (nth args 3)
      state-dir  (str (System/getenv "HOME") "/.claude/hooks/state")
      lock-file  (str state-dir "/" session-id "-session-init.lock")]

  ;; Fast exit if already initialized
  (when (fs/exists? lock-file)
    (System/exit 0))

  (let [tags (when-not (str/blank? tags-csv)
               (str/split tags-csv #","))

        ;; Derive project from git remote URL (same logic as session-reminder.bb)
        project (or (try
                      (let [result (process/sh "git" "-C" cwd "remote" "get-url" "origin")]
                        (when (zero? (:exit result))
                          (-> (str/trim (:out result))
                              (str/replace #"\.git$" "")
                              (str/split #"[/:]")
                              last)))
                      (catch Exception _ nil))
                    (when cwd (last (str/split cwd #"/"))))

        base-url  (or (System/getenv "AI_MEMORY_URL") "http://localhost:8080")
        api-token (System/getenv "AI_MEMORY_TOKEN")]

    ;; Create lock file before API call to prevent races
    (fs/create-dirs state-dir)
    (spit lock-file "")

    ;; POST to /api/session/update
    (try
      (http/post (str base-url "/api/session/update")
                 {:headers (cond-> {"Content-Type" "application/json"}
                             api-token (assoc "Authorization" (str "Bearer " api-token)))
                  :body    (json/generate-string
                             (cond-> {:session-id session-id
                                      :summary    summary
                                      :tags       tags}
                               project (assoc :project project)))})
      (catch Exception e
        (binding [*out* *err*]
          (println "session-save: POST failed:" (.getMessage e)))))))

(System/exit 0)
