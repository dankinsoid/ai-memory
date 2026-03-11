#!/usr/bin/env python3
# @ai-generated(solo)
from __future__ import annotations
"""Tests for tags.py and storage.py.

Run: python3 -m pytest mcp/test_mcp.py  OR  python3 mcp/test_mcp.py
Uses only stdlib — no external dependencies.

All storage tests use a temp dir as AI_MEMORY_DIR to avoid touching
the real ~/.claude/ai-memory/ directory.
"""

import os
import sys
import tempfile
import time
import unittest
import unittest.mock
from pathlib import Path

# Allow importing siblings regardless of cwd
sys.path.insert(0, str(Path(__file__).parent))

import storage
import tags


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _write_fact(
    base: Path,
    rel_path: str,
    body: str,
    fm_tags: list[str] | None = None,
    date_str: str = "2026-01-01",
) -> Path:
    """Create a fact .md file with front-matter under base."""
    path = base / rel_path
    path.parent.mkdir(parents=True, exist_ok=True)
    tags_str = "[" + ", ".join(fm_tags or []) + "]"
    content = f"---\ntags: {tags_str}\ndate: {date_str}\n---\n\n{body}\n"
    path.write_text(content, encoding="utf-8")
    return path


def _write_session(
    base: Path,
    rel_path: str,
    title: str,
    summary: str,
    session_id: str = "test-id",
    project: str | None = None,
    date_str: str = "2026-01-01",
    fm_tags: list[str] | None = None,
) -> Path:
    """Create a session .md file under base."""
    path = base / rel_path
    path.parent.mkdir(parents=True, exist_ok=True)
    tags_str = "[" + ", ".join(fm_tags or []) + "]"
    lines = ["---", f"id: {session_id}", f"date: {date_str}"]
    if project:
        lines.append(f"project: {project}")
    lines += [f"title: {title}", f"tags: {tags_str}", "---", "", "## Summary", "", summary, ""]
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


# ---------------------------------------------------------------------------
# tags.py tests
# ---------------------------------------------------------------------------


class TestParseFrontMatter(unittest.TestCase):
    def test_basic(self):
        content = "---\ntags: [testing, workflow]\ndate: 2026-01-01\n---\n\nbody"
        fm = tags.parse_front_matter(content)
        self.assertEqual(fm["tags"], "[testing, workflow]")
        self.assertEqual(fm["date"], "2026-01-01")

    def test_no_front_matter(self):
        self.assertEqual(tags.parse_front_matter("just body"), {})

    def test_empty_values(self):
        content = "---\ntags: []\n---\n\nbody"
        fm = tags.parse_front_matter(content)
        self.assertEqual(fm["tags"], "[]")

    def test_single_value_tag(self):
        content = "---\ntags: testing\n---\n\nbody"
        fm = tags.parse_front_matter(content)
        result = tags.parse_tags_field(fm["tags"])
        self.assertEqual(result, ["testing"])

    def test_ignores_comments(self):
        content = "---\n# this is a comment\ntags: [foo]\n---\n\nbody"
        fm = tags.parse_front_matter(content)
        self.assertNotIn("#", fm)
        self.assertEqual(fm["tags"], "[foo]")


class TestParseTagsField(unittest.TestCase):
    def test_inline_list(self):
        self.assertEqual(tags.parse_tags_field("[a, b, c]"), ["a", "b", "c"])

    def test_single(self):
        self.assertEqual(tags.parse_tags_field("foo"), ["foo"])

    def test_empty(self):
        self.assertEqual(tags.parse_tags_field(""), [])
        self.assertEqual(tags.parse_tags_field("[]"), [])

    def test_whitespace_trimmed(self):
        self.assertEqual(tags.parse_tags_field("[ a , b ]"), ["a", "b"])


