#!/usr/bin/env python3
"""Host-side seed, PreferencesProto codec, and logical checks for APK upgrade CI."""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import tempfile
from pathlib import Path

CONVERSATION_ID = "f9fba7c0-cc8d-4e40-bac7-0a18ef4d7131"
NODE_ID = "7f0325a3-1a60-4f21-a552-e0ec3425a619"
MESSAGE_ID = "b2e3958d-74d0-4b6b-958f-b1d18d25c733"
ASSISTANT_ID = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
TITLE = "ترقية الاختبار — محادثة حفظ البيانات"
SENTINEL = "رسالة اختبار الترقية العربية — RIKKA-UPGRADE-PROOF-20261018"
PREFERENCE_KEY = "dynamic_color"


def varint(value: int) -> bytes:
    if value < 0:
        value &= (1 << 64) - 1
    out = bytearray()
    while value > 0x7F:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = shift = 0
    while offset < len(data) and shift < 70:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
        shift += 7
    raise ValueError("truncated/invalid protobuf varint")


def fields(data: bytes):
    """Yield (field_number, wire_type, decoded_value, original_bytes)."""
    offset = 0
    while offset < len(data):
        start = offset
        tag, offset = read_varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise ValueError("invalid protobuf field number 0")
        if wire == 0:
            value, offset = read_varint(data, offset)
        elif wire == 1:
            if offset + 8 > len(data):
                raise ValueError("truncated fixed64")
            value, offset = data[offset:offset + 8], offset + 8
        elif wire == 2:
            length, offset = read_varint(data, offset)
            if offset + length > len(data):
                raise ValueError("truncated length-delimited field")
            value, offset = data[offset:offset + length], offset + length
        elif wire == 5:
            if offset + 4 > len(data):
                raise ValueError("truncated fixed32")
            value, offset = data[offset:offset + 4], offset + 4
        else:
            raise ValueError(f"unsupported protobuf wire type {wire}")
        yield number, wire, value, data[start:offset]


def length_field(number: int, payload: bytes) -> bytes:
    return varint(number << 3 | 2) + varint(len(payload)) + payload


def preferences_map(data: bytes) -> dict[str, bytes]:
    """Return raw serialized Value message for each PreferencesProto map key."""
    result: dict[str, bytes] = {}
    for field_no, wire, entry, _raw in fields(data):
        if field_no != 1 or wire != 2:
            continue
        key = None
        value = None
        for ent_no, ent_wire, ent_value, _ in fields(entry):
            if ent_no == 1 and ent_wire == 2:
                key = ent_value.decode("utf-8")
            elif ent_no == 2 and ent_wire == 2:
                value = ent_value
        if key is not None and value is not None:
            result[key] = value
    return result


def preference_bool(value: bytes) -> bool:
    matches = [(wire, item) for number, wire, item, _ in fields(value) if number == 1]
    if len(matches) != 1 or matches[0][0] != 0:
        raise ValueError("dynamic_color is not a PreferencesProto boolean")
    return bool(matches[0][1])


def encode_boolean_preferences(original: bytes, key: str, value: bool) -> bytes:
    entries = preferences_map(original)
    # AndroidX PreferencesProto.Value.boolean is oneof field 1, wire type 0.
    encoded_value = varint(1 << 3) + varint(1 if value else 0)
    entry = length_field(1, key.encode("utf-8")) + length_field(2, encoded_value)
    entries[key] = encoded_value
    # Preserve unrelated known preference entries byte-for-byte.
    return b"".join(
        length_field(1, length_field(1, k.encode("utf-8")) + length_field(2, v))
        for k, v in sorted(entries.items())
    )


