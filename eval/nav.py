# @ai-generated(solo)
from __future__ import annotations
"""Navigational retrieval benchmark: can an agent resolve "yesterday's last session"?

Semantic recall@k does not apply here — these requests name a position in time,
not a topic, and the answer is a single file. The metric is exact match on the
resolved ref.

An LLM plays the agent: it receives the real memory_search schema and answers
with tool calls, which are executed against the live vault. Whatever it finally
names is compared to ground truth computed directly from the store. This
measures the whole path — schema legibility, parameter choice, and whether the
rendered results carry enough signal to pick a winner.

Usage:
  python3 eval/nav.py --model gpt-4.1-mini
"""

import argparse
import json
import os
import re
import sys
import urllib.request
from datetime import date, timedelta
from pathlib import Path

PLUGIN_DIR = Path(__file__).resolve().parent.parent / "plugins" / "ai-memory"
if str(PLUGIN_DIR) not in sys.path:
    sys.path.insert(0, str(PLUGIN_DIR))

sys.path.insert(0, str(PLUGIN_DIR / "mcp"))

from lib import storage  # noqa: E402

_URL = "https://api.openai.com/v1/chat/completions"


def _api_key() -> str | None:
    key = os.environ.get("OPENAI_API_KEY")
    if key:
        return key
    try:
        settings = json.loads((Path.home() / ".claude" / "settings.json").read_text())
        return settings.get("env", {}).get("OPENAI_API_KEY")
    except Exception:
        return None


def last_session_of(day: str) -> dict | None:
    """Ground truth: the session with the latest mtime on the given day."""
    res = storage.search_facts(
        tags=["session"], since=day, until=day, sort_by="modified", limit=100,
    )
    return res[0] if res else None


def nth_session_of(day: str, n: int) -> dict | None:
    """Ground truth for "the Nth session that day", counted chronologically."""
    res = storage.search_facts(
        tags=["session"], since=day, until=day, sort_by="modified", limit=100,
    )
    ordered = sorted(res, key=lambda r: r["modified"])
    return ordered[n - 1] if len(ordered) >= n else None


def build_cases(today: date) -> list[dict]:
    """Navigational requests phrased as a user would, with their true answer."""
    cases: list[dict] = []
    for back, phrase in ((1, "вчерашний"), (2, "позавчерашний")):
        day = (today - timedelta(days=back)).isoformat()
        truth = last_session_of(day)
        if truth:
            cases.append({
                "request": f"загрузи {phrase} последний диалог",
                "expect": truth["ref"],
                "note": f"{day} last ({truth['modified']})",
            })

    day = (today - timedelta(days=1)).isoformat()
    first = nth_session_of(day, 1)
    if first:
        cases.append({
            "request": "открой самый первый вчерашний диалог",
            "expect": first["ref"],
            "note": f"{day} first ({first['modified']})",
        })

    for back in (3, 4):
        day = (today - timedelta(days=back)).isoformat()
        truth = last_session_of(day)
        if truth:
            cases.append({
                "request": f"загрузи последнюю сессию за {day}",
                "expect": truth["ref"],
                "note": f"{day} last ({truth['modified']})",
            })
    return cases


SYSTEM = """You are an agent with access to a memory vault of past sessions.
Resolve the user's request to exactly one stored session.

Call memory_search to look, then answer with the chosen session's [[ref]].
The [[ref]] is an opaque id, not a title — never infer meaning from it.
When you have the answer, reply with ONLY the ref in double brackets."""


def tool_schema() -> list[dict]:
    """The live memory_search schema, so the benchmark tracks the real tool."""
    from server import _build_tools

    for t in _build_tools():
        if t["name"] == "memory_search":
            return [{
                "type": "function",
                "function": {
                    "name": "memory_search",
                    "description": t["description"],
                    "parameters": t["inputSchema"],
                },
            }]
    raise RuntimeError("memory_search not found")


def run_search(args: dict) -> str:
    """Execute a tool call through the same formatting the agent would see."""
    from server import _format_search_result

    res = storage.search_facts(
        tags=args.get("tags"),
        any_tags=args.get("any_tags"),
        exclude_tags=args.get("exclude_tags"),
        query=args.get("query"),
        since=args.get("since"),
        until=args.get("until"),
        sort_by=args.get("sort_by", "date"),
        limit=args.get("limit", 20),
        offset=args.get("offset", 0),
    )
    if not res:
        return "No results found."
    return "\n\n".join(_format_search_result(r) for r in res)


def solve(case: dict, key: str, model: str, today: date, max_turns: int = 4) -> dict:
    """Let the model drive memory_search until it names a ref."""
    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": f"Today is {today.isoformat()}. {case['request']}"},
    ]
    tools = tool_schema()
    calls: list[dict] = []

    for _ in range(max_turns):
        body = json.dumps({
            "model": model, "messages": messages,
            "tools": tools, "temperature": 0,
        }).encode()
        req = urllib.request.Request(
            _URL, data=body,
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                msg = json.loads(resp.read())["choices"][0]["message"]
        except Exception as exc:
            return {**case, "got": None, "calls": calls, "error": str(exc)}

        messages.append(msg)
        tool_calls = msg.get("tool_calls") or []
        if not tool_calls:
            found = re.search(r"\[\[(.+?)\]\]", msg.get("content") or "")
            return {
                **case,
                "got": f"[[{found.group(1)}]]" if found else None,
                "calls": calls,
            }

        for tc in tool_calls:
            args = json.loads(tc["function"]["arguments"] or "{}")
            calls.append(args)
            messages.append({
                "role": "tool", "tool_call_id": tc["id"],
                "content": run_search(args)[:6000],
            })

    return {**case, "got": None, "calls": calls, "error": "max turns"}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gpt-4.1-mini")
    ap.add_argument("--today", default=date.today().isoformat())
    ap.add_argument("--out", default="eval/data/nav_results.json")
    args = ap.parse_args()

    key = _api_key()
    if not key:
        sys.exit("OPENAI_API_KEY not found")

    today = date.fromisoformat(args.today)
    cases = build_cases(today)
    print(f"{len(cases)} navigational cases\n")

    results = []
    for case in cases:
        r = solve(case, key, args.model, today)
        ok = r["got"] == r["expect"]
        r["ok"] = ok
        results.append(r)
        print(f"[{'PASS' if ok else 'FAIL'}] {case['request']}")
        print(f"       expect {case['expect']}  ({case['note']})")
        print(f"       got    {r['got']}")
        used = [{k: v for k, v in c.items() if k != 'tags'} for c in r["calls"]]
        print(f"       calls  {json.dumps(used, ensure_ascii=False)}")
        if r.get("error"):
            print(f"       error  {r['error']}")
        print()

    passed = sum(1 for r in results if r["ok"])
    sort_used = sum(1 for r in results if any("sort_by" in c for c in r["calls"]))
    print(f"exact match: {passed}/{len(results)}")
    print(f"used sort_by: {sort_used}/{len(results)}")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "model": args.model, "today": args.today,
        "passed": passed, "total": len(results),
        "sort_by_used": sort_used,
        "cases": results,
    }, indent=2, ensure_ascii=False, default=str))
    print(f"-> {out}")


if __name__ == "__main__":
    main()
