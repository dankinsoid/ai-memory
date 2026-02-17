# Ideation: Memory Integration into Claude Code

## Problem

Bridge ai-memory (Datomic + blob storage + tag taxonomy, 9 MCP tools ready) with Claude Code agents so that the agent **always knows what it learned before**, learns from every conversation, and never loses context — while spending minimal tokens.

## Constraints

| Constraint | Type |
|---|---|
| Token economy is paramount | Hard |
| Claude Code extension points: MCP, hooks, CLAUDE.md, skills | Hard |
| ai-memory backend already has 9 MCP tools over stdio | Fixed |
| MCP server NOT yet registered in `~/.claude/settings` | Gap |
| No hooks configured yet | Gap |
| Must work per-project | Hard |
| Autocompact replacement desirable | Soft |

---

## Ideas

### Idea 1: Zero-Cost Hook Pipeline
**CATEGORY: SAFE** | **EFFORT: MEDIUM**

Three hooks work together: (1) `PreUserMessage` hook runs a script that queries memory server via HTTP (tag match on project + topic keywords from the incoming message) and injects relevant facts as system context — agent never calls MCP to read. (2) Agent's natural output includes lightweight `<!-- FACT: ... | tags: ... -->` markers as part of its response text. (3) `PostAssistantMessage` hook parses these markers and POSTs them to memory server REST API. Zero explicit MCP calls from agent. Read = injected before message. Write = parsed after message.

**WHY IT MIGHT WORK:** Agent never spends tokens on MCP tool calls for basic read/write. Hooks are invisible and automatic.
**BIGGEST RISK:** Hook scripts must be fast (<500ms) or they block the agent. Keyword extraction from user message for retrieval may be imprecise.

---

### Idea 2: MCP Registration + CLAUDE.md Instructions
**CATEGORY: SAFE** | **EFFORT: LOW**

Simplest path: register `ai-memory` as MCP server in `~/.claude/settings.json`, add CLAUDE.md instructions telling agent to (a) call `memory_browse_tags` + `memory_get_facts` at session start, (b) call `memory_remember` when learning something new, (c) call `memory_store_conversation` at session end. Agent manages its own memory explicitly via MCP tool calls.

**WHY IT MIGHT WORK:** Works TODAY with zero new code. Agent is smart enough to follow instructions.
**BIGGEST RISK:** Agent may forget to call memory tools (unreliable). Each MCP call costs tokens. No automation.

---

### Idea 3: CPU Cache Hierarchy (L1/L2/L3)
**CATEGORY: CREATIVE** | **EFFORT: HIGH**

Three layers: **L1** = auto-generated section in CLAUDE.md with top-50 highest-weight facts for this project (~2KB, always in prompt cache, zero runtime cost). **L2** = session preload via `PreSessionStart` hook — queries memory for project-specific recent patterns, injects into first system message (~5KB). **L3** = lazy MCP queries when agent recognizes it needs deeper knowledge. A nightly job regenerates L1 from Datomic.

**WHY IT MIGHT WORK:** Most queries hit L1/L2 (free). L3 only for rare deep dives. Mirrors proven CPU cache architecture.
**BIGGEST RISK:** L1 regeneration requires running a script that overwrites CLAUDE.md. Staleness window between fact creation and L1 refresh.

---

### Idea 4: JSONL Watcher Daemon
**CATEGORY: CREATIVE** | **EFFORT: MEDIUM**

Background process (`fswatch` or Java WatchService) monitors `~/.claude/projects/*/` for JSONL changes. On every new line, it: (a) parses the message, (b) if assistant message — runs a cheap local summarizer to extract facts, (c) sends to memory server via REST. Agent never writes to memory — it's automatic. Conversation storage happens without any agent involvement.

**WHY IT MIGHT WORK:** Completely decoupled from agent. Zero agent tokens for writes. Works for ALL projects automatically.
**BIGGEST RISK:** Requires a running daemon. Fact extraction without agent intelligence may miss nuance. Need to handle concurrent writes to JSONL.

---

### Idea 5: Structured Output Markers
**CATEGORY: SAFE** | **EFFORT: LOW**

Add CLAUDE.md instruction: "End every response with a `<memory>` block containing facts learned." Format: `<memory><fact tags="a,b">content</fact></memory>`. A PostAssistantMessage hook strips the block (user never sees it) and POSTs facts to memory REST API. Agent writes memory as natural text — no MCP call overhead.

**WHY IT MIGHT WORK:** Leverages agent's intelligence for fact extraction. Near-zero overhead (a few tokens for the XML block). Hook handles the plumbing.
**BIGGEST RISK:** Agent may produce inconsistent formatting. Parsing XML from LLM output can be fragile.

---

### Idea 6: Memory-Backed Compaction
**CATEGORY: WILD** | **EFFORT: HIGH**

