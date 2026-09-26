"""Turns Core's public PebbleOS changelog page into the version list for the app.

The workflow fetches the page once with Notion's loadPageChunk call and passes the saved response
here. The page has EXPECTED_TABLES tables, and each of their rows names one version in a cell whose
trimmed text is "PebbleOS vX.Y.Z", optionally with "/N" patch suffixes ("v4.19.1/2" is 4.19.1 and
4.19.2) or a build suffix ("-core59"). Versions with a build suffix are skipped. A response without
an empty cursor, with another number of tables, or with a row that is missing or lacks exactly one
such cell is refused.

`update` keeps the previous list when the fetch or parse fails or when more than MAX_REMOVED
versions would disappear from it. It then exits 0 with a warning while the last successful update
is at most FAIL_AFTER old, and 1 otherwise. `publish` checks the list `update` produced again,
against the list on the branch and the clock, and writes it. Messages name rows by position, so no
text from the page reaches the run log.
"""

import argparse
import json
import re
import sys
from datetime import datetime, timedelta, timezone

SOURCE = "https://ndocs.repebble.com/pebbleos-changelog"
SCHEMA = 1
EXPECTED_TABLES = 2
MIN_VERSIONS = 20
MAX_REMOVED = 2
FAIL_AFTER = timedelta(hours=36)

VERSION_CELL = re.compile(r"PebbleOS\s*v(\d+)\.(\d+)\.(\d+)((?:/\d+)*)(-[A-Za-z0-9]+)?")
LISTED_VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
TIMESTAMP = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%SZ"
CLOCK_SKEW = timedelta(minutes=10)


class ParseError(Exception):
    pass


def _record(entry):
    # Records arrive as {"value": {"value": {...}}}; a single level is accepted as well.
    value = entry.get("value") if isinstance(entry, dict) else None
    if isinstance(value, dict) and isinstance(value.get("value"), dict):
        return value["value"]
    return value if isinstance(value, dict) else {}


def _cell_text(row, column):
    segments = row.get("properties", {}).get(column, [])
    return "".join(s[0] for s in segments if isinstance(s, list) and s and isinstance(s[0], str)).strip()


def _version_key(version):
    return tuple(int(part) for part in version.split("."))


def parse_versions(page):
    """The main-line versions the page's tables name, newest first."""
    try:
        blocks = page["recordMap"]["block"]
    except (KeyError, TypeError):
        raise ParseError("no recordMap.block in the response")
    cursor = page.get("cursor")
    if not isinstance(cursor, dict) or cursor.get("stack"):
        raise ParseError("the response has no cursor or is incomplete")
    tables = [t for t in map(_record, blocks.values()) if t.get("type") == "table"]
    if len(tables) != EXPECTED_TABLES:
        raise ParseError(f"{len(tables)} tables on the page, expected {EXPECTED_TABLES}")
    versions = set()
    for table_number, table in enumerate(tables, 1):
        columns = table.get("format", {}).get("table_block_column_order", [])
        for row_number, row_id in enumerate(table.get("content", []), 1):
            where = f"row {row_number} of table {table_number}"
            row = _record(blocks.get(row_id, {}))
            if row.get("type") != "table_row":
                raise ParseError(f"{where} is missing from the response")
            matches = [m for m in (VERSION_CELL.fullmatch(_cell_text(row, c)) for c in columns) if m]
            if len(matches) != 1:
                raise ParseError(f"{where} has {len(matches)} version cells, expected 1")
            match = matches[0]
            if match.group(5):
                continue
            major, minor, patch, extra = match.group(1), match.group(2), match.group(3), match.group(4)
            versions.add(f"{int(major)}.{int(minor)}.{int(patch)}")
            for more in filter(None, extra.split("/")):
                versions.add(f"{int(major)}.{int(minor)}.{int(more)}")
    return sorted(versions, key=_version_key, reverse=True)


def check_update(versions, previous):
    """The previous list's versions that the new one drops; raises when the update must not go out."""
    if len(versions) < MIN_VERSIONS:
        raise ParseError(f"only {len(versions)} versions parsed, expected at least {MIN_VERSIONS}")
    before = previous.get("versions", []) if previous else []
    removed = [v for v in before if v not in versions]
    if len(removed) > MAX_REMOVED:
        raise ParseError(f"{len(removed)} listed versions are gone from the page: {', '.join(removed)}")
    return removed


