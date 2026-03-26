#!/usr/bin/env python3
# @ai-generated(guided)
"""UserPromptSubmit async hook: early LLM digest on first long prompt.

Creates a session file with title/summary/tags from the user's first
message, before the agent even responds. Covers the edge case where
the agent crashes before the first Stop hook fires.

Runs async — does not block the agent. Output is ignored (no stdout).
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

EARLY_PROMPT_THRESHOLD = 200  # chars — minimum first message length


def git_project_name(cwd: str) -> str | None:
    """Extract repo name from git remote URL."""
    if not cwd:
        return None
    try:
        result = subprocess.run(
            ["git", "-C", cwd, "remote", "get-url", "origin"],
            capture_output=True, text=True,
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


def main() -> None:
    data = json.loads(sys.stdin.read())
    session_id = data.get("session_id")
    prompt = data.get("prompt", "") or ""
    cwd = data.get("cwd", "")

    if not session_id:
        sys.exit(0)

    # Only on first prompt with enough content
    try:
        from lib.db import get_state
        raw = get_state(f"{session_id}-reminder")
        if raw:
            state = json.loads(raw)
            if state.get("prompt_count", 0) > 1:
                sys.exit(0)
    except Exception:
        pass

    # Strip system tags before length check — prompts with only
    # slash-command XML (e.g. /plugin) shouldn't trigger early digest.
    from lib.digest import _strip_system_tags
    prompt = _strip_system_tags(prompt)

    if len(prompt) < EARLY_PROMPT_THRESHOLD:
        sys.exit(0)

    try:
        from lib.config import llm_cfg
        if not llm_cfg.enabled:
            sys.exit(0)
    except Exception:
        sys.exit(0)

    try:
        from lib.digest import compute_early_digest
        from lib import storage

        project = derive_project(cwd)
        digest = compute_early_digest(prompt, project)

        auto_tags = ["session"]
        if project:
            auto_tags.append(f"project/{project}")
        auto_tags.extend(t for t in digest.tags if t not in auto_tags)

        storage.upsert_session(
            session_id=session_id,
            project=project,
            title=digest.title,
            summary=digest.summary,
            tags=auto_tags,
        )
    except Exception:
        pass  # Stop hook will handle it


if __name__ == "__main__":
    main()
