#!/usr/bin/env python3
from __future__ import annotations
# @ai-generated(solo)
# UserPromptSubmit hook: reminds agent about session metadata updates.
#
# Reminder types (mutually exclusive, checked in priority order):
# 1. Compact urgent — context >= 100K tokens and compact overdue: MUST run /save before responding
# 2. Chunk naming  — context crossed a 20K boundary: call memory_session with updated title/summary
# 3. Compact stale — 40K+ tokens since last /save: gentle reminder to run /save
# 4. Summary       — early turns: call memory_session with title/summary
#    (first turn: "before task" if prompt is long enough to infer topic, "after responding" otherwise)
#
# State per session (SQLite, JSON file fallback):
#   prompt_count, first_prompt_len, last_chunk_tokens, last_compact_tokens, context_tokens
#
# Compact tracking: mcp/server.py writes compact-saved-{session_id} flag on memory_session
# with compact; this hook reads and deletes the flag to reset last_compact_tokens.
#

import json
import subprocess
import sys
from pathlib import Path

# Allow importing from plugin lib/ package
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

# ---- Config ----

CHUNK_TOKEN_STEP = 20_000    # create a new chunk every N tokens of context growth
SHORT_PROMPT_LEN = 20        # prompts shorter than this are likely greetings
SUMMARY_REMIND_TURNS = 3     # remind about summary for this many early turns
COMPACT_STALE_TOKENS = 40_000   # tokens since last compact before gentle /save reminder
COMPACT_URGENT_TOKENS = 100_000 # total context tokens before urgent /save reminder


