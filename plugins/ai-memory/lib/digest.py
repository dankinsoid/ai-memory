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

# ---------------------------------------------------------------------------
# Dataclasses
# ---------------------------------------------------------------------------


@dataclass
class SessionDigest:
    """Structured LLM output for session metadata."""

    title: str           # 3-8 words
    summary: str         # 1-3 sentences, full session arc
    tags: list[str]      # topic tags for search/rule-loading
    compact: str         # detailed notes for /load recovery
    search_tags: list[str]  # broader tags for rule search


@dataclass
class SessionDigestLight:
    """LLM output without compact — used when agent compact is still fresh."""

    title: str
    summary: str
    tags: list[str]
    search_tags: list[str]


@dataclass
class DigestState:
    """Tracks incremental digest progress across Stop hook invocations."""

    last_byte_offset: int          # in rendered transcript text
    last_digest: SessionDigest | None
    last_msg_count: int
    agent_compact: str | None      # higher-trust compact from agent /save
    agent_compact_msg_count: int   # msg_count when agent compact was written


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DIGEST_DELTA_THRESHOLD = 2000  # chars (~500 tokens) minimum delta to trigger LLM
DIGEST_OVERLAP = 500           # chars overlap with previous window
EARLY_PROMPT_THRESHOLD = 200   # chars — minimum first message length for early digest
AGENT_COMPACT_FRESH_MSGS = 10  # messages after agent /save before LLM may overwrite compact


# ---------------------------------------------------------------------------
# Transcript rendering (LLM-friendly)
# ---------------------------------------------------------------------------

# System-injected XML tags to strip from user messages.
_SYSTEM_TAG_RE = re.compile(
    r"<(?:ide_opened_file|ide_selection|system-reminder|available-deferred-tools"
    r"|system_instruction|local-command-stdout|fast_mode_info)"
    r"[^>]*>.*?</(?:ide_opened_file|ide_selection|system-reminder|available-deferred-tools"
    r"|system_instruction|local-command-stdout|fast_mode_info)>",
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
            parts.append(f"{label}:\n" + "\n".join(chunks))

    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Prompts
# ---------------------------------------------------------------------------


def build_digest_prompt(
    delta_text: str,
    previous_digest: SessionDigest | None,
    project: str | None,
    agent_compact: str | None = None,
) -> str:
    """Build prompt for the Stop hook digest (incremental update).

    Args:
        delta_text: new transcript text since last digest
        previous_digest: previous digest result (for context continuity)
        project: project name for context
        agent_compact: higher-trust compact from agent /save, to preserve

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

    project_ctx = f" for project '{project}'" if project else ""

    return f"""You are a session metadata extractor{project_ctx}. Analyze this conversation transcript and produce structured metadata.
{prev_ctx}
New conversation content:
---
{delta_text}
---
{agent_compact_instruction}
Instructions:
- title: 3-8 words capturing the main topic/task. Update if the session evolved.
- summary: 1-3 sentences describing the full session arc so far (what was done, key decisions). No file/function names.
- tags: specific topic tags for this session (e.g. "refactoring", "auth", "testing", "deployment"). Do NOT include generic tags like "session" or "project/..." — those are added automatically. 3-7 tags.
- compact: detailed notes for recovering this session later. Include: what was done, current status, key decisions, next steps, important context. Be thorough but concise.
- search_tags: broader tags that would help find related rules/knowledge (superset of tags, may include technology names, patterns, etc.). 5-10 tags.

Produce the metadata as a JSON object."""


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
- title: 3-8 words capturing the likely main topic/task
- summary: 1-2 sentences describing the session intent. No file/function names.
- tags: specific topic tags (e.g. "refactoring", "auth", "testing"). Do NOT include "session" or "project/..." tags. 3-5 tags.
- compact: brief initial context from the user's request
- search_tags: broader tags for finding related rules/knowledge. 3-7 tags.

Produce the metadata as a JSON object."""


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

    # Agent compact still fresh? Skip compact generation entirely —
    # use lighter schema (no compact field) to save output tokens.
    agent_compact_fresh = (
        state.agent_compact is not None
        and (current_msg_count - state.agent_compact_msg_count) < AGENT_COMPACT_FRESH_MSGS
    )

    provider = get_provider()

    if agent_compact_fresh:
        prompt = build_digest_prompt(delta_text, state.last_digest, project)
        light = provider.complete(prompt, SessionDigestLight)
        digest = SessionDigest(
            title=light.title,
            summary=light.summary,
            tags=light.tags,
            compact=state.agent_compact,  # type: ignore[arg-type]
            search_tags=light.search_tags,
        )
    else:
        prompt = build_digest_prompt(
            delta_text, state.last_digest, project,
            agent_compact=state.agent_compact,
        )
        digest = provider.complete(prompt, SessionDigest)

    new_state = DigestState(
        last_byte_offset=len(full_text),
        last_digest=digest,
        last_msg_count=current_msg_count,
        agent_compact=state.agent_compact,
        agent_compact_msg_count=state.agent_compact_msg_count,
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
    return provider.complete(prompt, SessionDigest)


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
    return json.dumps({
        "last_byte_offset": state.last_byte_offset,
        "last_digest": digest_dict,
        "last_msg_count": state.last_msg_count,
        "agent_compact": state.agent_compact,
        "agent_compact_msg_count": state.agent_compact_msg_count,
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
    return DigestState(
        last_byte_offset=data.get("last_byte_offset", 0),
        last_digest=digest,
        last_msg_count=data.get("last_msg_count", 0),
        agent_compact=data.get("agent_compact"),
        agent_compact_msg_count=data.get("agent_compact_msg_count", 0),
    )
