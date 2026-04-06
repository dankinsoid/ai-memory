# @ai-generated(guided)
"""Auto-summarization via LLM.

Extracts session metadata (title, summary, tags, compact) from conversation
transcripts using an LLM call. Decoupled from hooks — pure logic module.

When ``AI_MEMORY_LLM=true``, the Stop hook calls ``compute_digest`` after each
turn to keep the session file up-to-date without agent involvement.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Optional

from .tags import normalize_tags

# ---------------------------------------------------------------------------
# Dataclasses
# ---------------------------------------------------------------------------


@dataclass
class Fact:
    """An atomic statement extracted from user messages."""

    text: str         # 1-2 sentences preserving exact meaning
    importance: int   # 0-100 continuous scale


@dataclass
class SessionDigest:
    """Structured LLM output for session metadata."""

    title: str           # 3-8 words
    summary: str         # 1-3 sentences, full session arc
    tags: list[str]      # topic tags for search/rule-loading
    compact: str         # detailed notes for /load recovery
    search_tags: list[str]  # broader tags for rule search


@dataclass
class SessionDigestWithFacts:
    """LLM output with fact extraction (parallel lists for structured output)."""

    title: str
    summary: str
    tags: list[str]
    compact: str
    search_tags: list[str]
    fact_texts: list[str]        # fact text, 1-2 sentences each
    fact_importances: list[int]  # 0-100, same order as fact_texts


@dataclass
class SessionDigestLight:
    """LLM output without compact — used when agent compact is still fresh."""

    title: str
    summary: str
    tags: list[str]
    search_tags: list[str]


@dataclass
class SessionDigestLightWithFacts:
    """LLM output without compact, with fact extraction."""

    title: str
    summary: str
    tags: list[str]
    search_tags: list[str]
    fact_texts: list[str]
    fact_importances: list[int]


@dataclass
class ConsolidatedFacts:
    """LLM output for fact consolidation — compressed list of facts."""

    fact_texts: list[str]
    fact_importances: list[int]


@dataclass
class DigestState:
    """Tracks incremental digest progress across Stop hook invocations."""

    last_byte_offset: int          # in rendered transcript text
    last_digest: SessionDigest | None
    last_msg_count: int
    agent_compact: str | None      # higher-trust compact from agent /save
    agent_compact_msg_count: int   # msg_count when agent compact was written
    facts: list[Fact]              # accumulated facts across digests
    digests_since_consolidation: int = 0  # digest calls since last fact consolidation


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DIGEST_DELTA_THRESHOLD = 2000  # chars (~500 tokens) minimum delta to trigger LLM
DIGEST_OVERLAP = 500           # chars overlap with previous window
AGENT_COMPACT_FRESH_MSGS = 10  # messages after agent /save before LLM may overwrite compact
COMPACT_MIN_MSGS = 6           # minimum messages before generating compact (short sessions don't need it)
COMPACT_MIN_TRANSCRIPT = 8000  # chars — minimum transcript size to generate compact (short sessions don't need it)
USER_MSG_MIN_CHARS = 20        # skip user messages shorter than this in LLM transcript (slash commands, greetings)

# Compact writing spec — mirrored in skills/save/SKILL.md for agent use.
# If updating, keep both in sync.
FACTS_RECENT_COUNT = 15    # max facts to pass to LLM prompt for dedup
FACTS_LOAD_MAX = 50        # max facts to show in /load
LOAD_TOTAL_BUDGET = 5000   # shared chars budget for compact + facts + transcript tail in /load
FACTS_CONSOLIDATE_CHARS = 2000  # total chars across all facts before triggering consolidation
FACTS_CONSOLIDATE_COOLDOWN = 5  # minimum digest calls between consolidations

COMPACT_SPEC = """\
Compact is a handoff to a future agent — write what they'd need to continue \
this work with no other context. Concrete and actionable, not polished.

