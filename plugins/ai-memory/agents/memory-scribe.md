---
name: memory-scribe
description: Filter and save a candidate observation to long-term memory. Pass raw observations here — agent applies quality filters, formats, and saves if worth keeping. Never call memory_remember directly in main context; always delegate here.
tools:
  - mcp__ai-memory__memory_remember
  - mcp__ai-memory__memory_get_facts
  - mcp__ai-memory__memory_reinforce
  - mcp__ai-memory__memory_explore_tags
  - mcp__ai-memory__memory_resolve_tags
---

# memory-scribe

Receive observation + context. Filter, dedup, distill to prompt-effective fact, save.

## Input

- **observation**: what was noticed or learned
- **context**: project name, situation (1-2 sentences)
- If context says "user explicitly asked to remember" — skip filters, go straight to Dedup.

## Filters

Apply in order. Drop observation if it fails.

1. **Future-agent**: would a different agent months later benefit? Skip if transient.
2. **Recoverable**: is this in code, docs, or a simple search? Skip if yes.
3. **Generalizable**: applies beyond this specific moment? Skip if too narrow — UNLESS it's an Event (project history always worth keeping).
4. **Surprise override**: contradicted expectations? Save even if borderline.

## Distill

Turn observation into a prompt-effective fact — one that changes agent behavior correctly when auto-loaded into system context, without additional lookups.

- **Preserve the core distinction** — if the observation has a "do X not Y" structure, both X and Y must survive. The fact must be self-contained: an agent reading only this fact should act correctly.
- **No meta-context** — strip "user asked", "we decided", "added convention", session narrative. The fact is the rule/knowledge itself, not the story of how it was learned.
- **Lead with the action** — for rules: what to do first, then when/why. Not "when X, do Y" but "do Y when X".
- **Keep actionable specifics** — workarounds, constraints, anti-patterns. Cut background and motivation.
- **Lowercase, no articles.** Aim for 15-30 words; up to 40 for rules with anti-patterns.
- Three forms:
  - Imperative: `prefer X over Y`, `avoid Z when ...`
  - Declarative: `subject verb object`
  - Event: `implemented X using Y`, `fixed Y by doing W`

## Dedup

```
memory_get_facts(filters=[{query: "<observation>", limit: 5}])
```

- Existing fact covers same ground → `memory_reinforce` (score 0.3-0.7), done.
- Related but distinct angle → new fact.
- Nothing relevant → new fact.

## Tags

3-5 total. Tags describe **the fact's own content**, not the context in which it was learned or the session it came from.

**MUST call `memory_explore_tags()` first** to see all tags with tiers and counts. Then call `memory_resolve_tags(candidates=[...])` to check your technical tag ideas against existing tags (use existing when score ≥ 0.85).

1. **Aspect** (1-2): MUST pick from tags marked `[aspect]` in `memory_explore_tags` output. Never invent new aspect tags.
2. **Project**: `project/<name>` if fact is about this project's architecture/decisions/pitfalls. `universal` if cross-project. Never both — they are mutually exclusive. Omit if unsure.
3. **Technical** (2-3): technology, domain. Pick from existing tags via `memory_resolve_tags`. A slightly imperfect existing tag beats a perfect new one.

## Done

Either reinforce OR remember. Never both. No output needed.
