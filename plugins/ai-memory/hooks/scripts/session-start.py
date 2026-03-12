#!/usr/bin/env python3
# @ai-generated(solo)
# SessionStart hook: auto-loads memory context into the session.
#
# Fires on: startup (skips resume).
# Reads facts and sessions directly from file-based storage (no HTTP server needed).
# Outputs formatted context to stdout, which becomes a system-reminder visible to the agent.
#
# Loads facts sorted by tag priority per scope (universal, project):
#   1. critical-rule  2. rule  3. remaining (sorted by date)
#

from __future__ import annotations

import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path

# ---- Storage import ----
# lib/ package lives at the plugin root (three levels up from this script)
_PLUGIN_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(_PLUGIN_ROOT))

from lib import storage  # noqa: E402

# ---- Config ----


UNIVERSAL_LIMIT = 5
PROJECT_LIMIT = 10
SESSION_LIMIT = 4

RULE_TAG = "rule"
RULE_PREFIX = "[rule] "


# ---- Git helpers ----

def git_project_name(cwd: str) -> str | None:
    """Extract repo name from git remote URL, or None if unavailable."""
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


# ---- Git context persistence ----

def _git_head_and_branch(cwd: str) -> tuple[str | None, str | None]:
    """Return (short_commit, branch_name) for the repo at cwd.

    Args:
        cwd: working directory inside a git repo

    Returns:
        Tuple of (commit_short_sha, branch_name). Either may be None.
    """
    commit = branch = None
    try:
        r = subprocess.run(
            ["git", "-C", cwd, "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True,
        )
        if r.returncode == 0:
            commit = r.stdout.strip()
    except Exception:
        pass
    try:
        r = subprocess.run(
            ["git", "-C", cwd, "branch", "--show-current"],
            capture_output=True, text=True,
        )
        if r.returncode == 0:
            branch = r.stdout.strip() or None  # empty on detached HEAD
    except Exception:
        pass
    return commit, branch


def _save_git_context(cwd: str, session_id: str) -> None:
    """Persist branch + start commit to state DB for session-sync to read later.

    Args:
        cwd: working directory
        session_id: current session UUID
    """
    commit, branch = _git_head_and_branch(cwd)
    if not commit and not branch:
        return
    payload = {}
    if branch:
        payload["branch"] = branch
    if commit:
        payload["commit_start"] = commit
    try:
        from lib.db import set_state
        set_state(f"git-context-{session_id}", json.dumps(payload))
    except Exception:
        pass  # non-critical — session works without git context


# ---- Formatting ----

def _is_rule(fact: dict) -> bool:
    """True if the fact has the 'rule' tag (path-derived or explicit)."""
    return RULE_TAG in fact.get("tags", [])


def _content_first_line(content: str) -> str:
    """Extract the first non-front-matter non-empty line from a fact file content."""
    in_fm = False
    for i, line in enumerate(content.splitlines()):
        if i == 0 and line.strip() == "---":
            in_fm = True
            continue
        if in_fm:
            if line.strip() == "---":
                in_fm = False
            continue
        stripped = line.strip()
        if stripped:
            return stripped
    return ""


def format_fact(fact: dict) -> str:
    """Format a single fact as a markdown list item, prefixed with [rule] if applicable."""
    prefix = RULE_PREFIX if _is_rule(fact) else ""
    text = _content_first_line(fact.get("content", ""))
    return f"- {prefix}{text}"


def format_session_line(sess: dict) -> str:
    """Format a session record as a markdown list item."""
    title = sess.get("title", "(no title)")
    summary = sess.get("summary", "")
    date_str = sess.get("date", "")
    path = sess.get("path", "")

    # Extract blob dir from path: e.g. "projects/foo/sessions/2026-03-11 Title.md"
    # → use stem as blob dir name hint for /load
    stem = Path(path).stem if path else ""

    line = f"- **{title}**"
    if date_str:
        line += f" ({date_str})"
    if summary:
        line += f" — {summary}"
    if stem:
        line += f" [blob: {stem}]"
    return line


def format_timestamp() -> str:
    """Return current local timestamp string."""
    return datetime.now().strftime("%Y-%m-%d %H:%M (%a)")


# ---- Main ----

def main() -> None:
    data = json.loads(sys.stdin.read())
    session_id = data.get("session_id", "")
    cwd = data.get("cwd", "")

    # Skip resume — context already present
    if data.get("source") == "resume":
        sys.exit(0)

    # Reconcile filesystem → SQLite index in a detached background process.
    # The hook proceeds immediately using whatever index state exists from
    # the previous session.  The background reindex ensures fresh data for
    # subsequent MCP tool calls within this session.
    try:
        subprocess.Popen(
            [sys.executable, "-c",
             f"import sys; sys.path.insert(0, {str(_PLUGIN_ROOT)!r}); "
             "from lib.db import reindex; from lib.storage import get_base_dir, get_sessions_base_dir; "
             "reindex(get_base_dir(), get_sessions_base_dir())"],
            start_new_session=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        pass  # index is a cache — proceed with filescan fallback if it fails

    project_name = derive_project(cwd)
    sections: list[str] = []

    # ---- Universal facts ----
    u_facts = storage.search_facts(
        tags=["universal"],
        exclude_tags=["session"],
        sort_by="date",
        limit=UNIVERSAL_LIMIT,
    )
    if u_facts:
        rules = [f for f in u_facts if _is_rule(f)]
        others = [f for f in u_facts if not _is_rule(f)]
        parts: list[str] = []
        if rules:
            parts.append("### Rules\n" + "\n".join(format_fact(f) for f in rules))
        if others:
            parts.append("### Facts\n" + "\n".join(format_fact(f) for f in others))
        if parts:
            sections.append("## Universal\n" + "\n".join(parts))

    # ---- Project scope ----
    if project_name:
        ptag = f"project/{project_name}"
        proj_parts: list[str] = []

        # Recent sessions
        sessions = storage.search_sessions(
            project=project_name,
            sort_by="date",
            limit=SESSION_LIMIT,
        )
        if sessions:
            proj_parts.append(
                "### Sessions\n" + "\n".join(format_session_line(s) for s in sessions)
            )

        # Project facts
        p_facts = storage.search_facts(
            tags=[ptag],
            exclude_tags=["session"],
            sort_by="date",
            limit=PROJECT_LIMIT,
        )
        if p_facts:
            rules = [f for f in p_facts if _is_rule(f)]
            others = [f for f in p_facts if not _is_rule(f)]
            if rules:
                proj_parts.append("### Rules\n" + "\n".join(format_fact(f) for f in rules))
            if others:
                proj_parts.append("### Facts\n" + "\n".join(format_fact(f) for f in others))

        if proj_parts:
            sections.append(f"## Project: {project_name}\n" + "\n".join(proj_parts))

    # Save git context (branch + start commit) for session-sync to pick up later
    if cwd and session_id:
        _save_git_context(cwd, session_id)

    timestamp = format_timestamp()
    meta = f"session_id: {session_id}" if session_id else ""
    if sections or meta:
        header = "# Memory Context\n"
        if meta:
            header += f"\n{meta}\n"
        if sections:
            header += "\n" + "\n\n".join(sections)
        print(header + "\n\n---\n" + timestamp)


if __name__ == "__main__":
    main()
