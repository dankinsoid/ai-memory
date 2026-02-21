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

(require '[cheshire.core :as json]
         '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

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

(let [input      (json/parse-string (slurp *in*) true)
      session-id (:session_id input)
      prompt     (:prompt input)
      transcript (:transcript_path input)
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
                                 (< first-prompt-len short-prompt-len)))]

      (fs/create-dirs state-dir)
      (spit reminder-file (pr-str new-state))

      (when (or need-summary? need-chunk?)
        (println
         (if need-chunk?
           (str "Chunk ~" (quot context-tokens 1000) "K tokens. "
                "Call memory_session with context_id: \"" session-id
                "\", chunk_title, and summary.")
           (str "Call memory_session with context_id: \"" session-id
                "\" and summary.")))))))

(System/exit 0)
