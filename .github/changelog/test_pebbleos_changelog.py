import io
import json
import os
import tempfile
import unittest

import pebbleos_changelog as pc

REAL_PAGE = os.path.join(os.path.dirname(__file__), "testdata", "changelog-page-2026-09-26.json")


def _row(date, version, notes, nested=True):
    value = {"type": "table_row", "properties": {"d": [[date]], "v": version, "n": [[notes]]}}
    return {"value": {"value": value}} if nested else {"value": value}


def _page(rows, nested=True, second_table=(("x", [["PebbleOS v4.9.9-core59"]], "-"),), extra_tables=()):
    blocks = {"page": {"value": {"value": {"type": "page", "content": ["t1", "t2"]}}}}
    tables = [("t1", rows), ("t2", list(second_table))] + list(extra_tables)
    for table_id, table_rows in tables:
        ids = []
        for i, row in enumerate(table_rows):
            row_id = f"{table_id}-r{i}"
            blocks[row_id] = _row(*row, nested=nested)
            ids.append(row_id)
        table = {"type": "table", "format": {"table_block_column_order": ["d", "v", "n"]}, "content": ids}
        blocks[table_id] = {"value": {"value": table}} if nested else {"value": table}
    return {"cursor": {"stack": []}, "recordMap": {"block": blocks}}


def _main_rows(count=20, start_minor=40):
    return [(f"day {i}", [[f"PebbleOS v4.{start_minor - i}.0"]], "- notes") for i in range(count)]


def _versions(count=20, start_minor=40):
    return [f"4.{start_minor - i}.0" for i in range(count)]


class ParseVersionsTest(unittest.TestCase):
    def test_reads_the_real_page(self):
        with open(REAL_PAGE, encoding="utf-8") as f:
            versions = pc.parse_versions(json.load(f))
        self.assertEqual(len(versions), 32)
        self.assertEqual(versions[:4], ["4.38.1", "4.36.0", "4.33.2", "4.31.2"])
        self.assertIn("4.19.2", versions)
        self.assertIn("4.9.153", versions)
        self.assertNotIn("4.9.9", versions)

    def test_reads_version_cells_newest_first(self):
        page = _page([
            ("Sept 22", [["PebbleOS v4.38.1"]], "- fixes"),
            ("Aug 24", [["PebbleOS v4.36.0"]], "- more"),
            ("Jul 4", [["PebbleOS v4.19.1/2"]], "- small bugfixes"),
        ])
        self.assertEqual(pc.parse_versions(page), ["4.38.1", "4.36.0", "4.19.2", "4.19.1"])

    def test_joins_formatted_segments_and_whitespace(self):
        page = _page([("x", [["PebbleOS", [["b"]]], ["\nv4.9.152/153"], []], "-")])
        self.assertEqual(pc.parse_versions(page), ["4.9.153", "4.9.152"])

    def test_normalizes_leading_zeros(self):
        page = _page([("x", [["PebbleOS v4.040.01"]], "-")])
        self.assertEqual(pc.parse_versions(page), ["4.40.1"])

    def test_skips_build_suffixes_and_ignores_notes_that_mention_versions(self):
        page = _page([
            ("x", [["PebbleOS v4.9.71-stop0"]], "-"),
            ("y", [["PebbleOS v4.30.0"]], "Fixes a crash seen on PebbleOS v4.37.0"),
        ])
        self.assertEqual(pc.parse_versions(page), ["4.30.0"])

    def test_accepts_the_single_level_record_nesting(self):
        page = _page([("x", [["PebbleOS v4.38.1"]], "-")], nested=False,
                     second_table=[("y", [["PebbleOS v4.9.100"]], "-")])
        self.assertEqual(pc.parse_versions(page), ["4.38.1", "4.9.100"])

    def test_sorts_numerically(self):
        page = _page([("x", [["PebbleOS v4.9.100"]], "-"), ("y", [["PebbleOS v4.10.0"]], "-")])
        self.assertEqual(pc.parse_versions(page), ["4.10.0", "4.9.100"])

    def test_refuses_a_response_without_blocks(self):
        with self.assertRaises(pc.ParseError):
            pc.parse_versions({"cursor": {}})

    def test_refuses_an_incomplete_response_or_one_without_a_cursor(self):
        page = _page([("x", [["PebbleOS v4.38.1"]], "-")])
        page["cursor"] = {"stack": [[{"table": "block", "id": "t2", "index": 0}]]}
        with self.assertRaisesRegex(pc.ParseError, "incomplete"):
            pc.parse_versions(page)
        del page["cursor"]
        with self.assertRaisesRegex(pc.ParseError, "no cursor"):
            pc.parse_versions(page)

    def test_refuses_another_number_of_tables(self):
        with self.assertRaisesRegex(pc.ParseError, "3 tables"):
            pc.parse_versions(_page([("x", [["PebbleOS v4.38.1"]], "-")],
                                    extra_tables=[("t3", [("y", [["PebbleOS v4.40.0"]], "beta")])]))
        page = _page([("x", [["PebbleOS v4.38.1"]], "-")])
        del page["recordMap"]["block"]["t2"]
        with self.assertRaisesRegex(pc.ParseError, "1 tables"):
            pc.parse_versions(page)

    def test_refuses_a_row_missing_from_the_response(self):
        page = _page([("x", [["PebbleOS v4.38.1"]], "-"), ("y", [["PebbleOS v4.36.0"]], "-")])
        page["recordMap"]["block"]["t1-r1"] = {"value": {"role": "none"}}
        with self.assertRaisesRegex(pc.ParseError, "row 2 of table 1 is missing"):
            pc.parse_versions(page)
        del page["recordMap"]["block"]["t1-r1"]
        with self.assertRaisesRegex(pc.ParseError, "missing"):
            pc.parse_versions(page)

    def test_refuses_a_row_without_exactly_one_version_cell(self):
        for cell in ("PebbleOS v4.40.0 (beta)", "PebbleOS 4.40.0", "PebbleOS v4.40"):
            with self.subTest(cell=cell), self.assertRaisesRegex(pc.ParseError, "0 version cells"):
                pc.parse_versions(_page([("x", [[cell]], "-")]))
        with self.assertRaisesRegex(pc.ParseError, "2 version cells"):
            pc.parse_versions(_page([("x", [["PebbleOS v4.40.0"]], "PebbleOS v4.38.0")]))