def check_listed(listed):
    """Raises unless `listed` has the shape `update` writes."""
    if not isinstance(listed, dict) or set(listed) != {"schema", "source", "checkedAt", "versions"}:
        raise ParseError("unexpected keys in the list")
    if type(listed["schema"]) is not int or listed["schema"] != SCHEMA or listed["source"] != SOURCE:
        raise ParseError("unexpected schema or source in the list")
    if not isinstance(listed["checkedAt"], str) or not TIMESTAMP.fullmatch(listed["checkedAt"]):
        raise ParseError("unexpected checkedAt in the list")
    try:
        _timestamp(listed["checkedAt"])
    except ValueError:
        raise ParseError("unexpected checkedAt in the list")
    versions = listed["versions"]
    if not isinstance(versions, list) or not all(isinstance(v, str) and LISTED_VERSION.fullmatch(v) for v in versions):
        raise ParseError("unexpected versions in the list")
    if versions != sorted(set(versions), key=_version_key, reverse=True):
        raise ParseError("the list's versions are not unique and newest first")


def _timestamp(value):
    return datetime.strptime(value, TIMESTAMP_FORMAT).replace(tzinfo=timezone.utc)


def _load_list(list_path):
    try:
        with open(list_path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None


def _write_list(list_path, listed):
    with open(list_path, "w", encoding="utf-8") as f:
        json.dump(listed, f, indent=2)
        f.write("\n")


def run_update(page_path, status, list_path, now, out=sys.stdout):
    previous = _load_list(list_path)
    try:
        if status != "200":
            raise ParseError(f"HTTP {status}")
        with open(page_path, encoding="utf-8") as f:
            page = json.load(f)
        versions = parse_versions(page)
        removed = check_update(versions, previous)
    except (ParseError, ValueError, OSError, AttributeError, TypeError, KeyError, RecursionError, MemoryError) as e:
        last = previous.get("checkedAt") if previous else None
        if last and _timestamp(now) - _timestamp(last) <= FAIL_AFTER:
            print(f"::warning::Changelog list not updated ({e}); last updated {last}", file=out)
            return 0
        print(f"::error::Changelog list not updated ({e}); last updated {last or 'never'}", file=out)
        return 1

    if removed:
        print(f"::warning::No longer on the changelog: {', '.join(removed)}", file=out)
    _write_list(list_path, {"schema": SCHEMA, "source": SOURCE, "checkedAt": now, "versions": versions})
    print(f"{len(versions)} versions, newest {versions[0]}", file=out)
    return 0


def run_publish(listed_json, list_path, now, out=sys.stdout):
    previous = _load_list(list_path)
    try:
        listed = json.loads(listed_json)
        check_listed(listed)
        check_update(listed["versions"], previous)
        checked_at = _timestamp(listed["checkedAt"])
        if checked_at > _timestamp(now) + CLOCK_SKEW:
            raise ParseError(f"checkedAt {listed['checkedAt']} is in the future")
    except (ParseError, ValueError, TypeError, KeyError, RecursionError, MemoryError) as e:
        print(f"::error::Changelog list not published ({e})", file=out)
        return 1
    if previous and checked_at <= _timestamp(previous["checkedAt"]):
        print(f"Not published: the branch already has a list from {previous['checkedAt']}", file=out)
        return 0
    _write_list(list_path, listed)
    return 0


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    update = commands.add_parser("update")
    update.add_argument("--page", required=True)
    update.add_argument("--status", required=True)
    update.add_argument("--list", required=True)
    update.add_argument("--now", required=True)
    publish = commands.add_parser("publish", help="reads the list from stdin")
    publish.add_argument("--list", required=True)
    publish.add_argument("--now", required=True)
    args = parser.parse_args()
    if args.command == "update":
        return run_update(args.page, args.status, args.list, args.now)
    return run_publish(sys.stdin.read(), args.list, args.now)


if __name__ == "__main__":
    sys.exit(main())