def seed(db_path: Path, prefs_path: Path) -> None:
    if not db_path.is_file() or not prefs_path.is_file():
        raise ValueError("expected the old app to have initialized Room and DataStore first")
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("PRAGMA foreign_keys=ON")
        version = conn.execute("PRAGMA user_version").fetchone()[0]
        if version != 31:
            raise ValueError(f"expected Room schema 31, found {version}")
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"ConversationEntity", "message_node"}.issubset(tables):
            raise ValueError("schema 31 conversation/message_node tables missing")
        if conn.execute("SELECT 1 FROM ConversationEntity WHERE id=?", (CONVERSATION_ID,)).fetchone():
            raise ValueError("test conversation sentinel already exists; refusing to overwrite")
        now_ms = 1_798_737_600_000  # fixed synthetic timestamp; no personal data
        conn.execute(
            "INSERT INTO ConversationEntity "
            "(id,assistant_id,title,nodes,create_at,update_at,suggestions,is_pinned,custom_system_prompt,"
            "mode_injection_ids,lorebook_ids,workspace_cwd,model_id,workspace_id,folder_id,config) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (CONVERSATION_ID, ASSISTANT_ID, TITLE, "[]", now_ms, now_ms, "[]", 0, "", "[]", "[]", "", "", "", "", ""),
        )
        # Match kotlinx.serialization defaults and JSON polymorphic discriminator (`type`).
        message = {
            "id": MESSAGE_ID,
            "role": "user",
            "parts": [{"type": "text", "text": SENTINEL, "metadata": None}],
            "annotations": [],
            "createdAt": "2026-10-18T16:00:00",
            "finishedAt": None,
            "modelId": None,
            "modelSnapshot": None,
            "usage": None,
            "generationTimeMs": None,
            "contextTokens": None,
            "translation": None,
            "isContextCheckpoint": False,
        }
        conn.execute(
            "INSERT INTO message_node(id,conversation_id,node_index,messages,select_index) VALUES (?,?,?,?,?)",
            (NODE_ID, CONVERSATION_ID, 0, json.dumps([message], ensure_ascii=False, separators=(",", ":")), 0),
        )
        conn.commit()
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    finally:
        conn.close()

    raw = prefs_path.read_bytes()
    prefs = preferences_map(raw)
    if PREFERENCE_KEY in prefs and preference_bool(prefs[PREFERENCE_KEY]) is False:
        raise ValueError("sentinel setting already exists with the requested value")
    prefs_path.write_bytes(encode_boolean_preferences(raw, PREFERENCE_KEY, False))
    # Read-after-write catches malformed protobuf before pushing it to the emulator.
    written = preferences_map(prefs_path.read_bytes())
    if preference_bool(written[PREFERENCE_KEY]) is not False:
        raise ValueError("failed to encode AndroidX boolean preference")


def logical_snapshot(db_path: Path, prefs_path: Path, phase: str) -> dict:
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    try:
        conversation = conn.execute(
            "SELECT * FROM ConversationEntity WHERE id=?", (CONVERSATION_ID,)
        ).fetchone()
        if conversation is None:
            raise ValueError(f"{phase}: seeded conversation was deleted")
        nodes = conn.execute(
            "SELECT id,node_index,messages,select_index FROM message_node WHERE conversation_id=? ORDER BY node_index",
            (CONVERSATION_ID,),
        ).fetchall()
    finally:
        conn.close()
    if len(nodes) != 1:
        raise ValueError(f"{phase}: expected one message node, found {len(nodes)}")
    msgs = json.loads(nodes[0]["messages"])
    if len(msgs) != 1 or msgs[0].get("id") != MESSAGE_ID:
        raise ValueError(f"{phase}: expected the seeded message, found {len(msgs)} messages")
    message = msgs[0]
    texts = [p.get("text") for p in message.get("parts", []) if p.get("type") == "text"]
    if message.get("role") != "user" or SENTINEL not in texts:
        raise ValueError(f"{phase}: seeded Arabic message is not intact")
    values = preferences_map(prefs_path.read_bytes())
    if PREFERENCE_KEY not in values or preference_bool(values[PREFERENCE_KEY]) is not False:
        raise ValueError(f"{phase}: dynamic_color sentinel missing or changed")
    return {
        "phase": phase,
        "conversation": {
            "id": conversation["id"],
            "assistant_id": conversation["assistant_id"],
            "title": conversation["title"],
            "created_at": conversation["create_at"],
            "pinned": bool(conversation["is_pinned"]),
            "node_count": len(nodes),
        },
        "message": {
            "node_id": nodes[0]["id"],
            "node_index": nodes[0]["node_index"],
            "select_index": nodes[0]["select_index"],
            "id": message["id"],
            "role": message["role"],
            "text": "\n".join(texts),
        },
        "setting": {"key": PREFERENCE_KEY, "type": "boolean", "value": False},
    }