class CheckListedTest(unittest.TestCase):
    def _listed(self, **changes):
        listed = {"schema": 1, "source": pc.SOURCE, "checkedAt": "2026-09-26T09:22:00Z", "versions": _versions()}
        listed.update(changes)
        return listed

    def test_accepts_what_update_writes(self):
        pc.check_listed(self._listed())

    def test_refuses_other_shapes(self):
        bad = [
            self._listed(schema=2),
            self._listed(source="https://example.com"),
            self._listed(checkedAt="yesterday"),
            self._listed(versions=["4.38.1", "4.40.0"]),
            self._listed(versions=["4.38.1", "4.38.1"]),
            self._listed(versions=["4.38.1-core1"]),
            self._listed(versions="4.38.1"),
            self._listed(versions=[4, 38, 1]),
            self._listed(versions=["099.0.0"]),
            self._listed(versions=["\u0664.\u0663\u0668.\u0661"]),
            self._listed(schema=True),
            self._listed(schema=1.0),
            self._listed(checkedAt="2026-9-1T1:2:3Z"),
            self._listed(checkedAt="\u0662026-09-26T09:22:00Z"),
            self._listed(checkedAt=None),
            dict(self._listed(), extra=1),
            [],
        ]
        for listed in bad:
            with self.subTest(listed=listed), self.assertRaises(pc.ParseError):
                pc.check_listed(listed)


