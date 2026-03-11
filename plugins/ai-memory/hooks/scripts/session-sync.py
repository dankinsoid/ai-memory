#!/usr/bin/env python3
# @ai-generated(solo)
# Stop hook: syncs conversation transcript delta to server.
#
# Reads JSONL transcript, computes delta since last sync,
# POSTs to /api/session/sync, saves state (last_uuid + chunk_bytes).
#
# State file: ~/.claude/hooks/state/{session-id}.json
#   {"last_uuid": "...", "chunk_bytes": 12345}
#
# Env-var toggles (set any to disable):
#   AI_MEMORY_DISABLED=1     — master switch (all hooks)
#   AI_MEMORY_NO_WRITE=1     — disable all writes
#   AI_MEMORY_NO_SESSIONS=1  — disable session-specific features

import json
import os
import subprocess
import sys
from pathlib import Path
from urllib import request as urllib_request
from urllib.error import URLError

API_TOKEN = os.environ.get("AI_MEMORY_TOKEN")


def git_sh(cwd: str, *args: str) -> str | None:
    """Run git command in cwd, return trimmed stdout or None on failure."""
    try:
        result = subprocess.run(
            ["git", "-C", cwd, *args],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            out = result.stdout.strip()
            return out or None
    except Exception:
        pass
    return None


def git_project_name(cwd: str) -> str | None:
    """Extract repo name from git remote URL."""
    if not cwd:
        return None
    url = git_sh(cwd, "remote", "get-url", "origin")
    if url:
        return url.rstrip("/").removesuffix(".git").split("/")[-1].split(":")[-1]
    return None


def git_context(cwd: str) -> dict | None:
    """Collect git context (branch, commit, remote) for the current directory.
    Returns None if no git commit found.
    """
    if not cwd:
        return None
    commit = git_sh(cwd, "rev-parse", "--short", "HEAD")
    if not commit:
        return None
    ctx: dict = {"end_commit": commit}
    branch = git_sh(cwd, "rev-parse", "--abbrev-ref", "HEAD")
    if branch and branch != "HEAD":
        ctx["branch"] = branch
    remote = git_sh(cwd, "remote", "get-url", "origin")
    if remote:
        ctx["remote"] = remote
    return ctx


def derive_project(cwd: str) -> str | None:
    """Derive project name from git remote, falling back to directory name."""
    return git_project_name(cwd) or (cwd.rstrip("/").split("/")[-1] if cwd else None)


def find_transcript(session_id: str) -> Path | None:
    """Search ~/.claude/projects recursively for the session JSONL file."""
    projects_dir = Path.home() / ".claude" / "projects"
    if not projects_dir.exists():
        return None
    matches = list(projects_dir.glob(f"**/{session_id}.jsonl"))
    return matches[0] if matches else None


def parse_jsonl(path: Path) -> list[dict]:
    """Parse a JSONL file, silently skipping malformed lines."""
    entries = []
    for line in path.read_text().splitlines():
        try:
            entries.append(json.loads(line))
        except Exception:
            pass
    return entries


def extract_messages(entries: list[dict]) -> list[dict]:
    """Extract user/assistant messages (non-meta) from transcript entries."""
    result = []
    for e in entries:
        if e.get("type") in ("user", "assistant") and not e.get("isMeta"):
            result.append({
                "uuid": e.get("uuid"),
                "timestamp": e.get("timestamp"),
                "type": e.get("type"),
                "role": (e.get("message") or {}).get("role"),
                "content": (e.get("message") or {}).get("content"),
            })
    return result


def delta_messages(entries: list[dict], last_uuid: str | None) -> list[dict]:
    """Return messages after last_uuid, or all messages if last_uuid not found."""
    all_msgs = extract_messages(entries)
    if not last_uuid:
        return all_msgs
    # Find position of last_uuid in full entry list (not just messages)
    uuids = [e.get("uuid") for e in entries]
    if last_uuid in uuids:
        idx = uuids.index(last_uuid)
        return extract_messages(entries[idx + 1:])
    # last_uuid not found — return all (transcript may have been rotated)
    return all_msgs


def post_json(url: str, payload: dict) -> dict | None:
    """POST JSON payload, return parsed response or None on error."""
    headers = {"Content-Type": "application/json"}
    if API_TOKEN:
        headers["Authorization"] = f"Bearer {API_TOKEN}"
    body = json.dumps(payload).encode()
    req = urllib_request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib_request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read())
    except Exception as e:
        print(f"session-sync: POST failed: {e}", file=sys.stderr)
        return None


def main() -> None:
    if any(os.environ.get(v) for v in ("AI_MEMORY_DISABLED", "AI_MEMORY_NO_WRITE", "AI_MEMORY_NO_SESSIONS")):
        sys.exit(0)

    data = json.loads(sys.stdin.read())
    session_id = data.get("session_id")
    cwd = data.get("cwd", "")
    base_url = os.environ.get("AI_MEMORY_URL", "http://localhost:8080")

    if not session_id:
        sys.exit(0)

    state_dir = Path.home() / ".claude" / "hooks" / "state"
    state_dir.mkdir(parents=True, exist_ok=True)
    state_file = state_dir / f"{session_id}.json"

    transcript_path = data.get("transcript_path")
    transcript = Path(transcript_path) if transcript_path else find_transcript(session_id)
    if not transcript or not transcript.exists():
        sys.exit(0)

    state: dict = {}
    if state_file.exists():
        try:
            state = json.loads(state_file.read_text())
        except Exception:
            pass

    last_uuid = state.get("last_uuid")
    entries = parse_jsonl(transcript)
    messages = delta_messages(entries, last_uuid)
    project = derive_project(cwd)
    git = git_context(cwd)

    if not messages:
        sys.exit(0)

    payload: dict = {"session_id": session_id, "cwd": cwd, "messages": messages}
    if project:
        payload["project"] = project
    if git:
        payload["git"] = git

    resp_body = post_json(f"{base_url}/api/session/sync", payload)
    chunk_bytes = (resp_body or {}).get("current_chunk_size", 0)

    last_msg_uuid = messages[-1].get("uuid") if messages else last_uuid
    state_file.write_text(json.dumps({"last_uuid": last_msg_uuid, "chunk_bytes": chunk_bytes}))

    # prev-session cache is written by session-end.py (SessionEnd/clear only).
    # Stop hook must NOT write it — parallel sessions would overwrite each other.


if __name__ == "__main__":
    main()