Format: a structured header, then a compression-gradient narrative \
(early work compressed, recent work in full detail).

Header:
  Goal: <why this session exists — intent, not just topic>
  Status: in-progress | completed | blocked
  Next: <immediate next action, one line>

Body:
  - Overview sentence
  - Early session — high compression: 1-2 sentences per topic
  - Recent work — full detail: what was tried, what worked, what didn't
  - Current state: what is working, what is broken, what is half-done
  - Dead ends: approach tried → why it failed (if any)
  - User requirements: direct quotes of constraints/preferences from the user

Rules:
  - Substance, not mechanics — "chose X over Y because Z" matters; which files were edited does not
  - Dead ends — rejected approaches and why, so the next agent doesn't retry them
  - User requirements — preserve exact wording, don't paraphrase
  - Length: keep total compact under 2000 characters; if space is tight, prioritize current state and recent decisions over early history"""

FACTS_SPEC = """\
FORBIDDEN — never extract facts from Assistant messages. The assistant's \
technical explanations, analysis, and findings are NOT facts to store. \
Only the human user's own statements matter.

Before adding any fact, verify: "Is this something the USER typed?" \
If you cannot point to a specific User: message that contains this claim, \
discard it.

Return an EMPTY list when the user said nothing worth remembering. \
Most conversations produce 0-2 user facts — that is normal.

A fact is a DURABLE statement that will be useful in future sessions. \
Skip anything ephemeral:
  - Questions ("did you get X?", "why is Y?") — asking is not stating
  - Situational observations ("it's slow", "it hangs") — transient symptoms
  - Task instructions to the assistant ("try X", "run Y") — one-time commands

Extract only what reveals the user's lasting intent, decisions, or knowledge:
  - Decisions or preferences ("let's go with X", "chose X over Y")
  - Corrections ("no, not that", "actually", "wrong")
  - Requirements or constraints ("must", "need", "can't use X")
  - Confirmations of non-obvious approaches ("yes exactly", "that's right")

Example — given this exchange:
  User: it keeps crashing on startup
  Assistant: The crash is caused by a null pointer in init(). Fixed it.
  User: great, also make sure we always validate config before init
CORRECT facts: ["User requires config validation before init"]
WRONG facts: ["Crash caused by null pointer in init"] — assistant said this

Importance scale (0-100):
  - 30-50: context, background, minor preferences
  - 60-80: decisions, confirmed approaches
  - 85-100: critical requirements, hard constraints, corrections

Do NOT extract:
  - Assistant's analysis, findings, or explanations
  - Technical details the assistant discovered
  - Conclusions the assistant reached

Do NOT duplicate facts already listed in "Previous facts"."""

FACTS_CONSOLIDATE_SPEC = """\
You are compressing a list of session facts. These facts capture user's \
nuances, requirements, explanations, corrections, and preferences — things \
that cannot be learned from code or git history.

Rules:
- Merge facts that form a decision chain into one dense fact \
(e.g. "tried X → didn't work → switched to Y because Z")
- Remove facts that are now redundant because a later fact supersedes them
- Keep every distinct user intent — compress, NEVER discard
- Preserve the user's voice — if the original was a quote, keep the quote
- Each fact should be ≤150 characters
- Importance: keep the highest importance from merged facts