def git_project_name(cwd: str) -> str | None:
    """Extract repo name from git remote URL."""
    if not cwd:
        return None
    try:
        result = subprocess.run(
            ["git", "-C", cwd, "remote", "get-url", "origin"],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            url = result.stdout.strip().rstrip("/").removesuffix(".git")
            return url.split("/")[-1].split(":")[-1]
    except Exception:
        pass
    return None


def derive_project(cwd: str) -> str | None:
    """Derive project name from git remote, falling back to directory name."""
    return git_project_name(cwd) or (cwd.rstrip("/").split("/")[-1] if cwd else None)


def get_context_tokens(transcript_path: str | None) -> int | None:
    """Extract total token usage from last assistant message in transcript JSONL.
    Reads last 100 lines to find the most recent usage entry.
    Returns None if transcript unavailable or unreadable.
    """
    if not transcript_path:
        return None
    p = Path(transcript_path)
    if not p.exists():
        return None
    try:
        lines = p.read_text().splitlines()
        for line in reversed(lines[-100:]):
            try:
                entry = json.loads(line)
                usage = (entry.get("message") or {}).get("usage")
                if usage:
                    return (
                        usage.get("input_tokens", 0)
                        + usage.get("cache_read_input_tokens", 0)
                        + usage.get("cache_creation_input_tokens", 0)
                    )
            except Exception:
                pass
    except Exception:
        pass
    return None



def _is_llm_enabled() -> bool:
    """Check if LLM auto-digest is enabled."""
    try:
        from lib.config import llm_cfg
        return llm_cfg.enabled
    except Exception:
        return False


def main() -> None:
    data = json.loads(sys.stdin.read())
    session_id = data.get("session_id")
    prompt = data.get("prompt", "") or ""
    transcript = data.get("transcript_path")
    cwd = data.get("cwd", "")
    project_name = derive_project(cwd)

    if not session_id:
        sys.exit(0)

    llm_on = _is_llm_enabled()

    # Load or initialize state from SQLite, with JSON file fallback
    reminder_key = f"{session_id}-reminder"
    reminder_state: dict = {}
    try:
        from lib.db import get_state
        raw = get_state(reminder_key)
        if raw:
            reminder_state = json.loads(raw)
    except Exception:
        # Fallback: legacy JSON file
        state_dir = Path.home() / ".claude" / "hooks" / "state"
        reminder_file = state_dir / f"{session_id}-reminder.json"
        if reminder_file.exists():
            try:
                reminder_state = json.loads(reminder_file.read_text())
            except Exception:
                pass

    prompt_count = reminder_state.get("prompt_count", 0) + 1
    first_prompt_len = reminder_state.get("first_prompt_len") or len(prompt)
    last_chunk_tokens = reminder_state.get("last_chunk_tokens", 0)
    last_compact_tokens = reminder_state.get("last_compact_tokens", 0)

    context_tokens = get_context_tokens(transcript) or 0

    # Reset after /clear or /compact (tokens dropped below last boundary)
    if context_tokens < last_chunk_tokens:
        last_chunk_tokens = (context_tokens // CHUNK_TOKEN_STEP) * CHUNK_TOKEN_STEP
    if context_tokens < last_compact_tokens:
        last_compact_tokens = 0

    current_bucket = (context_tokens // CHUNK_TOKEN_STEP) * CHUNK_TOKEN_STEP
    need_chunk = (current_bucket > last_chunk_tokens) and (prompt_count > 1)

    # Check if compact was saved since last turn (flag written by MCP memory_session)
    try:
        from lib.db import get_state, delete_state
        if get_state(f"compact-saved-{session_id}"):
            last_compact_tokens = context_tokens
            delete_state(f"compact-saved-{session_id}")
    except Exception:
        pass

    tokens_since_compact = context_tokens - last_compact_tokens
    need_compact_urgent = (
        context_tokens >= COMPACT_URGENT_TOKENS
        and tokens_since_compact >= COMPACT_STALE_TOKENS
        and prompt_count > 1
    )
    need_compact_stale = (
        not need_compact_urgent
        and tokens_since_compact >= COMPACT_STALE_TOKENS
        and prompt_count > 3
    )

    new_state = {
        "prompt_count": prompt_count,
        "first_prompt_len": first_prompt_len,
        "last_chunk_tokens": current_bucket if need_chunk else last_chunk_tokens,
        "last_compact_tokens": last_compact_tokens,
        "context_tokens": context_tokens,
    }

    # When LLM is on, skip summary/chunk memory_session reminders —
    # the Stop hook handles auto-digest. Keep compact/save reminders.
    need_summary = False
    if not llm_on:
        need_summary = (prompt_count == 1) or (
            prompt_count <= SUMMARY_REMIND_TURNS and first_prompt_len < SHORT_PROMPT_LEN
        )

    # Set PreToolUse fallback flag on first prompt (only when LLM is off)
    if not llm_on and prompt_count == 1:
        try:
            from lib.db import set_state
            set_state(
                f"session-needs-init-{session_id}",
                json.dumps({"project": project_name or ""}),
            )
        except Exception:
            pass

    # Persist state
    try:
        from lib.db import set_state
        set_state(reminder_key, json.dumps(new_state))
    except Exception:
        # Fallback: legacy JSON file
        state_dir = Path.home() / ".claude" / "hooks" / "state"
        state_dir.mkdir(parents=True, exist_ok=True)
        reminder_file = state_dir / f"{session_id}-reminder.json"
        reminder_file.write_text(json.dumps(new_state))

    # Build output message
    parts: list[str] = []

    if need_compact_urgent:
        k = context_tokens // 1000
        parts.append(
            f'IMPORTANT: Context is large (~{k}K tokens) and compact is overdue. '
            f'You MUST run /save BEFORE responding to this message to avoid losing context on auto-compact.'
        )
    elif not llm_on and need_chunk:
        k = context_tokens // 1000
        session_part = f', project: "{project_name}"' if project_name else ""
        parts.append(
            f'Chunk ~{k}K tokens. Call memory_session with session_id: "{session_id}"'
            f'{session_part}, title, and summary.'
        )
    elif need_compact_stale:
        stale_k = tokens_since_compact // 1000
        parts.append(
            f'~{stale_k}K tokens without /save. Consider running /save to preserve compact notes.'
        )
    elif need_summary:
        session_part = f', project: "{project_name}"' if project_name else ""
        if prompt_count == 1:
            # Long first message → topic is clear, register session upfront;
            # short message (greeting) → respond first, then register.
            if len(prompt) >= SHORT_PROMPT_LEN:
                parts.append(
                    f'IMPORTANT: You MUST call memory_session with session_id: "{session_id}"'
                    f'{session_part}, title, summary, and tags BEFORE you start working on the task.'
                    " This is required for every conversation — do not skip it."
                    " Summary must describe the session intent, not repeat the user's message verbatim."
                )
            else:
                parts.append(
                    f'IMPORTANT: After responding to this first message, you MUST call memory_session with session_id: "{session_id}"'
                    f'{session_part}, title, summary, and tags.'
                    " This is required for every conversation — do not skip it."
                    " Summary must describe the session intent, not repeat the user's message verbatim."
                )
        else:
            parts.append(
                f'Call memory_session with session_id: "{session_id}"'
                f'{session_part}, title, summary, and tags.'
            )

    if parts:
        print(" ".join(parts))


if __name__ == "__main__":
    main()