def compare(a_path: Path, b_path: Path) -> None:
    a, b = json.loads(a_path.read_text()), json.loads(b_path.read_text())
    a.pop("phase", None)
    b.pop("phase", None)
    if a != b:
        raise ValueError("logical persisted conversation/message/settings differ across upgrade")
    print("Logical conversation, Arabic message, and dynamic_color preference match across upgrade.")


def self_test() -> None:
    # Known PreferencesProto map encoding: map entry key=dynamic_color, Value.bool=true.
    known = bytes.fromhex("0a130a0d64796e616d69635f636f6c6f7212020801")
    decoded = preferences_map(known)
    assert preference_bool(decoded[PREFERENCE_KEY]) is True
    patched = encode_boolean_preferences(known, "dynamic_color", False)
    assert preference_bool(preferences_map(patched)[PREFERENCE_KEY]) is False
    # Unknown unrelated preference value remains semantically preserved.
    other = length_field(1, b"keep") + length_field(2, varint(3 << 3) + varint(42))
    combined = known + length_field(1, other)
    roundtrip = preferences_map(encode_boolean_preferences(combined, "dynamic_color", False))
    assert preference_bool(roundtrip[PREFERENCE_KEY]) is False
    assert any(number == 3 and wire == 0 and value == 42 for number, wire, value, _ in fields(roundtrip["keep"]))
    with tempfile.TemporaryDirectory() as tmp:
        db = Path(tmp) / "rikka_hub"
        prefs = Path(tmp) / "settings.preferences_pb"
        conn = sqlite3.connect(db)
        conn.executescript("""
          CREATE TABLE ConversationEntity(id TEXT PRIMARY KEY, assistant_id TEXT NOT NULL, title TEXT NOT NULL,
            nodes TEXT NOT NULL, create_at INTEGER NOT NULL, update_at INTEGER NOT NULL, suggestions TEXT NOT NULL,
            is_pinned INTEGER NOT NULL, custom_system_prompt TEXT NOT NULL, mode_injection_ids TEXT NOT NULL,
            lorebook_ids TEXT NOT NULL, workspace_cwd TEXT NOT NULL, model_id TEXT NOT NULL, workspace_id TEXT NOT NULL,
            folder_id TEXT NOT NULL, config TEXT NOT NULL);
          CREATE TABLE message_node(id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, node_index INTEGER NOT NULL,
            messages TEXT NOT NULL, select_index INTEGER NOT NULL, FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id));
          PRAGMA user_version=31;
        """)
        conn.close()
        prefs.write_bytes(known)
        seed(db, prefs)
        a = logical_snapshot(db, prefs, "self-test-before")
        b = logical_snapshot(db, prefs, "self-test-after")
        assert a["conversation"]["title"] == TITLE and a["message"]["text"] == SENTINEL
        assert a["setting"]["value"] is False
        a.pop("phase")
        b.pop("phase")
        assert a == b
        before = Path(tmp) / "before.json"
        after = Path(tmp) / "after.json"
        before.write_text(json.dumps({**a, "phase": "old"}))
        after.write_text(json.dumps({**b, "phase": "new"}))
        compare(before, after)
    print("Host self-test passed: schema-31 SQLite seed, Arabic serialized message, and AndroidX PreferencesProto.")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("self-test")
    p_seed = sub.add_parser("seed")
    p_seed.add_argument("database", type=Path)
    p_seed.add_argument("preferences", type=Path)
    p_capture = sub.add_parser("capture")
    p_capture.add_argument("database", type=Path)
    p_capture.add_argument("preferences", type=Path)
    p_capture.add_argument("phase")
    p_capture.add_argument("output", type=Path)
    p_compare = sub.add_parser("compare")
    p_compare.add_argument("before", type=Path)
    p_compare.add_argument("after", type=Path)
    args = parser.parse_args()
    if args.command == "self-test":
        self_test()
    elif args.command == "seed":
        seed(args.database, args.preferences)
        print("Seeded synthetic schema-31 conversation/message and dynamic_color=false.")
    elif args.command == "capture":
        result = logical_snapshot(args.database, args.preferences, args.phase)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(result, ensure_ascii=False))
    elif args.command == "compare":
        compare(args.before, args.after)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"upgrade-data verification failed: {exc}", file=sys.stderr)
        sys.exit(1)
