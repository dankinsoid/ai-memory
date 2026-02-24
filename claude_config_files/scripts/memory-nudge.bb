#!/usr/bin/env bb
;; UserPromptSubmit hook: stochastic memory nudge (~30% probability).
;; Reminds the agent to check if this exchange produced anything memory-worthy.

(require '[cheshire.core :as json])

(let [input      (json/parse-string (slurp *in*) true)
      session-id (:session_id input)]
  (when (and session-id (< (rand) 0.30))
    (println "Memory nudge: did the last exchange ring any of the 5 triggers? (I wish I'd known / should remember / understanding changed / user preference / interesting) → memory-scribe.")))

(System/exit 0)