class TestDeriveTagsFromPath(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _path(self, rel: str) -> Path:
        return self.base / rel

    def test_universal(self):
        self.assertEqual(
            tags.derive_tags_from_path(self._path("universal/foo.md"), self.base),
            ["universal"],
        )

    def test_language(self):
        self.assertEqual(
            tags.derive_tags_from_path(self._path("languages/clojure/foo.md"), self.base),
            ["clojure"],
        )

    def test_project_rules(self):
        result = tags.derive_tags_from_path(
            self._path("projects/my-app/rules/foo.md"), self.base
        )
        self.assertIn("project/my-app", result)
        self.assertIn("rule", result)

    def test_project_sessions(self):
        result = tags.derive_tags_from_path(
            self._path("projects/my-app/sessions/foo.md"), self.base
        )
        self.assertIn("project/my-app", result)
        self.assertIn("session", result)

    def test_project_root_file(self):
        # File directly in projects/<name>/ (no subdirectory section)
        result = tags.derive_tags_from_path(
            self._path("projects/my-app/foo.md"), self.base
        )
        self.assertIn("project/my-app", result)
        self.assertNotIn("rule", result)
        self.assertNotIn("session", result)

    def test_sessions_top_level(self):
        self.assertEqual(
            tags.derive_tags_from_path(self._path("sessions/foo.md"), self.base),
            ["session"],
        )

    def test_root_file_no_tags(self):
        self.assertEqual(
            tags.derive_tags_from_path(self._path("foo.md"), self.base),
            [],
        )

    def test_outside_base(self):
        self.assertEqual(
            tags.derive_tags_from_path(Path("/tmp/other/foo.md"), self.base),
            [],
        )


class TestAllTagsForFile(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_union_dedup(self):
        # Path gives [universal]; front-matter adds [testing]
        # front-matter 'universal' should not be duplicated
        content = "---\ntags: [universal, testing]\n---\n\nbody"
        path = self.base / "universal" / "foo.md"
        result = tags.all_tags_for_file(path, self.base, content)
        self.assertEqual(result.count("universal"), 1)
        self.assertIn("testing", result)
        # path-derived comes first
        self.assertEqual(result[0], "universal")

    def test_path_tags_first(self):
        content = "---\ntags: [extra]\n---\n\nbody"
        path = self.base / "universal" / "foo.md"
        result = tags.all_tags_for_file(path, self.base, content)
        self.assertEqual(result[0], "universal")
        self.assertIn("extra", result)


# ---------------------------------------------------------------------------
# storage.py tests
# ---------------------------------------------------------------------------


class StorageTestBase(unittest.TestCase):
    """Base class: creates a temp AI_MEMORY_DIR and restores env after each test."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        os.environ["AI_MEMORY_DIR"] = str(self.base)

    def tearDown(self):
        del os.environ["AI_MEMORY_DIR"]
        self.tmp.cleanup()


class TestSearchFacts(StorageTestBase):
    def test_empty_returns_empty(self):
        self.assertEqual(storage.search_facts(), [])

    def test_finds_by_all_tags(self):
        _write_fact(self.base, "universal/foo.md", "foo fact", fm_tags=["testing"])
        _write_fact(self.base, "universal/bar.md", "bar fact", fm_tags=["workflow"])

        results = storage.search_facts(tags=["universal", "testing"])
        self.assertEqual(len(results), 1)
        self.assertIn("foo fact", results[0]["content"])

    def test_any_tags_union(self):
        _write_fact(self.base, "universal/a.md", "fact a", fm_tags=["testing"])
        _write_fact(self.base, "universal/b.md", "fact b", fm_tags=["workflow"])
        _write_fact(self.base, "universal/c.md", "fact c", fm_tags=["other"])

        results = storage.search_facts(any_tags=["testing", "workflow"])
        paths = [r["path"] for r in results]
        self.assertIn("universal/a.md", paths)
        self.assertIn("universal/b.md", paths)
        self.assertNotIn("universal/c.md", paths)

    def test_exclude_tags(self):
        _write_fact(self.base, "universal/keep.md", "keep", fm_tags=["testing"])
        _write_fact(self.base, "universal/drop.md", "drop", fm_tags=["session"])

        results = storage.search_facts(tags=["universal"], exclude_tags=["session"])
        paths = [r["path"] for r in results]
        self.assertIn("universal/keep.md", paths)
        self.assertNotIn("universal/drop.md", paths)

    def test_text_filter(self):
        _write_fact(self.base, "universal/a.md", "use kaocha for testing")
        _write_fact(self.base, "universal/b.md", "prefer leiningen")

        results = storage.search_facts(text="kaocha")
        self.assertEqual(len(results), 1)
        self.assertIn("kaocha", results[0]["content"])

    def test_text_case_insensitive(self):
        _write_fact(self.base, "universal/a.md", "Use Kaocha For Testing")
        results = storage.search_facts(text="kaocha")
        self.assertEqual(len(results), 1)

    def test_since_filter(self):
        _write_fact(self.base, "universal/old.md", "old", date_str="2025-06-01")
        _write_fact(self.base, "universal/new.md", "new", date_str="2026-03-01")

        results = storage.search_facts(since="2026-01-01")
        paths = [r["path"] for r in results]
        self.assertIn("universal/new.md", paths)
        self.assertNotIn("universal/old.md", paths)

    def test_until_filter(self):
        _write_fact(self.base, "universal/old.md", "old", date_str="2025-06-01")
        _write_fact(self.base, "universal/new.md", "new", date_str="2026-03-01")

        results = storage.search_facts(until="2025-12-31")
        paths = [r["path"] for r in results]
        self.assertIn("universal/old.md", paths)
        self.assertNotIn("universal/new.md", paths)

    def test_sort_by_date_newest_first(self):
        _write_fact(self.base, "universal/a.md", "a", date_str="2025-01-01")
        _write_fact(self.base, "universal/b.md", "b", date_str="2026-03-01")
        _write_fact(self.base, "universal/c.md", "c", date_str="2025-06-15")

        results = storage.search_facts(sort_by="date")
        dates = [r["date"] for r in results]
        self.assertEqual(dates, sorted(dates, reverse=True))

    def test_sort_by_modified(self):
        _write_fact(self.base, "universal/a.md", "a")
        time.sleep(0.02)  # ensure different mtime
        _write_fact(self.base, "universal/b.md", "b")

        results = storage.search_facts(sort_by="modified")
        # b was written last, should come first
        self.assertIn("b", results[0]["content"])

    def test_limit_and_offset(self):
        for i in range(5):
            _write_fact(self.base, f"universal/f{i}.md", f"fact {i}", date_str=f"2026-0{i+1}-01")

        page1 = storage.search_facts(sort_by="date", limit=3, offset=0)
        page2 = storage.search_facts(sort_by="date", limit=3, offset=3)

        self.assertEqual(len(page1), 3)
        self.assertEqual(len(page2), 2)
        # No overlap
        paths1 = {r["path"] for r in page1}
        paths2 = {r["path"] for r in page2}
        self.assertEqual(paths1 & paths2, set())

    def test_sessions_excluded_via_tag(self):
        """Sessions are excluded from facts when using exclude_tags=["session"]."""
        _write_fact(self.base, "universal/fact.md", "a fact")
        _write_session(self.base, "sessions/sess.md", "My session", "summary")

        results = storage.search_facts(exclude_tags=["session"])
        paths = [r["path"] for r in results]
        self.assertNotIn("sessions/sess.md", paths)
        self.assertIn("universal/fact.md", paths)

    def test_result_includes_date_field(self):
        _write_fact(self.base, "universal/foo.md", "body", date_str="2026-03-11")
        results = storage.search_facts()
        self.assertEqual(results[0]["date"], "2026-03-11")


class TestSearchSessions(StorageTestBase):
    def test_empty_returns_empty(self):
        self.assertEqual(storage.search_sessions(), [])

    def test_finds_in_sessions_dir(self):
        _write_session(self.base, "sessions/s1.md", "Session One", "summary one")
        results = storage.search_sessions()
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["title"], "Session One")

    def test_project_scoped(self):
        _write_session(
            self.base, "projects/myapp/sessions/s1.md",
            "App Session", "summary", project="myapp", date_str="2026-03-01",
        )
        _write_session(self.base, "sessions/s2.md", "Generic Session", "summary")

        results = storage.search_sessions(project="myapp")
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["title"], "App Session")

    def test_since_filter(self):
        _write_session(self.base, "sessions/old.md", "Old", "s", date_str="2025-01-01")
        _write_session(self.base, "sessions/new.md", "New", "s", date_str="2026-03-01")

        results = storage.search_sessions(since="2026-01-01")
        titles = [r["title"] for r in results]
        self.assertIn("New", titles)
        self.assertNotIn("Old", titles)

    def test_sort_newest_first(self):
        _write_session(self.base, "sessions/a.md", "A", "s", date_str="2025-01-01")
        _write_session(self.base, "sessions/b.md", "B", "s", date_str="2026-03-01")
        _write_session(self.base, "sessions/c.md", "C", "s", date_str="2025-06-15")

        results = storage.search_sessions(sort_by="date")
        dates = [r["date"] for r in results]
        self.assertEqual(dates, sorted(dates, reverse=True))

    def test_limit(self):
        for i in range(4):
            _write_session(
                self.base, f"sessions/s{i}.md", f"S{i}", "s", date_str=f"2026-0{i+1}-01"
            )
        results = storage.search_sessions(limit=2)
        self.assertEqual(len(results), 2)


class TestUpsertSession(StorageTestBase):
    def test_creates_file(self):
        path = storage.upsert_session(
            session_id="abc123",
            project=None,
            title="test session",
            summary="did some work",
            tags=["architecture"],
        )
        full = self.base / path
        self.assertTrue(full.exists())
        content = full.read_text()
        self.assertIn("abc123", content)
        self.assertIn("did some work", content)
        self.assertIn("## Summary", content)

    def test_creates_in_project_dir(self):
        path = storage.upsert_session(
            session_id="xyz",
            project="my-project",
            title="proj session",
            summary="worked on project",
            tags=[],
        )
        self.assertTrue(path.startswith("projects/my-project/sessions/"))

    def test_updates_existing_by_session_id(self):
        path1 = storage.upsert_session(
            session_id="same-id",
            project=None,
            title="original",
            summary="first summary",
            tags=[],
        )
        path2 = storage.upsert_session(
            session_id="same-id",
            project=None,
            title="original",
            summary="updated summary",
            tags=[],
        )
        # Same file, not a new one
        self.assertEqual(path1, path2)
        content = (self.base / path2).read_text()
        self.assertIn("updated summary", content)
        self.assertNotIn("first summary", content)

    def test_different_ids_create_different_files(self):
        path1 = storage.upsert_session("id-1", None, "session one", "s", [])
        path2 = storage.upsert_session("id-2", None, "session two", "s", [])
        self.assertNotEqual(path1, path2)

    def test_content_written_to_messages_file(self):
        """Content is written to a separate messages file, not the summary file."""
        path = storage.upsert_session(
            session_id="with-content",
            project=None,
            title="full session",
            summary="summary text",
            tags=[],
            content="detailed content here",
        )
        summary_text = (self.base / path).read_text()
        # Summary file links to messages file
        self.assertIn("messages:", summary_text)
        # Messages file contains the actual content
        stem = (self.base / path).stem
        messages_path = (self.base / path).parent / f"{stem} messages.md"
        self.assertTrue(messages_path.exists())
        self.assertIn("detailed content here", messages_path.read_text())

    def test_continues_set_on_new_session(self):
        """Second new session in same dir auto-sets continues: to first session title."""
        storage.upsert_session("id-first", None, "first session", "s1", [])
        path2 = storage.upsert_session("id-second", None, "second session", "s2", [])
        content = (self.base / path2).read_text()
        self.assertIn("continues:", content)
        self.assertIn("first session", content)

    def test_continues_not_set_when_updating(self):
        """Updating an existing session must not overwrite its continues: field."""
        # Create first session so auto-continues would kick in
        storage.upsert_session("id-prev", None, "prev session", "s0", [])
        # Create second session (will get continues: set automatically)
        storage.upsert_session("id-target", None, "target session", "s1", [])
        # Update second session again — continues: must not change
        path = storage.upsert_session("id-target", None, "target session", "updated", [])
        content = (self.base / path).read_text()
        # continues: should still point to first session, not be re-written
        self.assertIn("prev session", content)

    def test_sessions_dir_env_override(self):
        """AI_MEMORY_SESSIONS_DIR routes sessions to a different root directory."""
        with tempfile.TemporaryDirectory() as sessions_root:
            with unittest.mock.patch.dict(os.environ, {"AI_MEMORY_SESSIONS_DIR": sessions_root}):
                path = storage.upsert_session("env-id", "myproj", "env session", "s", [])
                self.assertTrue(path.startswith("projects/myproj/sessions/"))
                full_path = Path(sessions_root) / path
                self.assertTrue(full_path.exists(), f"Expected session at {full_path}")


class TestRemember(StorageTestBase):
    def test_universal_routing(self):
        path = storage.remember("always write docstrings", tags=["universal", "conventions"])
        self.assertTrue(path.startswith("universal/"))

    def test_project_routing(self):
        path = storage.remember("use integrant", tags=["project/my-app", "architecture"])
        self.assertTrue(path.startswith("projects/my-app/rules/"))

    def test_language_routing(self):
        path = storage.remember("prefer kaocha", tags=["clojure", "testing"])
        self.assertTrue(path.startswith("languages/clojure/"))

    def test_path_tags_excluded_from_fm(self):
        path = storage.remember("some rule", tags=["universal", "testing"])
        content = (self.base / path).read_text()
        fm = tags.parse_front_matter(content)
        fm_tags = tags.parse_tags_field(fm.get("tags", ""))
        # 'universal' comes from path — must not be duplicated in front-matter
        self.assertNotIn("universal", fm_tags)
        self.assertIn("testing", fm_tags)

    def test_custom_filename(self):
        path = storage.remember("some rule", tags=["universal"], filename="my-custom-rule")
        self.assertTrue(path.endswith("my-custom-rule.md"))

    def test_auto_filename_from_content(self):
        path = storage.remember("use test driven development", tags=["universal"])
        self.assertIn("use test driven development", path)

    def test_explicit_language_overrides_tag(self):
        # tags has no language tag — explicit language param should route correctly
        path = storage.remember("use typing module", tags=["typing"], language="python")
        self.assertTrue(path.startswith("languages/python/"))

    def test_explicit_language_takes_priority_over_tag(self):
        # Both explicit language and a language tag — explicit wins
        path = storage.remember("some rule", tags=["clojure"], language="python")
        self.assertTrue(path.startswith("languages/python/"))

    def test_language_tag_fallback(self):
        # No explicit language — language tag used as fallback
        path = storage.remember("prefer kaocha", tags=["clojure", "testing"])
        self.assertTrue(path.startswith("languages/clojure/"))


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    unittest.main(verbosity=2)