Return the consolidated list. It must be strictly shorter (fewer total \
characters) than the input."""


# ---------------------------------------------------------------------------
# Transcript rendering (LLM-friendly)
# ---------------------------------------------------------------------------

# System-injected XML tags to strip from user messages.
_SYSTEM_TAGS = (
    "ide_opened_file|ide_selection|system-reminder|available-deferred-tools"
    "|system_instruction|local-command-stdout|local-command-caveat|fast_mode_info"
)
_SYSTEM_TAG_RE = re.compile(
    rf"<(?:{_SYSTEM_TAGS})[^>]*>.*?</(?:{_SYSTEM_TAGS})>",
    re.DOTALL,
)


def _strip_system_tags(text: str) -> str:
    """Remove system-injected XML tags and collapse excess blank lines."""
    cleaned = _SYSTEM_TAG_RE.sub("", text)
    return re.sub(r"\n{3,}", "\n\n", cleaned).strip()


def extract_llm_transcript(entries: list[dict]) -> str:
    """Render JSONL entries into compact LLM-friendly text.

    Format:
    - User/assistant text: included fully (system tags stripped)
    - tool_use: ``[Tool: name(input[:100])]``
    - tool_result: ``[Result: text[:200]]``
    - Thinking/system/images: skipped

    Args:
        entries: parsed JSONL entries from the session transcript

    Returns:
        Plain text transcript suitable for LLM consumption.
    """
    parts: list[str] = []

    for entry in entries:
        if entry.get("type") not in ("user", "assistant"):
            continue
        if entry.get("isMeta"):
            continue

        msg = entry.get("message") or {}
        role = msg.get("role")
        if not role:
            continue

        content = msg.get("content", "")
        label = "User" if role == "user" else "Assistant"
        chunks: list[str] = []

        if isinstance(content, str):
            text = _strip_system_tags(content)
            if text:
                chunks.append(text)
        elif isinstance(content, list):
            for block in content:
                if not isinstance(block, dict):
                    continue
                btype = block.get("type")
                if btype == "text":
                    text = _strip_system_tags(block.get("text", ""))
                    if text:
                        chunks.append(text)
                elif btype == "tool_use":
                    name = block.get("name", "?")
                    inp = json.dumps(block.get("input", {}), ensure_ascii=False)
                    if len(inp) > 100:
                        inp = inp[:100] + "…"
                    chunks.append(f"[Tool: {name}({inp})]")
                elif btype == "tool_result":
                    rc = block.get("content", "")
                    if isinstance(rc, list):
                        rc = " ".join(
                            b.get("text", "") for b in rc
                            if isinstance(b, dict) and b.get("type") == "text"
                        )
                    if isinstance(rc, str) and rc:
                        if len(rc) > 200:
                            rc = rc[:200] + "…"
                        chunks.append(f"[Result: {rc}]")

        if chunks:
            combined = "\n".join(chunks)
            # Skip user messages that are too short after cleanup — slash
            # commands like "/plugin" or greetings produce noise titles.
            if role == "user" and len(combined.strip()) < USER_MSG_MIN_CHARS:
                continue
            parts.append(f"{label}:\n" + combined)

    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Prompts
# ---------------------------------------------------------------------------


def build_digest_prompt(
    delta_text: str,
    previous_digest: SessionDigest | None,
    project: str | None,
    agent_compact: str | None = None,
    include_compact: bool = True,
    previous_facts: list[Fact] | None = None,
) -> str:
    """Build prompt for the Stop hook digest (incremental update).

    Args:
        delta_text: new transcript text since last digest
        previous_digest: previous digest result (for context continuity)
        project: project name for context
        agent_compact: higher-trust compact from agent /save, to preserve
        include_compact: whether to include compact instructions (False for
            SessionDigestLight when agent compact is fresh or session is short)
        previous_facts: accumulated facts from prior digests (last N for dedup)

    Returns:
        Complete prompt string for the LLM.
    """
    prev_ctx = ""
    if previous_digest:
        prev_ctx = (
            f"\n\nPrevious session state:\n"
            f"Title: {previous_digest.title}\n"
            f"Summary: {previous_digest.summary}\n"
            f"Tags: {', '.join(previous_digest.tags)}\n"
        )

    agent_compact_instruction = ""
    if agent_compact:
        agent_compact_instruction = (
            f"\n\nPrevious compact notes (written by the agent earlier in the session):\n"
            f"---\n{agent_compact}\n---\n"
            f"Use these as a starting point. Your compact should be a complete "
            f"replacement that incorporates this context plus everything new."
        )

    prev_facts_ctx = ""
    if previous_facts:
        recent = previous_facts[-FACTS_RECENT_COUNT:]
        lines = [f"- [{f.importance}] {f.text}" for f in recent]
        prev_facts_ctx = (
            f"\n\nPrevious facts (already extracted):\n"
            + "\n".join(lines)
        )

    project_ctx = f" for project '{project}'" if project else ""

    compact_line = ""
    if include_compact:
        compact_line = f"\n- compact:\n{COMPACT_SPEC}"

    return f"""You are a session metadata extractor{project_ctx}. Analyze this conversation transcript and produce structured metadata.
{prev_ctx}{prev_facts_ctx}
New conversation content:
---
{delta_text}
---
{agent_compact_instruction}
Instructions:
- title: 3-8 words capturing the main topic/task. Update if the session evolved. Return empty string "" if there is no clear topic yet (e.g. only greetings, slash commands, or system messages without an explicit user request or question).
- summary: 1-3 sentences describing the full session arc so far (what was done, key decisions). No file/function names.
- tags: specific topic tags for this session (e.g. "refactoring", "auth", "testing", "deployment"). Do NOT include generic tags like "session" or "project/..." — those are added automatically. 3-7 tags.{compact_line}
- search_tags: broader tags that would help find related rules/knowledge (superset of tags, may include technology names, patterns, etc.). 5-10 tags.
- fact_texts + fact_importances:
{FACTS_SPEC}

