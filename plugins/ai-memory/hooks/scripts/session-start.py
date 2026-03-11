#!/usr/bin/env python3
# @ai-generated(solo)
# SessionStart hook: auto-loads memory context into the session.
#
# Fires on: startup (skips resume).
# Calls ai-memory HTTP API and outputs formatted context to stdout,
# which becomes a system-reminder visible to the agent.
#
# Loads facts in priority order per scope (universal, project):
#   1. critical-rule  2. rule  3. preference  4. remaining
# Cascading exclude_tags ensure server-side disjointness.
# Global dedup by db/id across scopes.
#
# On clear: auto-chains new session to previous via /api/session/continue
# if the prev-session cache (written by session-end) is fresh (< 2 min).
#
# Env-var toggles (set any to disable):
#   AI_MEMORY_DISABLED=1     — master switch (all hooks)
#   AI_MEMORY_NO_READ=1      — don't inject any context
#   AI_MEMORY_NO_SESSIONS=1  — skip recent sessions section
#   AI_MEMORY_NO_FACTS=1     — skip facts sections

import json
import os
import subprocess
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib import request as urllib_request
from urllib.parse import urlencode
from urllib.error import URLError

# ---- Config ----

BASE_URL = os.environ.get("AI_MEMORY_URL", "http://localhost:8080")
API_TOKEN = os.environ.get("AI_MEMORY_TOKEN")
NO_SESSIONS = bool(os.environ.get("AI_MEMORY_NO_SESSIONS"))
NO_FACTS = bool(os.environ.get("AI_MEMORY_NO_FACTS"))

PRIORITY_TIERS = ["critical-rule", "rule", "preference", "conventions"]
TIER_LIMIT = 10
UNIVERSAL_BUDGET = 5
PROJECT_BUDGET = 10
MAX_AUTO_CHAIN_AGE_SECS = 120


# ---- Git helpers ----

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


# ---- HTTP helpers ----

api_errors: list[dict] = []


def auth_headers() -> dict:
    h = {"Accept": "application/json"}
    if API_TOKEN:
        h["Authorization"] = f"Bearer {API_TOKEN}"
    return h


def api_get(path: str, params: dict | None = None) -> dict | list | None:
    """GET request to the API, returns parsed JSON or None on error."""
    url = BASE_URL + path
    if params:
        url += "?" + urlencode(params)
    req = urllib_request.Request(url, headers=auth_headers())
    try:
        with urllib_request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read())
    except Exception as e:
        api_errors.append({"path": path, "error": str(e)})
        return None


def api_post(path: str, body: dict) -> dict | None:
    """POST JSON to the API, returns parsed response or None on error."""
    headers = {**auth_headers(), "Content-Type": "application/json"}
    data = json.dumps(body).encode()
    req = urllib_request.Request(BASE_URL + path, data=data, headers=headers, method="POST")
    try:
        with urllib_request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read())
    except Exception as e:
        api_errors.append({"path": path, "error": str(e)})
        return None


# ---- Filter generation ----

def scope_filters(scope_tag: str, base_excludes: list[str], budget: int) -> list[dict]:
    """Generate priority-tiered filters for a scope.

    Args:
        scope_tag: the tag that identifies this scope (e.g. "universal" or "project/foo")
        base_excludes: tags to always exclude (e.g. other scope tags)
        budget: max facts for catch-all tier

    Returns:
        List of filter dicts for each priority tier plus one catch-all.
        Each tier excludes all higher-priority tiers to guarantee disjointness.
    """
    filters = []
    prev_tiers: list[str] = []
    for tier in PRIORITY_TIERS:
        excludes = base_excludes + prev_tiers
        f: dict = {"tags": [tier, scope_tag], "limit": TIER_LIMIT}
        if excludes:
            f["exclude_tags"] = excludes
        filters.append(f)
        prev_tiers.append(tier)

    # Catch-all: exclude all tiers + base, sort by weight
    all_excludes = base_excludes + PRIORITY_TIERS
    f_catchall: dict = {"tags": [scope_tag], "limit": budget, "sort_by": "weight"}
    if all_excludes:
        f_catchall["exclude_tags"] = all_excludes
    filters.append(f_catchall)
    return filters


def find_result(results: list[dict], filter_spec: dict) -> dict | None:
    """Find a result group matching a filter spec.
    API returns exclude_tags with dashes (exclude-tags), we send underscores.
    """
    for r in results:
        f = r.get("filter", {})
        if f.get("tags") == filter_spec.get("tags") and \
           f.get("exclude-tags") == filter_spec.get("exclude_tags"):
            return r
    return None


