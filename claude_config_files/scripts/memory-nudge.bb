#!/usr/bin/env bb
;; UserPromptSubmit hook: memory nudge (fires every turn).
;; Prompts the agent to extract and save memory-worthy facts from the last exchange.

(require '[cheshire.core :as json])

(let [input      (json/parse-string (slurp *in*) true)
      session-id (:session_id input)]
  (when session-id
    (println "Memory nudge: scan the last exchange and identify 1-3 things worth saving — user preferences, discoveries, pitfalls, patterns, what the user is currently focused on/exploring. Delegate each to memory-scribe in background (one call per fact). If genuinely nothing worth saving, skip.")))

(System/exit 0)
