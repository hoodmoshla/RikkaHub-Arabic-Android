#!/usr/bin/env python3
"""Host-side schema/data verifier for the Rikka 186 -> candidate upgrade test.

The seed fixture and PreferencesProto codec are imported from the candidate checkout's
existing verify-apk-upgrade_data.py. This file intentionally does not duplicate that
application-specific message serialization.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import sqlite3
import sys
from pathlib import Path
from typing import Any

DEFAULT_HELPER = Path(__file__).with_name("verify-apk-upgrade_data.py")
EXPECTED_PREFERENCES = {"dynamic_color": False, "enable_suggestion": False}
# room_master_table contains Room's identity hash, expected to change with schema 31 -> 32.
# usage_record is the one intentional new application table. sqlite_sequence is SQLite's
# internal AUTOINCREMENT bookkeeping, not application data.
EXPECTED_NEW_TABLES = {"usage_record"}
IGNORED_TABLES = {"room_master_table", "sqlite_sequence"}


def load_fixture(path: Path):
    path = path.resolve()
    if not path.is_file():
        raise ValueError(f"original fixture helper not found: {path}")
    spec = importlib.util.spec_from_file_location("rikka_original_upgrade_fixture", path)
    if spec is None or spec.loader is None:
        raise ValueError(f"cannot import original fixture helper: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def encode_sql_value(value: Any) -> Any:
    if isinstance(value, bytes):
        return {"$blob_hex": value.hex()}
    return value


def stable_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def connect_readonly(path: Path) -> sqlite3.Connection:
    if not path.is_file():
        raise ValueError(f"database file missing: {path}")
    return sqlite3.connect(f"file:{path.resolve()}?mode=ro", uri=True)


def db_snapshot(db_path: Path, phase: str, expected_version: int) -> dict[str, Any]:
    conn = connect_readonly(db_path)
    conn.row_factory = sqlite3.Row
    try:
        version = conn.execute("PRAGMA user_version").fetchone()[0]
        if version != expected_version:
            raise ValueError(f"{phase}: expected PRAGMA user_version={expected_version}, got {version}")
        integrity = [row[0] for row in conn.execute("PRAGMA integrity_check")]
        if integrity != ["ok"]:
            raise ValueError(f"{phase}: PRAGMA integrity_check failed: {integrity!r}")
        foreign_keys = [list(row) for row in conn.execute("PRAGMA foreign_key_check")]
        if foreign_keys:
            raise ValueError(f"{phase}: PRAGMA foreign_key_check returned violations: {foreign_keys!r}")
        table_names = sorted(
            row[0]
            for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
        )
        tables: dict[str, list[dict[str, Any]]] = {}
        for table in table_names:
            quoted = '"' + table.replace('"', '""') + '"'
            columns = [row[1] for row in conn.execute(f"PRAGMA table_info({quoted})")]
            if not columns:
                raise ValueError(f"{phase}: could not inspect columns for {table}")
            rows = []
            for row in conn.execute(f"SELECT * FROM {quoted}"):
                rows.append({column: encode_sql_value(row[column]) for column in columns})
            # Stable ordering independent of SQLite query planner/rowid ordering.
            rows.sort(key=stable_json)
            tables[table] = rows
        return {
            "phase": phase,
            "user_version": version,
            "integrity_check": integrity,
            "foreign_key_check": foreign_keys,
            "tables": tables,
        }
    finally:
        conn.close()


def settings_snapshot(prefs_path: Path, phase: str, fixture) -> dict[str, str]:
    if not prefs_path.is_file():
        raise ValueError(f"{phase}: settings DataStore file missing: {prefs_path}")
    entries = fixture.preferences_map(prefs_path.read_bytes())
    for key, expected in EXPECTED_PREFERENCES.items():
        if key not in entries or fixture.preference_bool(entries[key]) is not expected:
            raise ValueError(f"{phase}: required boolean preference {key}={expected} is missing or changed")
    # Compare raw Value-message bytes, not merely selected keys: no old settings entry may
    # be dropped or rewritten during the update.
    return {key: value.hex() for key, value in sorted(entries.items())}


def run_seed(db_path: Path, prefs_path: Path, fixture) -> None:
    # Reuse the source helper's canonical schema-31 fixture and native serialization.
    fixture.seed(db_path, prefs_path)
    prefs = fixture.preferences_map(prefs_path.read_bytes())
    # This is a harmless UI preference; suggestions remain disabled and trigger no network.
    encoded = fixture.encode_boolean_preferences(prefs_path.read_bytes(), "enable_suggestion", False)
    prefs_path.write_bytes(encoded)
    check = fixture.preferences_map(prefs_path.read_bytes())
    if fixture.preference_bool(check["dynamic_color"]) is not False:
        raise ValueError("original fixture's dynamic_color=false setting did not persist")
    if fixture.preference_bool(check["enable_suggestion"]) is not False:
        raise ValueError("enable_suggestion=false setting failed to encode")
    if set(prefs) - set(check):
        raise ValueError("setting encoder unexpectedly dropped existing preference keys")
    print("Seeded the imported original Arabic conversation/message fixture; dynamic_color=false and enable_suggestion=false.")


def snapshot(db_path: Path, prefs_path: Path, phase: str, output: Path, expected_version: int, fixture, native_snapshot: Path | None = None) -> None:
    if native_snapshot is None:
        db = db_snapshot(db_path, phase, expected_version)
    else:
        db = json.loads(native_snapshot.read_text())
        if db.get("user_version") != expected_version:
            raise ValueError(f"native SQLite user_version mismatch: {db.get('user_version')} != {expected_version}")
        if db.get("integrity_check") != ["ok"] or db.get("foreign_key_check") != []:
            raise ValueError("native APK SQLite integrity/foreign-key verification did not pass")
        if not isinstance(db.get("tables"), dict) or "message_fts" not in db["tables"]:
            raise ValueError("native snapshot must include the actual FTS table, not omit it")
        for rows in db["tables"].values():
            rows.sort(key=stable_json)
        db["phase"] = phase
        db["verifier"] = "Android CLI loading libsqlite3x.so and libsimple.so extracted from this phase's signed APK; read-only copied DB"
    settings = settings_snapshot(prefs_path, phase, fixture)
    # Keep the original helper's own strict semantic readback alongside a complete DB dump.
    logical = fixture.logical_snapshot(db_path, prefs_path, phase)
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "phase": phase,
        "database": db,
        "settings_entries_value_hex": settings,
        "fixture_readback": logical,
        "exceptions": {
            "usage_record": "New migration-32 table; migration may backfill usage for qualifying assistant messages. It is not treated as schema-31 data.",
            "room_master_table": "Room identity row/hash is expected to change when the schema identity advances from 31 to 32.",
            "sqlite_sequence": "SQLite internal AUTOINCREMENT bookkeeping, excluded from application-data equivalence.",
        },
    }
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"phase": phase, "user_version": expected_version, "tables": len(db["tables"]), "settings": len(settings), "integrity": "ok", "foreign_key_violations": 0}, ensure_ascii=False))


def compare(before_path: Path, after_path: Path) -> None:
    before = json.loads(before_path.read_text(encoding="utf-8"))
    after = json.loads(after_path.read_text(encoding="utf-8"))
    if before["database"]["user_version"] != 31 or after["database"]["user_version"] != 32:
        raise ValueError("comparison requires schema user_version 31 before and 32 after")
    if before["database"]["integrity_check"] != ["ok"] or after["database"]["integrity_check"] != ["ok"]:
        raise ValueError("integrity_check did not pass on both snapshots")
    if before["database"]["foreign_key_check"] or after["database"]["foreign_key_check"]:
        raise ValueError("foreign_key_check did not pass on both snapshots")
    old_tables = before["database"]["tables"]
    new_tables = after["database"]["tables"]
    old_app_tables = set(old_tables) - IGNORED_TABLES
    new_app_tables = set(new_tables) - IGNORED_TABLES
    added = new_app_tables - old_app_tables
    removed = old_app_tables - new_app_tables
    if added != EXPECTED_NEW_TABLES or removed:
        raise ValueError(f"unexpected table set change: added={sorted(added)}, removed={sorted(removed)}; expected only usage_record")
    for table in sorted(old_app_tables):
        if table == "usage_record":
            raise ValueError("usage_record unexpectedly existed in schema-31 snapshot")
        if old_tables[table] != new_tables[table]:
            raise ValueError(f"persisted rows changed in pre-existing table {table}")
    # Room identity contents are deliberately excluded above; verify the explanation is
    # present so the exception is transparent in generated JSON.
    for payload in (before, after):
        if "room_master_table" in payload["database"]["tables"] and not payload["exceptions"].get("room_master_table"):
            raise ValueError("Room identity-table exception was not documented")
    if before["settings_entries_value_hex"] != after["settings_entries_value_hex"]:
        before_keys = set(before["settings_entries_value_hex"])
        after_keys = set(after["settings_entries_value_hex"])
        missing, added_settings = sorted(before_keys - after_keys), sorted(after_keys - before_keys)
        changed = sorted(k for k in before_keys & after_keys if before["settings_entries_value_hex"][k] != after["settings_entries_value_hex"][k])
        raise ValueError(f"DataStore preference entries differ: removed={missing}, added={added_settings}, changed={changed}")
    a, b = before["fixture_readback"], after["fixture_readback"]
    a.pop("phase", None)
    b.pop("phase", None)
    if a != b:
        raise ValueError("original Arabic fixture readback changed across upgrade")
    print(f"PASS: all {len(old_app_tables)} schema-31 application tables' complete rows match; DataStore entries match; only usage_record was added (Room identity change documented).")


def self_test(fixture) -> None:
    fixture.self_test()
    import tempfile
    with tempfile.TemporaryDirectory(prefix="upgrade186-selftest-") as tmp:
        root = Path(tmp)
        db = root / "rikka_hub"
        prefs = root / "settings.preferences_pb"
        conn = sqlite3.connect(db)
        conn.executescript(
            """CREATE TABLE ConversationEntity(id TEXT PRIMARY KEY, assistant_id TEXT NOT NULL, title TEXT NOT NULL,
                 nodes TEXT NOT NULL, create_at INTEGER NOT NULL, update_at INTEGER NOT NULL, suggestions TEXT NOT NULL,
                 is_pinned INTEGER NOT NULL, custom_system_prompt TEXT NOT NULL, mode_injection_ids TEXT NOT NULL,
                 lorebook_ids TEXT NOT NULL, workspace_cwd TEXT NOT NULL, model_id TEXT NOT NULL, workspace_id TEXT NOT NULL,
                 folder_id TEXT NOT NULL, config TEXT NOT NULL);
               CREATE TABLE message_node(id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, node_index INTEGER NOT NULL,
                 messages TEXT NOT NULL, select_index INTEGER NOT NULL, FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id));
               PRAGMA user_version=31;"""
        )
        conn.close()
        prefs.write_bytes(bytes.fromhex("0a130a0d64796e616d69635f636f6c6f7212020801"))
        run_seed(db, prefs, fixture)
        snapshot(db, prefs, "self-test-before", root / "before.json", 31, fixture)
        # Construct a tiny migration-shape DB: preserve old app rows; change Room identity;
        # add only the expected usage table; advance user_version.
        conn = sqlite3.connect(db)
        conn.execute("CREATE TABLE usage_record (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, created_at INTEGER NOT NULL)")
        conn.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        conn.execute("INSERT INTO room_master_table VALUES (42, 'new-32-identity')")
        conn.execute("PRAGMA user_version=32")
        conn.commit()
        conn.close()
        # This test's basic-schema fixture has no Room identity before migration.
        # Compare the data set after snapshotting the post-upgrade form.
        snapshot(db, prefs, "self-test-after", root / "after.json", 32, fixture)
        compare(root / "before.json", root / "after.json")
    print("upgrade186-data.py self-test passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--helper", type=Path, default=DEFAULT_HELPER, help="path to repository's existing verify-apk-upgrade_data.py")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("self-test")
    seed_cmd = sub.add_parser("seed")
    seed_cmd.add_argument("database", type=Path)
    seed_cmd.add_argument("preferences", type=Path)
    snap_cmd = sub.add_parser("snapshot")
    snap_cmd.add_argument("database", type=Path)
    snap_cmd.add_argument("preferences", type=Path)
    snap_cmd.add_argument("phase")
    snap_cmd.add_argument("output", type=Path)
    snap_cmd.add_argument("--expected-version", type=int, choices=(31, 32), required=True)
    snap_cmd.add_argument("--native-snapshot", type=Path)
    cmp_cmd = sub.add_parser("compare")
    cmp_cmd.add_argument("before", type=Path)
    cmp_cmd.add_argument("after", type=Path)
    args = parser.parse_args()
    fixture = load_fixture(args.helper)
    if args.command == "self-test":
        self_test(fixture)
    elif args.command == "seed":
        run_seed(args.database, args.preferences, fixture)
    elif args.command == "snapshot":
        snapshot(args.database, args.preferences, args.phase, args.output, args.expected_version, fixture, args.native_snapshot)
    elif args.command == "compare":
        compare(args.before, args.after)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"upgrade186-data verification failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