def build_scope(results: list[dict], scope_tag: str, base_excludes: list[str],
                budget: int, seen_ids: set) -> tuple[list[tuple[str | None, list]], set]:
    """Collect facts for a scope in priority order.

    Args:
        results: all result groups from the API
        scope_tag: scope identifier tag
        base_excludes: tags always excluded
        budget: max total facts for this scope
        seen_ids: globally seen db/id values (dedup across scopes)

    Returns:
        (tier_groups, updated_seen_ids) where tier_groups is list of (tier_name, facts).
        tier_name is None for the catch-all tier.
    """
    filters = scope_filters(scope_tag, base_excludes, budget)
    tier_names: list[str | None] = list(PRIORITY_TIERS) + [None]
    tier_groups: list[tuple[str | None, list]] = []
    remaining = budget

    for tier_name, filt in zip(tier_names, filters):
        if remaining <= 0:
            break
        result = find_result(results, filt)
        facts = (result or {}).get("facts", [])
        new_facts = [f for f in facts if f.get("db/id") not in seen_ids][:remaining]
        if new_facts:
            tier_groups.append((tier_name, new_facts))
            seen_ids = seen_ids | {f["db/id"] for f in new_facts}
            remaining -= len(new_facts)

    return tier_groups, seen_ids


# ---- Formatting ----

TIER_PREFIX = {
    "critical-rule": "[!] ",
    "rule": "[rule] ",
    "preference": "[pref] ",
}


def format_fact_with_tier(tier: str | None, fact: dict) -> str:
    prefix = TIER_PREFIX.get(tier or "", "")
    content = fact.get("node/content", "")
    blob_dir = fact.get("node/blob-dir")
    line = f"- {prefix}{content}"
    if blob_dir:
        line += f" [blob: {blob_dir}]"
    return line


def format_tier_groups(tier_groups: list[tuple[str | None, list]]) -> str | None:
    """Format tier groups into a block of lines, or return None if empty."""
    lines = [
        format_fact_with_tier(tier, fact)
        for tier, facts in tier_groups
        for fact in facts
    ]
    return "\n".join(lines) if lines else None


def split_tier_groups(tier_groups: list) -> tuple[list, list]:
    """Split tier groups into (rule_groups, fact_groups).
    Rules have a tier name; facts are the catch-all (None tier).
    """
    rules = [(t, f) for t, f in tier_groups if t is not None]
    facts = [(t, f) for t, f in tier_groups if t is None]
    return rules, facts


def format_session_line(fact: dict) -> str:
    content = fact.get("node/content", "") or ""
    blob_dir = fact.get("node/blob-dir")
    lines = content.splitlines()
    title = lines[0] if lines else "(no summary)"
    summary = lines[1] if len(lines) > 1 else None
    line = f"- {title}"
    if summary:
        line += f" — {summary}"
    if blob_dir:
        line += f" [blob: {blob_dir}]"
    return line


def format_timestamp() -> str:
    now = datetime.now()
    return now.strftime("%Y-%m-%d %H:%M (%a)")


# ---- Main ----

