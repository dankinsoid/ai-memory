#!/usr/bin/env bb
;; UserPromptSubmit hook: reminds agent to name the current chunk when it grows large.
;;
;; Reads chunk-bytes from state file (written by session-sync.bb on Stop).
;; If above threshold, prints reminder to stdout (injected into agent context).
;; No HTTP calls, no transcript parsing — executes instantly.

(require '[cheshire.core :as json]
         '[babashka.fs :as fs])

(def chunk-size-threshold
  "Byte size of _current.md above which we remind the agent to name the chunk.
   ~20KB = 5-8 turns of substantive conversation text."
  20000)

(let [input      (json/parse-string (slurp *in*) true)
      session-id (:session_id input)
      state-dir  (str (System/getenv "HOME") "/.claude/hooks/state")
      state-file (str state-dir "/" session-id ".edn")]

  (when (and session-id (fs/exists? state-file))
    (let [state       (try (read-string (slurp state-file))
                           (catch Exception _ {}))
          chunk-bytes (or (:chunk-bytes state) 0)]
      (when (> chunk-bytes chunk-size-threshold)
        (println
          (str "The current conversation chunk has grown to ~"
               (int (/ chunk-bytes 1024)) "KB. "
               "Call memory_name_chunk({ "
               "context_id: \"" session-id "\", "
               "title: \"short-description\" "
               "}) to name it. The title should describe the work done in previous turns "
               "(not including this exchange)."))))))

(System/exit 0)
