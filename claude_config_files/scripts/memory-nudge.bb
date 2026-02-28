#!/usr/bin/env bb
;; UserPromptSubmit hook: stochastic memory nudge (~30% probability).
;; Reminds the agent to check if this exchange produced anything memory-worthy.
;;
;; Env-var toggles (set any to disable):
;;   AI_MEMORY_DISABLED=1     — master switch (all hooks)
;;   AI_MEMORY_NO_WRITE=1     — disable all writes/nudges
;;   AI_MEMORY_NO_FACTS=1     — disable fact-specific features

(require '[cheshire.core :as json])

(when (or (System/getenv "AI_MEMORY_DISABLED")
          (System/getenv "AI_MEMORY_NO_WRITE")
          (System/getenv "AI_MEMORY_NO_FACTS"))
  (System/exit 0))

(let [input      (json/parse-string (slurp *in*) true)
      session-id (:session_id input)]
  (when (and session-id (< (rand) 0.30))
    (println "Memory nudge: did the last exchange ring any of the 5 triggers? (I wish I'd known / should remember / understanding changed / user preference / interesting) → memory-scribe.")))

(System/exit 0)
