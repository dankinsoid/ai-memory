#!/usr/bin/env bb
;; UserPromptSubmit hook: reminds agent about session metadata updates.
;;
;; Two types of reminders:
;; 1. Summary — on early turns, prompt agent to call memory_session with summary
;; 2. Chunk naming — when _current.md is large, prompt agent to name the chunk
;;
;; Reads two state files per session:
;;   - {session-id}.edn        — written by session-sync.bb (Stop hook): {:last-uuid ... :chunk-bytes N}
;;   - {session-id}-reminder.edn — written here: {:prompt-count N :first-prompt-len N}

(require '[cheshire.core :as json]
         '[babashka.fs :as fs])

(def chunk-size-threshold
  "Byte size of _current.md above which we remind the agent to name the chunk.
   ~20KB ≈ 5-8 turns of substantive conversation."
  20000)

(def short-prompt-len
  "Prompts shorter than this are likely greetings, not substantive."
  50)

(def summary-remind-turns
  "Remind about summary for this many early turns (if first prompt was short)."
  3)

(let [input      (json/parse-string (slurp *in*) true)
      session-id (:session_id input)
      prompt     (:prompt input)
      state-dir  (str (System/getenv "HOME") "/.claude/hooks/state")
      sync-file  (str state-dir "/" session-id ".edn")
      reminder-file (str state-dir "/" session-id "-reminder.edn")]

  (when session-id
    ;; Read/update reminder state
    (let [reminder-state (if (fs/exists? reminder-file)
                           (try (read-string (slurp reminder-file))
                                (catch Exception _ {}))
                           {})
          prompt-count   (inc (or (:prompt-count reminder-state) 0))
          first-prompt-len (or (:first-prompt-len reminder-state)
                               (when prompt (count prompt))
                               0)
          new-state      {:prompt-count    prompt-count
                          :first-prompt-len first-prompt-len}]

      (fs/create-dirs state-dir)
      (spit reminder-file (pr-str new-state))

      ;; Read sync state (chunk-bytes)
      (let [sync-state  (when (fs/exists? sync-file)
                          (try (read-string (slurp sync-file))
                               (catch Exception _ {})))
            chunk-bytes (or (:chunk-bytes sync-state) 0)

            ;; Decide what to remind about
            need-summary? (or (= prompt-count 1)
                              (and (<= prompt-count summary-remind-turns)
                                   (< first-prompt-len short-prompt-len)))
            need-chunk?   (> chunk-bytes chunk-size-threshold)]

        (when (or need-summary? need-chunk?)
          (println
            (if need-chunk?
              (str "Chunk ~" (int (/ chunk-bytes 1024)) "KB. "
                   "Call memory_session with context_id: \"" session-id
                   "\", chunk_title, and summary.")
              (str "Call memory_session with context_id: \"" session-id
                   "\" and summary."))))))))

(System/exit 0)