"""


def build_early_prompt(user_message: str, project: str | None) -> str:
    """Build prompt for first-prompt early extraction (UserPromptSubmit).

    Simpler than the full digest — just needs title, summary, tags from
    the user's first message.

    Args:
        user_message: the user's first message text
        project: project name for context

    Returns:
        Complete prompt string for the LLM.
    """
    project_ctx = f" for project '{project}'" if project else ""

    return f"""You are a session metadata extractor{project_ctx}. Based on the user's first message, predict the session metadata.

User's message:
---
{user_message}
---

Instructions:
- title: 3-8 words capturing the likely main topic/task. Return empty string "" if the message has no clear topic or intent (e.g. only a greeting, slash command, or pasted code without context).
- summary: 1-2 sentences describing the session intent. No file/function names.
- tags: specific topic tags (e.g. "refactoring", "auth", "testing"). Do NOT include "session" or "project/..." tags. 3-5 tags.
- search_tags: broader tags for finding related rules/knowledge. 3-7 tags.

"""


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _zip_facts(texts: list[str], importances: list[int]) -> list[Fact]:
    """Combine parallel fact lists from LLM response into Fact objects.

    If lists have mismatched lengths, pairs up to the shorter one.
    Clamps importance to 0-100.

    Args:
        texts: fact text strings from LLM
        importances: importance scores from LLM

    Returns:
        List of Fact objects.
    """
    return [
        Fact(text=t, importance=max(0, min(100, imp)))
        for t, imp in zip(texts, importances)
        if t.strip()
    ]


def _facts_total_chars(facts: list[Fact]) -> int:
    """Total character count across all fact texts."""
    return sum(len(f.text) for f in facts)


def _consolidate_facts(facts: list[Fact]) -> list[Fact]:
    """Compress accumulated facts via LLM to reduce total size.

    Merges decision chains, removes superseded facts, keeps all user intent.
    Returns a shorter list or the original if LLM fails.

    Args:
        facts: accumulated facts to consolidate

    Returns:
        Consolidated list of facts (strictly fewer total chars).
    """
    from .llm import get_provider

    lines = [f"- [{f.importance}] {f.text}" for f in facts]
    prompt = f"""{FACTS_CONSOLIDATE_SPEC}

