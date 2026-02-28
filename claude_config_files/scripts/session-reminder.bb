#!/usr/bin/env bb
;; UserPromptSubmit hook: reminds agent about session metadata updates.
;;
;; Two types of reminders:
;; 1. Summary — on early turns, prompt agent to call memory_session with summary
;; 2. Chunk naming — when context token usage crosses a fixed boundary (e.g. every 20K tokens)
;;
;; Reads state files per session:
;;   - {session-id}-reminder.edn — written here: {:prompt-count N :first-prompt-len N :last-chunk-tokens N}
;; Reads transcript JSONL to extract total token usage.
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
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def project-remind-interval-days
  "Remind about project summary at most once every N days per project."
  3)

(def chunk-token-step
  "Create a new chunk every N tokens of context growth."
  20000)

(def short-prompt-len
  "Prompts shorter than this are likely greetings, not substantive."
  50)

(def summary-remind-turns
  "Remind about summary for this many early turns (if first prompt was short)."
  3)

(defn- get-context-tokens
  "Extracts total token usage from last assistant message in transcript JSONL.
   Reads last 100 lines to find the most recent usage entry."
  [transcript-path]
  (when (and transcript-path (fs/exists? transcript-path))
    (try
      (let [lines (-> (io/reader transcript-path)
                      (slurp)
                      (str/split-lines)
                      reverse
                      (->> (take 100)))
            usage (->> lines
                       (keep (fn [line]
                               (try
                                 (let [entry (json/parse-string line true)]
                                   (get-in entry [:message :usage]))
                                 (catch Exception _ nil))))
                       first)]
        (when usage
          (let [input   (:input_tokens usage 0)
                cached  (:cache_read_input_tokens usage 0)
                created (:cache_creation_input_tokens usage 0)]
            (+ input cached created))))
      (catch Exception _ nil))))

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

(let [input      (json/parse-string (slurp *in*) true)
      session-id (:session_id input)
      prompt     (:prompt input)
      transcript (:transcript_path input)
      cwd        (:cwd input)
      project-name (derive-project cwd)
      state-dir  (str (System/getenv "HOME") "/.claude/hooks/state")
      reminder-file (str state-dir "/" session-id "-reminder.edn")]

  (when session-id
    ;; Read/update reminder state
    (let [reminder-state (if (fs/exists? reminder-file)
                           (try (read-string (slurp reminder-file))
                                (catch Exception _ {}))
                           {})
          prompt-count   (inc (:prompt-count reminder-state 0))
          first-prompt-len (or (:first-prompt-len reminder-state)
                               (when prompt (count prompt))
                               0)
          last-chunk-tokens (:last-chunk-tokens reminder-state 0)

          ;; Get current total token usage
          context-tokens (or (get-context-tokens transcript) 0)

          ;; Reset after /clear or /compact (tokens dropped below last boundary)
          last-chunk-tokens (if (< context-tokens last-chunk-tokens)
                              (* chunk-token-step (quot context-tokens chunk-token-step))
                              last-chunk-tokens)

          ;; Check if we crossed a chunk-token-step boundary
          current-bucket (* chunk-token-step (quot context-tokens chunk-token-step))
          need-chunk?    (and (> current-bucket last-chunk-tokens)
                              (> prompt-count 1))

          new-state      (cond-> {:prompt-count      prompt-count
                                  :first-prompt-len  first-prompt-len
                                  :last-chunk-tokens (if need-chunk?
                                                       current-bucket
                                                       last-chunk-tokens)}
                           context-tokens (assoc :context-tokens context-tokens))

          ;; Summary reminder on early turns
          need-summary? (or (= prompt-count 1)
                            (and (<= prompt-count summary-remind-turns)
                                 (< first-prompt-len short-prompt-len)))

          ;; Project summary reminder: once per N days per project
          project-remind-file (when project-name
                                 (str state-dir "/project-remind-" project-name ".edn"))
          project-remind-state (when (and project-remind-file (fs/exists? project-remind-file))
                                  (try (read-string (slurp project-remind-file))
                                       (catch Exception _ {})))
          need-project-remind? (and project-name
                                    (= prompt-count 1)
                                    (>= (days-since (:last-reminded project-remind-state ""))
                                        project-remind-interval-days))]

      (fs/create-dirs state-dir)
      (spit reminder-file (pr-str new-state))
      (when need-project-remind?
        (spit project-remind-file
              (pr-str {:last-reminded (str (java.time.LocalDate/now))})))

      (when (or need-summary? need-chunk? need-project-remind?)
        (println
         (str/join " "
           (cond-> []
             need-chunk?
             (conj (str "Chunk ~" (quot context-tokens 1000) "K tokens."
                        " Call memory_session with session_id: \"" session-id
                        "\", chunk_title, and summary."))
             (and need-summary? (not need-chunk?))
             (conj (str "Call memory_session with session_id: \"" session-id
                        "\", summary, and tags."))
             need-project-remind?
             (conj (str "Call memory_project(project=\"" project-name
                        "\", summary=\"...\") if the project description has changed or is not yet stored.")))))))))

(System/exit 0)