Replace Claude's autocompact with a custom hook: when context approaches limit, instead of generic summarization, extract all learnings as facts into memory, save conversation as blob, then `/clear` and restart with memory preload. The new session starts with full knowledge from L1+L2 cache. Effectively: context never dies, it metamorphoses into persistent memory.

**WHY IT MIGHT WORK:** Nothing is ever lost. Each compaction makes memory BETTER. Eliminates autocompact's lossy compression.
**BIGGEST RISK:** `/clear` is disruptive. Requires seamless handoff. May confuse the agent about conversation continuity.

---

### Idea 7: Memory Skill (`/recall`, `/save`)
**CATEGORY: SAFE** | **EFFORT: LOW**

Create two Claude Code skills: `/recall <topic>` does the full 3-phase tag retrieval (browse -> count -> fetch) in a single skill invocation, injecting results into context. `/save` extracts facts from current conversation and stores them. Skills are user-triggered but do the heavy lifting internally.

**WHY IT MIGHT WORK:** User stays in control. Skills can do complex multi-step operations cheaply. Works with existing MCP tools.
**BIGGEST RISK:** Not automatic — requires user to remember to invoke. Doesn't help with continuous learning.

---

### Idea 8: User Message as Memory Query
**CATEGORY: CREATIVE** | **EFFORT: MEDIUM**

PreUserMessage hook takes the raw user message, extracts keywords (simple TF-IDF or even just nouns), queries memory server for matching tags and facts, injects as `<system-reminder>` before the message reaches the agent. Agent sees relevant memory as context without ever asking. Combine with vector search for semantic matching.

**WHY IT MIGHT WORK:** Every user message is inherently a "query" about what to work on. Memory retrieval becomes automatic and contextual.
**BIGGEST RISK:** May inject irrelevant facts (false positives). Keyword extraction is imprecise for complex messages.

---

### Idea 9: Conversation Lifecycle Hooks
**CATEGORY: SAFE** | **EFFORT: MEDIUM**

Three hooks for the full lifecycle: (1) `SessionStart` — preload project memory summary. (2) `PostAssistantMessage` — stream-update a "running summary" file that tracks the current conversation state. (3) `SessionEnd` (or a `/save-session` skill) — store the full conversation as blob + extract final facts. The running summary enables continuation across sessions.

**WHY IT MIGHT WORK:** Covers the full lifecycle. Running summary can replace autocompact.
**BIGGEST RISK:** Claude Code may not have a reliable `SessionEnd` event. Running summary file must be atomic.

---

### Idea 10: Context Budget Controller
**CATEGORY: CREATIVE** | **EFFORT: HIGH**

A middleware layer between memory and agent that manages a "token budget" for memory. Given budget B tokens, it optimally fills context: critical facts (full), relevant facts (one-liners), tangential facts (just tag names). Budget adjusts dynamically based on conversation complexity. More complex task = more memory budget, simple fix = minimal memory.

**WHY IT MIGHT WORK:** Prevents memory from bloating context. Optimal information density.
**BIGGEST RISK:** Complexity of implementation. Hard to estimate "complexity" of a task automatically.

---

### Idea 11: Background Learning Synthesizer
**CATEGORY: WILD** | **EFFORT: HIGH**

A cron job (daily/weekly) launches a Claude subagent that reads ALL accumulated conversations for a project, synthesizes higher-order patterns: "User prefers functional style", "Always uses ex-info for errors", "Avoids macros". These meta-patterns go into a special `meta/` tag category and form the core of L1 cache. Over time, the system develops a personality model.

**WHY IT MIGHT WORK:** Produces exactly the kind of knowledge that makes an agent feel "personalized". Raw facts are good; synthesized wisdom is better.
**BIGGEST RISK:** Expensive (reads many conversations). May produce wrong generalizations. Requires careful prompting.

---

### Idea 12: Dual-Track Memory (Fast Path + Slow Path)
**CATEGORY: CREATIVE** | **EFFORT: MEDIUM**

**Fast path** (synchronous): Hook extracts project name + file names from context, does direct tag lookup (e.g., `projects/ai-memory + languages/clojure`), injects top-5 facts. Takes <100ms. **Slow path** (async): PostAssistantMessage hook queues a background job that does full conversation analysis, vector search, fact extraction, blob updates. Results appear in NEXT session's preload.

**WHY IT MIGHT WORK:** Fast path keeps agent responsive. Slow path does thorough processing without blocking. Best of both worlds.
**BIGGEST RISK:** Two code paths to maintain. Slow path results are delayed.

---

## Clusters

### Cluster A: "Hook-Driven Automation" (Ideas 1, 5, 8, 9)
Hooks do all the work. Agent barely knows memory exists. Maximum token savings, minimum agent awareness.

