#!/usr/bin/env python3
# @ai-generated(solo)
# UserPromptSubmit hook: reminds agent about session metadata updates.
#
# Two types of reminders:
# 1. Summary — on early turns, prompt agent to call memory_session with summary
# 2. Chunk naming — when context token usage crosses a fixed boundary (e.g. every 20K tokens)
#
# State file per session: ~/.claude/hooks/state/{session-id}-reminder.json
#   {"prompt_count": N, "first_prompt_len": N, "last_chunk_tokens": N}
#

import json
import subprocess
import sys
from pathlib import Path

# Allow importing from plugin lib/ package
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

# ---- Config ----

CHUNK_TOKEN_STEP = 20_000    # create a new chunk every N tokens of context growth
SHORT_PROMPT_LEN = 50        # prompts shorter than this are likely greetings
SUMMARY_REMIND_TURNS = 3     # remind about summary for this many early turns


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



def main() -> None:
    data = json.loads(sys.stdin.read())
    session_id = data.get("session_id")
    prompt = data.get("prompt", "") or ""
    transcript = data.get("transcript_path")
    cwd = data.get("cwd", "")
    project_name = derive_project(cwd)

    if not session_id:
        sys.exit(0)

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

    context_tokens = get_context_tokens(transcript) or 0

    # Reset after /clear or /compact (tokens dropped below last boundary)
    if context_tokens < last_chunk_tokens:
        last_chunk_tokens = (context_tokens // CHUNK_TOKEN_STEP) * CHUNK_TOKEN_STEP

    current_bucket = (context_tokens // CHUNK_TOKEN_STEP) * CHUNK_TOKEN_STEP
    need_chunk = (current_bucket > last_chunk_tokens) and (prompt_count > 1)

    new_state = {
        "prompt_count": prompt_count,
        "first_prompt_len": first_prompt_len,
        "last_chunk_tokens": current_bucket if need_chunk else last_chunk_tokens,
        "context_tokens": context_tokens,
    }

    # Summary reminder on early turns
    need_summary = (prompt_count == 1) or (
        prompt_count <= SUMMARY_REMIND_TURNS and first_prompt_len < SHORT_PROMPT_LEN
    )

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

    if need_chunk:
        k = context_tokens // 1000
        session_part = f', project: "{project_name}"' if project_name else ""
        parts.append(
            f'Chunk ~{k}K tokens. Call memory_session with session_id: "{session_id}"'
            f'{session_part}, chunk_title, title, and summary.'
        )
    elif need_summary:
        session_part = f', project: "{project_name}"' if project_name else ""
        if prompt_count == 1:
            parts.append(
                f'After responding to this first message, call memory_session once with session_id: "{session_id}"'
                f'{session_part}, title, summary, and tags.'
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
