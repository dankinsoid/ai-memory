#!/usr/bin/env python3
# @ai-generated(solo)
# Load skill script: discovers session chain and outputs combined context.
#
# Usage:
#   python3 load-chain.py <session-id> [project]  # traverse continuation chain
#   python3 load-chain.py --blob <blob-dir>        # load specific session blob
#
# Chain mode: calls /api/session/chain to traverse the full chain.
# When [project] is provided, filters the fallback session query by project tag.
# Blob mode: directly reads compact.md from a specific blob dir.
#
# Content strategy: shows last N chars of conversation (concatenated chunks).
# If entire conversation fits in N chars, compact.md is skipped.
# If conversation exceeds N chars, compact.md is shown first, then the tail.

import json
import os
import sys
from urllib import request as urllib_request
from urllib.error import URLError

BASE_URL = os.environ.get("AI_MEMORY_URL", "http://localhost:8080")
API_TOKEN = os.environ.get("AI_MEMORY_TOKEN")
CONTEXT_CHARS = 4000  # ~1000 words — conversation tail for recovery


def auth_headers() -> dict:
    h = {"Content-Type": "application/json", "Accept": "application/json"}
    if API_TOKEN:
        h["Authorization"] = f"Bearer {API_TOKEN}"
    return h


def api_post(path: str, body: dict) -> dict | None:
    """POST JSON to the API, return parsed response or None on error."""
    data = json.dumps(body).encode()
    req = urllib_request.Request(BASE_URL + path, data=data, headers=auth_headers(), method="POST")
    try:
        with urllib_request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read())
    except Exception as e:
        print(f"API error: {e}", file=sys.stderr)
        return None


def read_blob_file(blob_dir: str, filename: str) -> str | None:
    """Read a file from a blob dir via /api/blobs/exec. Returns content or None."""
    result = api_post("/api/blobs/exec", {
        "blob_dir": blob_dir,
        "command": f"cat {filename} 2>/dev/null",
    })
    if result and result.get("exit-code", -1) == 0:
        stdout = result.get("stdout", "")
        return stdout if stdout.strip() else None
    return None


def read_conversation(blob_dir: str, max_chars: int) -> dict | None:
    """Read conversation from blob — all numbered chunks + _current.md in order.

    Args:
        blob_dir: blob directory identifier
        max_chars: maximum characters to return

    Returns:
        {"content": str, "total_size": int, "full": bool} or None if no files found.
    """
    result = api_post("/api/blobs/exec", {
        "blob_dir": blob_dir,
        "command": (
            'files=$(ls -1 *.md 2>/dev/null | grep -v compact.md | sort);'
            'if [ -z "$files" ]; then exit 1; fi;'
            'total=$(echo "$files" | xargs cat | wc -c);'
            'echo "$files";'
            'echo "---SIZE---";'
            'echo $total'
        ),
    })
    if not result or result.get("exit-code", -1) != 0:
        return None

    output = result.get("stdout", "")
    parts = output.split("---SIZE---\n", 1)
    if len(parts) < 2:
        return None

    files = [f for f in parts[0].strip().splitlines() if f.strip()]
    try:
        total_size = int(parts[1].strip())
    except (ValueError, IndexError):
        return None

    if not files or total_size <= 0:
        return None

    all_files = " ".join(files)
    full = total_size <= max_chars
    cmd = f"cat {all_files}" if full else f"cat {all_files} | tail -c {max_chars}"
    content_result = api_post("/api/blobs/exec", {"blob_dir": blob_dir, "command": cmd})
    if not content_result or content_result.get("exit-code", -1) != 0:
        return None

    return {
        "content": content_result.get("stdout", ""),
        "total_size": total_size,
        "full": full,
    }


def trim_to_line_boundary(content: str) -> str:
    """Trim content to start at first newline (avoids partial first line)."""
    idx = content.find("\n")
    return content[idx + 1:] if idx != -1 else content


def read_blob_meta(blob_dir: str) -> dict | None:
    """Read and parse meta.edn from a blob dir. Returns dict or None."""
    content = read_blob_file(blob_dir, "meta.edn")
    if not content:
        return None
    # Basic EDN map parsing: extract :key "value" pairs
    # Only handles simple flat maps with string/keyword values
    import re
    result = {}
    # Extract git sub-map
    git_match = re.search(r':git\s*\{([^}]*)\}', content)
    if git_match:
        git = {}
        for m in re.finditer(r':(\S+)\s+"([^"]*)"', git_match.group(1)):
            git[m.group(1)] = m.group(2)
        if git:
            result["git"] = git
    return result or None