def main() -> None:
    data = json.loads(sys.stdin.read())
    cwd = data.get("cwd", "")
    session_id = data.get("session_id")
    hook_reason = data.get("reason")

    # Skip resume — context already present
    if data.get("source") == "resume":
        sys.exit(0)

    if any(os.environ.get(v) for v in ("AI_MEMORY_DISABLED", "AI_MEMORY_NO_READ")):
        sys.exit(0)

    project_name = derive_project(cwd)

    # ---- Auto-chain on clear ----
    # When reason=clear, SessionEnd just wrote prev-session cache.
    # If it's fresh (< 2 min), create continuation edge immediately.
    # SessionEnd(clear) → SessionStart(clear) happen back-to-back within seconds.
    if hook_reason == "clear" and session_id and project_name:
        state_dir = Path.home() / ".claude" / "hooks" / "state"
        cache_file = state_dir / f"prev-session-{project_name}.json"
        if cache_file.exists():
            try:
                cache = json.loads(cache_file.read_text())
                prev_id = cache.get("session_id")
                timestamp_str = cache.get("timestamp")
                age_secs = None
                if timestamp_str:
                    then = datetime.fromisoformat(timestamp_str)
                    now = datetime.now(then.tzinfo)
                    age_secs = (now - then).total_seconds()

                if (prev_id and prev_id != session_id
                        and age_secs is not None and age_secs <= MAX_AUTO_CHAIN_AGE_SECS):
                    api_post("/api/session/continue", {
                        "prev_session_id": prev_id,
                        "session_id": session_id,
                        "project": project_name,
                    })
                # Always clean up cache (fresh → linked; stale → skip)
                cache_file.unlink(missing_ok=True)
            except Exception:
                pass

    # ---- Build filter list ----

    def make_filters() -> list[dict]:
        if project_name:
            ptag = f"project/{project_name}"
            return (
                scope_filters("universal", [], UNIVERSAL_BUDGET)
                + [{"tags": ["project", ptag]}]
                + scope_filters(ptag, ["session", "universal"], PROJECT_BUDGET)
                + [{"tags": ["session", ptag], "sort_by": "date", "limit": 4}]
            )
        else:
            return (
                scope_filters("universal", [], UNIVERSAL_BUDGET)
                + [{"tags": ["session"], "sort_by": "date", "limit": 4}]
            )

    fact_filters = make_filters()

    tags_data = api_get("/api/tags", {"limit": "50"}) or []
    facts_data = api_post("/api/tags/facts", {"filters": fact_filters}) or {}
    results: list[dict] = facts_data.get("results", [])

    # ---- Assemble sections ----

    seen: set = set()
    sections: list[str] = []

    # Universal scope
    if not NO_FACTS:
        tier_groups, seen = build_scope(results, "universal", [], UNIVERSAL_BUDGET, seen)
        rules, facts = split_tier_groups(tier_groups)
        parts: list[str] = []
        if rules:
            rules_text = format_tier_groups(rules)
            if rules_text:
                parts.append("### Rules\n" + rules_text)
        if facts:
            facts_text = format_tier_groups(facts)
            if facts_text:
                parts.append("### Facts\n" + facts_text)
        if parts:
            sections.append("## Universal\n" + "\n".join(parts))

    # Project scope
    if project_name and not NO_FACTS:
        ptag = f"project/{project_name}"

        # Project summary (separate, not budgeted)
        summary_spec = {"tags": ["project", ptag]}
        summary_facts = (find_result(results, summary_spec) or {}).get("facts", [])
        seen |= {f["db/id"] for f in summary_facts}

        # Project sessions
        session_spec = {"tags": ["session", ptag], "sort_by": "date", "limit": 4}
        session_facts = (find_result(results, session_spec) or {}).get("facts", [])
        seen |= {f["db/id"] for f in session_facts}

        # Prioritized project facts
        tier_groups, seen = build_scope(results, ptag, ["session", "universal"], PROJECT_BUDGET, seen)
        rules, facts = split_tier_groups(tier_groups)

        proj_parts: list[str] = []
        if session_facts:
            proj_parts.append("### Sessions\n" + "\n".join(map(format_session_line, session_facts)))
        summary_lines = "\n".join(f"- {f.get('node/content', '')}" for f in summary_facts)
        rules_text = format_tier_groups(rules)
        header_block = "\n".join(filter(None, [summary_lines, ("### Rules\n" + rules_text) if rules_text else None]))
        if header_block:
            proj_parts.append(header_block)
        if facts:
            facts_text = format_tier_groups(facts)
            if facts_text:
                proj_parts.append("### Facts\n" + facts_text)
        if proj_parts:
            sections.append(f"## Project: {project_name}\n" + "\n".join(proj_parts))

    # Aspect tags (lightweight orientation)
    aspect_names = sorted(
        t.get("tag/name", "")
        for t in (tags_data if isinstance(tags_data, list) else [])
        if t.get("tag/tier") == "aspect"
    )
    if aspect_names:
        sections.append("Aspect tags: " + ", ".join(aspect_names))

    timestamp = format_timestamp()

    if sections:
        print("# Memory Context\n\n" + "\n\n".join(sections) + "\n\n---\n" + timestamp)
    elif api_errors:
        # No sections at all — API likely down
        print(
            f"# ⚠ Memory Unavailable\n\n"
            f"ai-memory server is unreachable ({BASE_URL}). "
            f"Memory context was NOT loaded. "
            f"MCP tools (memory_remember, memory_session, etc.) will likely fail too.\n"
            f"Tell the user that their memory plugin is down."
        )


if __name__ == "__main__":
    main()