### Cluster B: "Agent-Directed Memory" (Ideas 2, 7)
Agent explicitly calls MCP tools or skills. Maximum control, but costs tokens and relies on agent following instructions.

### Cluster C: "Layered Architecture" (Ideas 3, 10, 12)
Multiple levels of memory with different latency/cost tradeoffs. Optimal information density but higher implementation complexity.

### Cluster D: "Background Intelligence" (Ideas 4, 6, 11)
External processes handle memory management. Agent is unaware. Maximum decoupling but requires running daemons.

---

## Recommendation

Top 3 for `/debate`:

1. **Idea 1+5+9 combined: "Hook Pipeline with Structured Markers"** — The full lifecycle via hooks: PreUserMessage injects memory, agent writes `<memory>` markers, PostAssistantMessage extracts and stores. Conversation lifecycle hooks handle session storage. **This is the sweet spot of automation + token economy + existing infrastructure.**

2. **Idea 3: "Cache Hierarchy"** — L1 in CLAUDE.md (free), L2 at session start (hook), L3 lazy MCP. This is the architecture *around* the hook pipeline. Debate whether L1 generation is worth the complexity.

3. **Idea 2+7 as fallback: "MCP + Skills"** — Start with simple MCP registration and skills as the MVP. Can be built in hours. Use as baseline while building the hook pipeline.

## Suggested Implementation Order

1. Idea 2 (register MCP, add CLAUDE.md instructions) — immediate value
2. Ideas 5+9 (structured markers + lifecycle hooks) — automation
3. Idea 8 (user message as query) + Idea 3's L1 layer — full pipeline
4. Ideas 11, 6 — advanced features

---

## Debate Results

**VERDICT: GO with scope reduction** | **CONFIDENCE: 75%** | **ROUNDS: 1 (converged)**

### Decision

Do Phase 1 only. Drop Phases 2, 3, 4 from the plan until data justifies them.

1. **Register MCP server** in `~/.claude/settings.json` — 9 tools are built and ready
2. **Add instructions to CLAUDE.md** — call `memory_get_facts` at session start, `memory_remember` at session end, `memory_store_conversation` for significant sessions
3. **Prototype one PostAssistantMessage hook** — calls `memory_store_conversation` with session JSONL path. Automatic ingestion via existing pipeline, zero agent cooperation needed
4. **Measure compliance** for 2 weeks before building anything else

### Surviving Arguments

| Argument | Source | Strength |
|----------|--------|----------|
| MCP server already built — Phase 1 is near-zero cost | Advocate | STRONG |
| Single PostAssistantMessage hook > XML marker extraction | Explorer | STRONG |
| `<memory>` XML markers are fragile prompt engineering | Adversary | STRONG |
| Phase 3 auto-gen inverts trust (machine writes agent's ground truth) | Adversary | STRONG |
| Phase 4 already implemented better via agent-in-the-loop `memory_store_conversation` | Feasibility | STRONG |
| 80/20 is MCP registration + CLAUDE.md instructions | Simplicity | STRONG |

### Dead Arguments

| Argument | Killed by |
|----------|-----------|
| XML markers + regex extraction | Explorer's hook approach supersedes |
| L1/L2/L3 cache hierarchy | Aesthetic alignment ≠ functional argument; over-engineering |
| Do nothing, fix retrieval first | Chicken-and-egg: need real data to improve retrieval |

### Key Trade-offs

| Gain | Lose |
|------|------|
| Cross-session memory starts accumulating immediately | No automation — depends on agent following CLAUDE.md |
| Zero new Clojure code | Some sessions produce no memory records |
| Existing 9 MCP tools exercised under real conditions | No L1/L2/L3 cache — retrieval costs tokens |
| Failure modes are visible and obvious | No background synthesis or compaction |

### Conditions

- **Go** if Datomic runs as part of normal dev workflow, or MCP server gets graceful fallback when DB is down
- **Reconsider** if agent compliance with `memory_remember` < 50% after 2 weeks — CLAUDE.md alone insufficient, PostAssistantMessage hook becomes mandatory
- **Pivot** if PostAssistantMessage hooks fire per-turn (not per-session) — changes chunking architecture

### Unresolved Questions

1. **Success metric undefined.** Propose: "agent references a past decision unprompted" or "avoids repeating a solved problem"
2. **Datomic availability.** MCP server crashes if DB down, no retry logic
3. **Tag quality over time.** Agent assigns tags during `memory_remember` — without normalization, tags diverge across sessions
4. **Security model.** Blob storage contains raw conversations, access model undefined

### Implementation Order

1. `~/.claude/settings.json` — add MCP server entry
2. CLAUDE.md — add memory usage instructions
3. Prototype PostAssistantMessage hook — test hook semantics first
4. Use for 2 weeks, measure compliance and retrieval quality
5. Decide on automation (Phase 2) based on data, not prediction
