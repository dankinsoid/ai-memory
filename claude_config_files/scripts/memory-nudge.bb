#!/usr/bin/env bb
;; UserPromptSubmit hook: memory nudge (fires every turn).
;; Prompts the agent to extract and save memory-worthy facts from the last exchange.
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
  (when session-id
    (println "Memory nudge: scan the last exchange and identify 1-3 things worth saving — user preferences, discoveries, pitfalls, patterns, what the user is currently focused on/exploring. Delegate each to memory-scribe in background (one call per fact). If genuinely nothing worth saving, skip.")))

(System/exit 0)