Current facts ({len(facts)} items, {_facts_total_chars(facts)} chars):
{chr(10).join(lines)}
"""

    try:
        provider = get_provider()
        result = provider.complete(prompt, ConsolidatedFacts)
        consolidated = _zip_facts(result.fact_texts, result.fact_importances)
        # Only accept if actually shorter
        if consolidated and _facts_total_chars(consolidated) < _facts_total_chars(facts):
            return consolidated
        return facts
    except Exception:
        return facts


# ---------------------------------------------------------------------------
# Orchestrators
# ---------------------------------------------------------------------------


def compute_digest(
    entries: list[dict],
    state: DigestState,
    project: str | None,
    *,
    force: bool = False,
) -> tuple[SessionDigest, DigestState] | None:
    """Compute session digest from transcript entries.

    Renders the full transcript, checks if enough new content exists
    (delta >= DIGEST_DELTA_THRESHOLD), and calls the LLM for extraction.

    Args:
        entries: parsed JSONL entries
        state: current digest state (byte offset, previous digest)
        project: project name
        force: ignore delta threshold (for final digest on /clear)

    Returns:
        Tuple of (new digest, updated state) if LLM was called, or None
        if the delta was too small and force=False.

    Raises:
        LLMError: propagated from the LLM provider on failure.
    """
    from .llm import get_provider

    full_text = extract_llm_transcript(entries)
    delta_size = len(full_text) - state.last_byte_offset

    if not force and delta_size < DIGEST_DELTA_THRESHOLD:
        return None

    # Extract delta with overlap for context continuity
    overlap_start = max(0, state.last_byte_offset - DIGEST_OVERLAP)
    delta_text = full_text[overlap_start:]

    if not delta_text.strip():
        return None

    current_msg_count = len([
        e for e in entries
        if e.get("type") in ("user", "assistant") and not e.get("isMeta")
    ])

    agent_compact_fresh = (
        state.agent_compact is not None
        and (current_msg_count - state.agent_compact_msg_count) < AGENT_COMPACT_FRESH_MSGS
    )
    transcript_too_short = len(full_text) < COMPACT_MIN_TRANSCRIPT
    skip_compact = agent_compact_fresh or transcript_too_short
    # Facts are redundant when the full transcript fits in /load output
    skip_facts = transcript_too_short

    provider = get_provider()

    # Fall back to previous title when LLM returns empty (no clear topic yet)
    prev_title = (state.last_digest.title if state.last_digest else None) or "untitled session"

    if skip_compact and skip_facts:
        # Short session: transcript fits in /load — no compact, no facts needed
        prompt = build_digest_prompt(
            delta_text, state.last_digest, project, include_compact=False,
        )
        light = provider.complete(prompt, SessionDigestLight)
        digest = SessionDigest(
            title=light.title.strip() or prev_title,
            summary=light.summary,
            tags=normalize_tags(light.tags),
            compact=state.agent_compact or "",
            search_tags=normalize_tags(light.search_tags),
        )
        new_facts = []
    elif skip_compact:
        # Medium session: facts needed but compact skipped (agent compact fresh)
        prompt = build_digest_prompt(
            delta_text, state.last_digest, project, include_compact=False,
            previous_facts=state.facts or None,
        )
        light = provider.complete(prompt, SessionDigestLightWithFacts)
        digest = SessionDigest(
            title=light.title.strip() or prev_title,
            summary=light.summary,
            tags=normalize_tags(light.tags),
            compact=state.agent_compact or "",
            search_tags=normalize_tags(light.search_tags),
        )
        new_facts = _zip_facts(light.fact_texts, light.fact_importances)
    else:
        prompt = build_digest_prompt(
            delta_text, state.last_digest, project,
            agent_compact=state.agent_compact,
            previous_facts=state.facts or None,
        )
        raw = provider.complete(prompt, SessionDigestWithFacts)
        digest = SessionDigest(
            title=raw.title.strip() or prev_title,
            summary=raw.summary,
            tags=normalize_tags(raw.tags),
            compact=raw.compact,
            search_tags=normalize_tags(raw.search_tags),
        )
        new_facts = _zip_facts(raw.fact_texts, raw.fact_importances)

    merged_facts = list(state.facts) + new_facts

    # Consolidate facts when they exceed the char budget and cooldown has passed
    digests_since = state.digests_since_consolidation + 1
    needs_consolidation = (
        _facts_total_chars(merged_facts) > FACTS_CONSOLIDATE_CHARS
        and digests_since >= FACTS_CONSOLIDATE_COOLDOWN
    )
    if needs_consolidation:
        merged_facts = _consolidate_facts(merged_facts)
        digests_since = 0

    new_state = DigestState(
        last_byte_offset=len(full_text),
        last_digest=digest,
        last_msg_count=current_msg_count,
        agent_compact=state.agent_compact,
        agent_compact_msg_count=state.agent_compact_msg_count,
        facts=merged_facts,
        digests_since_consolidation=digests_since,
    )

    return digest, new_state


def compute_early_digest(
    user_message: str,
    project: str | None,
) -> SessionDigest:
    """Compute early digest from the user's first message.

    Called by UserPromptSubmit when the first message is long enough
    to infer the session topic.

    Args:
        user_message: the user's first message text
        project: project name

    Returns:
        SessionDigest with predicted metadata.

    Raises:
        LLMError: propagated from the LLM provider on failure.
    """
    from .llm import get_provider

    prompt = build_early_prompt(user_message, project)
    provider = get_provider()
    light = provider.complete(prompt, SessionDigestLight)
    return SessionDigest(
        title=light.title.strip() or "untitled session",
        summary=light.summary,
        tags=normalize_tags(light.tags),
        compact="",  # no compact for first message — too early
        search_tags=normalize_tags(light.search_tags),
    )


# ---------------------------------------------------------------------------
# State serialization helpers
# ---------------------------------------------------------------------------


def serialize_state(state: DigestState) -> str:
    """Serialize DigestState to JSON string for SQLite storage.

    Args:
        state: digest state to serialize

    Returns:
        JSON string.
    """
    digest_dict = None
    if state.last_digest:
        d = state.last_digest
        digest_dict = {
            "title": d.title,
            "summary": d.summary,
            "tags": d.tags,
            "compact": d.compact,
            "search_tags": d.search_tags,
        }
    facts_list = [
        {"text": f.text, "importance": f.importance}
        for f in state.facts
    ] if state.facts else []
    return json.dumps({
        "last_byte_offset": state.last_byte_offset,
        "last_digest": digest_dict,
        "last_msg_count": state.last_msg_count,
        "agent_compact": state.agent_compact,
        "agent_compact_msg_count": state.agent_compact_msg_count,
        "facts": facts_list,
        "digests_since_consolidation": state.digests_since_consolidation,
    })


def deserialize_state(raw: str) -> DigestState:
    """Deserialize DigestState from JSON string.

    Args:
        raw: JSON string from SQLite state table

    Returns:
        Reconstructed DigestState.
    """
    data = json.loads(raw)
    digest = None
    if data.get("last_digest"):
        d = data["last_digest"]
        digest = SessionDigest(
            title=d["title"],
            summary=d["summary"],
            tags=d["tags"],
            compact=d["compact"],
            search_tags=d["search_tags"],
        )
    facts = [
        Fact(text=f["text"], importance=f["importance"])
        for f in data.get("facts", [])
        if isinstance(f, dict) and "text" in f
    ]
    return DigestState(
        last_byte_offset=data.get("last_byte_offset", 0),
        last_digest=digest,
        last_msg_count=data.get("last_msg_count", 0),
        agent_compact=data.get("agent_compact"),
        agent_compact_msg_count=data.get("agent_compact_msg_count", 0),
        facts=facts,
        digests_since_consolidation=data.get("digests_since_consolidation", 0),
    )
