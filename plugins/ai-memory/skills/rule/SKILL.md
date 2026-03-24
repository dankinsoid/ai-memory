---
name: rule
description: Save a rule, preference, or convention to long-term memory. Use when the user says "remember this", "always do X", "never do Y", or asks to save a convention/preference.
---

# Rule

1. **Clean** — strip meta-wrappers ("remember that", "I want you to"). Fix broken references. Translate to English.
2. **Tag** — always include `rule`. Add scope tag when applicable:
   - project-specific → `project/<project-name>`
   - language/tech-specific → `lang/<language>`
   - universal (applies everywhere) → `universal`
3. **Title** — pick a kebab-case slug, e.g. `always-run-regression-test-before-fixing-bug`.
4. **Save** — call `memory_remember(content=..., tags=[...], title=...)`. All three params are required.

Confirm: "Saved to memory."
