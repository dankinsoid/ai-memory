---
name: remember
description: Save a rule, preference, or observation to long-term memory. Use when the user says "remember this", "always do X", "never do Y", or asks to save a convention/preference. Delegates to memory-scribe agent in background.
---

# Remember

Extract the observation from the user's message and surrounding context. Spawn memory-scribe agent in background:

```
Agent(subagent_type="ai-memory:memory-scribe", run_in_background=true,
      prompt="observation: <distilled observation>\ncontext: project=<name>, user explicitly asked to remember")
```

Confirm briefly: "Saving to memory." — do not wait for the agent to finish.