def format_git_info(git: dict) -> str | None:
    """Format git dict as a compact string, or None if no useful info."""
    branch = git.get("branch")
    start = git.get("start-commit")
    end = git.get("end-commit")
    if start and end and start != end:
        commits = f"{start}..{end}"
    else:
        commits = end or start
    parts = [p for p in [branch, commits] if p]
    if not parts:
        return None
    return f"*git: {' @ '.join(parts)}*"


def print_blob_content(blob_dir: str) -> None:
    """Print session content from a blob.
    Small conversations: full transcript, no compact.md needed.
    Large conversations: compact.md summary + last N chars of transcript.
    """
    meta = read_blob_meta(blob_dir)
    if meta and meta.get("git"):
        git_line = format_git_info(meta["git"])
        if git_line:
            print(git_line)
            print()

    conv = read_conversation(blob_dir, CONTEXT_CHARS)
    if conv:
        if conv["full"]:
            # Entire conversation fits — show directly, skip compact
            print(conv["content"])
        else:
            # Large conversation — compact summary + tail
            compact = read_blob_file(blob_dir, "compact.md")
            if compact:
                print(compact)
                print()
                print("---")
                print()
            k = CONTEXT_CHARS // 1000
            total_k = conv["total_size"] // 1000
            print("## Conversation Tail")
            print()
            print(f"*(last ~{k}K of {total_k}K total)*")
            print()
            print(trim_to_line_boundary(conv["content"]))
    else:
        # No conversation files — try compact.md alone
        compact = read_blob_file(blob_dir, "compact.md")
        if compact:
            print(compact)
        else:
            print("*No content found.*")


def main() -> None:
    args = sys.argv[1:]
    flag = args[0] if args else None
    value = args[1] if len(args) > 1 else None

    if flag == "--blob":
        if not value:
            print("Usage: python3 load-chain.py --blob <blob-dir>")
            sys.exit(1)
        print("# Session Recovery")
        print()
        print_blob_content(value)

    elif flag:
        session_id = flag
        project = value  # optional 2nd arg for project isolation

        result = api_post("/api/session/chain", {
            "session_id": session_id,
            "strengthen": True,
        })
        chain = (result or {}).get("chain", [])

        if chain:
            latest = chain[0]
            older = chain[1:]
            print("# Session Chain Recovery")
            print()
            print(f"{len(chain)} previous session(s) in chain.")
            for session in older:
                print()
                print("---")
                print(f"## {session.get('content') or '(no summary)'}")
            # Most recent: load full blob content
            blob_dir = latest.get("blob-dir")
            if blob_dir:
                print()
                print("---")
                print(f"## {latest.get('content') or '(no summary)'}")
                print()
                print_blob_content(blob_dir)
            print()
            print("---")
            print("Continuation edge strengthened.")
        else:
            # No chain — show candidates for user to pick from
            tags = ["session"]
            if project:
                tags.append(f"project/{project}")
            resp = api_post("/api/tags/facts", {
                "filters": [{"tags": tags, "sort_by": "date", "limit": 8}],
            })
            facts = ((resp or {}).get("results") or [{}])[0].get("facts", [])
            sid_prefix = session_id[:8]
            candidates = [
                f for f in facts
                if f.get("node/blob-dir")
                and sid_prefix not in f.get("node/blob-dir", "")
            ]
            if candidates:
                print("# CHOOSE_SESSION")
                print()
                print("No continuation chain found. Recent sessions:")
                print()
                for i, f in enumerate(candidates):
                    content = f.get("node/content", "") or ""
                    blob_dir = f.get("node/blob-dir", "")
                    lines = content.splitlines()
                    title = lines[0] if lines else "(untitled)"
                    summary = lines[1] if len(lines) > 1 else None
                    line = f"{i + 1}. **{title}**"
                    if summary:
                        line += f" — {summary}"
                    line += f" `[blob: {blob_dir}]`"
                    print(line)
            else:
                print("No previous session found.")

    else:
        print("Usage: python3 load-chain.py <session-id> [project]")
        print("       python3 load-chain.py --blob <blob-dir>")
        sys.exit(1)


if __name__ == "__main__":
    main()