class RunTest(unittest.TestCase):
    NOW = "2026-09-27T09:22:00Z"

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.page_path = os.path.join(self.dir.name, "page.json")
        self.list_path = os.path.join(self.dir.name, "list.json")

    def tearDown(self):
        self.dir.cleanup()

    def _write_page(self, page):
        with open(self.page_path, "w", encoding="utf-8") as f:
            json.dump(page, f)

    def _write_list(self, checked_at, versions=("4.38.1",)):
        with open(self.list_path, "w", encoding="utf-8") as f:
            json.dump({"schema": 1, "source": pc.SOURCE, "checkedAt": checked_at, "versions": list(versions)}, f)

    def _update(self, status="200"):
        out = io.StringIO()
        code = pc.run_update(self.page_path, status, self.list_path, self.NOW, out=out)
        return code, out.getvalue()

    def _publish(self, listed, raw=None):
        out = io.StringIO()
        code = pc.run_publish(raw if raw is not None else json.dumps(listed), self.list_path, self.NOW, out=out)
        return code, out.getvalue()

    def _list(self):
        with open(self.list_path, encoding="utf-8") as f:
            return json.load(f)

    def test_first_update_writes_the_list(self):
        self._write_page(_page(_main_rows()))
        code, _ = self._update()
        self.assertEqual(code, 0)
        self.assertEqual(self._list(), {"schema": 1, "source": pc.SOURCE, "checkedAt": self.NOW,
                                        "versions": _versions()})

    def test_update_with_a_new_version_writes_the_list(self):
        self._write_list("2026-09-26T09:22:00Z", versions=_versions(start_minor=39))
        self._write_page(_page(_main_rows(count=21)))
        code, out = self._update()
        self.assertEqual(code, 0)
        self.assertNotIn("::warning::", out)
        self.assertEqual(self._list()["versions"], _versions(count=21))
        self.assertEqual(self._list()["checkedAt"], self.NOW)

    def test_unchanged_page_updates_the_timestamp(self):
        self._write_list("2026-09-26T09:22:00Z", versions=_versions())
        self._write_page(_page(_main_rows()))
        code, _ = self._update()
        self.assertEqual(code, 0)
        self.assertEqual(self._list()["versions"], _versions())
        self.assertEqual(self._list()["checkedAt"], self.NOW)

    def test_up_to_two_removed_versions_are_published_with_a_warning(self):
        self._write_list("2026-09-26T09:22:00Z", versions=["4.42.0", "4.41.0"] + _versions())
        self._write_page(_page(_main_rows()))
        code, out = self._update()
        self.assertEqual(code, 0)
        self.assertIn("::warning::No longer on the changelog: 4.42.0, 4.41.0", out)
        self.assertEqual(self._list()["versions"], _versions())

    def test_more_than_two_removed_versions_are_a_failure(self):
        before = ["4.43.0", "4.42.0", "4.41.0"] + _versions()
        self._write_list("2026-09-26T09:22:00Z", versions=before)
        self._write_page(_page(_main_rows()))
        code, out = self._update()
        self.assertEqual(code, 0)
        self.assertIn("3 listed versions are gone", out)
        self.assertEqual(self._list()["versions"], before)

    def test_failure_within_36_hours_warns_and_keeps_the_list(self):
        self._write_list("2026-09-26T09:22:00Z")
        code, out = self._update(status="403")
        self.assertEqual(code, 0)
        self.assertIn("::warning::", out)
        self.assertEqual(self._list()["checkedAt"], "2026-09-26T09:22:00Z")

    def test_failure_at_exactly_36_hours_still_warns(self):
        self._write_list("2026-09-25T21:22:00Z")
        code, _ = self._update(status="403")
        self.assertEqual(code, 0)

    def test_failure_after_36_hours_fails_the_run(self):
        self._write_list("2026-09-25T21:21:59Z")
        code, out = self._update(status="403")
        self.assertEqual(code, 1)
        self.assertIn("::error::", out)
        self.assertEqual(self._list()["checkedAt"], "2026-09-25T21:21:59Z")

    def test_a_non_200_status_is_a_failure_even_with_a_page(self):
        self._write_list("2026-09-26T09:22:00Z")
        self._write_page(_page(_main_rows()))
        code, out = self._update(status="500")
        self.assertEqual(code, 0)
        self.assertIn("HTTP 500", out)
        self.assertEqual(self._list()["versions"], ["4.38.1"])

    def test_failure_with_no_list_fails_the_run(self):
        code, _ = self._update(status="000")
        self.assertEqual(code, 1)
        self.assertFalse(os.path.exists(self.list_path))

    def test_too_few_versions_is_a_failure(self):
        self._write_list("2026-09-26T09:22:00Z", versions=_versions(count=19, start_minor=36))
        self._write_page(_page(_main_rows(count=19, start_minor=36)))
        code, out = self._update()
        self.assertEqual(code, 0)
        self.assertIn("only 19 versions", out)

    def test_bad_input_is_a_failure_not_a_crash(self):
        pages = {
            "html": "<html>challenge</html>",
            "shape": json.dumps({"recordMap": {"block": {"t": {"value": {"value": {"type": "table", "format": None}}}}}}),
            "nesting": "[" * 100000 + "]" * 100000,
        }
        for name, text in pages.items():
            with self.subTest(page=name):
                self._write_list("2026-09-26T09:22:00Z")
                with open(self.page_path, "w", encoding="utf-8") as f:
                    f.write(text)
                code, out = self._update()
                self.assertEqual(code, 0)
                self.assertIn("::warning::", out)
        os.remove(self.page_path)
        code, out = self._update()
        self.assertEqual(code, 0)
        self.assertIn("::warning::", out)

    def test_page_text_does_not_reach_the_log(self):
        self._write_list("2026-09-26T09:22:00Z")
        page = _page(_main_rows())
        page["recordMap"]["block"]["t1"]["value"]["value"]["content"].append("x\n::error::injected")
        self._write_page(page)
        code, out = self._update()
        self.assertEqual(code, 0)
        self.assertEqual(out.count("\n"), 1)
        self.assertNotIn("injected", out)

    def test_publish_writes_a_checked_list(self):
        self._write_list("2026-09-26T09:22:00Z", versions=_versions(start_minor=39))
        listed = {"schema": 1, "source": pc.SOURCE, "checkedAt": self.NOW, "versions": _versions(count=21)}
        code, _ = self._publish(listed)
        self.assertEqual(code, 0)
        self.assertEqual(self._list(), listed)

    def test_publish_refuses_a_list_that_fails_the_checks(self):
        def listed(**changes):
            return dict({"schema": 1, "source": pc.SOURCE, "checkedAt": self.NOW, "versions": _versions()}, **changes)
        before = ["4.43.0", "4.42.0", "4.41.0"] + _versions()
        cases = [
            ("removals", before, listed(), None),
            ("shape", _versions(), listed(source="https://example.com"), None),
            ("too few", _versions(count=19), listed(versions=_versions(count=19)), None),
            ("future", _versions(), listed(checkedAt="2026-09-27T09:33:00Z"), None),
            ("not json", _versions(), None, "{"),
            ("nesting", _versions(), None, "[" * 100000 + "]" * 100000),
        ]
        for name, previous, new, raw in cases:
            with self.subTest(case=name):
                self._write_list("2026-09-26T09:22:00Z", versions=previous)
                code, out = self._publish(new, raw)
                self.assertEqual(code, 1)
                self.assertIn("::error::", out)
                self.assertEqual(self._list()["versions"], previous)

    def test_publish_leaves_a_newer_list_on_the_branch(self):
        for checked_at in ("2026-09-27T09:22:00Z", "2026-09-27T10:00:00Z"):
            with self.subTest(branch=checked_at):
                self._write_list(checked_at, versions=_versions(start_minor=39))
                listed = {"schema": 1, "source": pc.SOURCE, "checkedAt": self.NOW, "versions": _versions()}
                code, out = self._publish(listed)
                self.assertEqual(code, 0)
                self.assertIn("Not published", out)
                self.assertEqual(self._list()["versions"], _versions(start_minor=39))

if __name__ == "__main__":
    unittest.main()
