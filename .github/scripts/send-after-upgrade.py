#!/usr/bin/env python3
"""Post-upgrade UI send proof against a local, deterministic OpenAI-compatible mock.

Run only AFTER verify-upgrade186.sh has completed. This script never installs APKs,
contacts GitHub, uses a real AI provider, or writes chat messages to SQLite. It patches
only provider/model-selection preferences through AndroidX PreferencesProto, then
sends the one proof message through the normal Compose chat UI.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

PACKAGE_DEFAULT = "me.rerere.rikkahub.arabic.dev"
PROVIDER_ID = "62b77476-35df-4b11-84ec-8a8fb6f8bf31"
MODEL_ID = "6ab73c79-2212-4b97-b08e-4954a13423c6"
MODEL_NAME = "upgrade-mock-model"
STORE_REL = "files/datastore/settings.preferences_pb"
DB_REL = "databases/rikka_hub"
DEFAULT_MESSAGE = "رسالة تحقق بعد ترقية stable186 إلى PR4 — MOCK ONLY — لا مزود خارجي"
DEFAULT_REPLY = "تم استلام رسالة اختبار الترقية عبر الخادم المحلي الوهمي."


def run(args: list[str], *, check: bool = True, timeout: int = 40, text: bool = True) -> subprocess.CompletedProcess:
    p = subprocess.run(args, text=text, capture_output=True, timeout=timeout)
    if check and p.returncode:
        out = p.stdout if isinstance(p.stdout, str) else repr(p.stdout)
        err = p.stderr if isinstance(p.stderr, str) else repr(p.stderr)
        raise RuntimeError(f"command failed ({p.returncode}): {' '.join(args)}\n{out}\n{err}")
    return p


def adb(serial: str | None, *args: str, check: bool = True, timeout: int = 40, text: bool = True):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return run(cmd, check=check, timeout=timeout, text=text)


def varint(n: int) -> bytes:
    out = bytearray()
    while n > 0x7f:
        out.append((n & 0x7f) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)


def read_varint(data: bytes, pos: int) -> tuple[int, int]:
    value = shift = 0
    while pos < len(data) and shift < 70:
        b = data[pos]
        pos += 1
        value |= (b & 0x7f) << shift
        if b < 0x80:
            return value, pos
        shift += 7
    raise ValueError("PreferencesProto varint غير صالح أو مبتور")


def fields(data: bytes):
    pos = 0
    while pos < len(data):
        start = pos
        tag, pos = read_varint(data, pos)
        number, wire = tag >> 3, tag & 7
        if not number:
            raise ValueError("PreferencesProto يحوي field number=0")
        if wire == 0:
            value, pos = read_varint(data, pos)
        elif wire == 1:
            if pos + 8 > len(data): raise ValueError("protobuf fixed64 مبتور")
            value, pos = data[pos:pos+8], pos + 8
        elif wire == 2:
            size, pos = read_varint(data, pos)
            if pos + size > len(data): raise ValueError("protobuf length field مبتور")
            value, pos = data[pos:pos+size], pos + size
        elif wire == 5:
            if pos + 4 > len(data): raise ValueError("protobuf fixed32 مبتور")
            value, pos = data[pos:pos+4], pos + 4
        else:
            raise ValueError(f"protobuf wire type غير مدعوم: {wire}")
        yield number, wire, value, data[start:pos]


def length_field(n: int, value: bytes) -> bytes:
    return varint((n << 3) | 2) + varint(len(value)) + value


def pref_map(data: bytes) -> dict[str, bytes]:
    result: dict[str, bytes] = {}
    for n, wire, payload, _ in fields(data):
        if n != 1 or wire != 2: continue
        key = value = None
        for en, ew, ev, _raw in fields(payload):
            if en == 1 and ew == 2: key = ev.decode("utf-8")
            elif en == 2 and ew == 2: value = ev
        if key is not None and value is not None: result[key] = value
    return result


def proto_string(value: str) -> bytes:
    return length_field(5, value.encode("utf-8"))  # AndroidX Value.string = field 5


def read_pref_string(raw: bytes, name: str) -> str | None:
    values = [(wire, value) for num, wire, value, _ in fields(raw) if num == 5]
    if not values: return None
    if len(values) != 1 or values[0][0] != 2:
        raise ValueError(f"DataStore key {name} ليس string")
    return values[0][1].decode("utf-8")


def encode_map(values: dict[str, bytes]) -> bytes:
    return b"".join(length_field(1, length_field(1, k.encode()) + length_field(2, v))
                     for k, v in sorted(values.items()))


def mock_provider() -> dict[str, Any]:
    # Mirrors kotlinx.serialization defaults for ProviderSetting.OpenAI and Model;
    # discriminator is `type` and its serial name is `openai`.
    model = {
        "modelId": MODEL_NAME, "displayName": "Upgrade local mock", "id": MODEL_ID,
        "type": "CHAT", "customHeaders": [], "customBodies": [],
        "inputModalities": ["TEXT"], "outputModalities": ["TEXT"], "abilities": [],
        "tools": [], "providerOverwrite": None, "contextLength": 1000000,
    }
    return {
        "type": "openai", "id": PROVIDER_ID, "enabled": True, "name": "Upgrade local mock",
        "models": [model],
        "quotaQuery": {"enabled": False, "url": "{{baseUrl}}/credits",
                        "headers": [{"name": "Authorization", "value": "Bearer {{apiKey}}"}],
                        "resultPath": "data.total_usage"},
        "customHeaders": [], "apiKey": "upgrade-ci-local-only", "baseUrl": "http://127.0.0.1:8765/v1",
        "chatCompletionsPath": "/chat/completions", "useResponseApi": False,
        "includeHistoryReasoning": True, "responsesPath": "/responses",
    }


def patched_preferences(raw: bytes, base_url: str) -> tuple[bytes, dict[str, Any]]:
    prefs = pref_map(raw)
    provider_list_raw = read_pref_string(prefs.get("providers", b""), "providers") if "providers" in prefs else None
    providers = json.loads(provider_list_raw) if provider_list_raw else []
    if not isinstance(providers, list): raise ValueError("providers DataStore JSON ليس قائمة")
    test_provider = mock_provider()
    test_provider["baseUrl"] = base_url
    providers = [p for p in providers if p.get("id") != PROVIDER_ID]
    providers.append(test_provider)
    prefs["providers"] = proto_string(json.dumps(providers, ensure_ascii=False, separators=(",", ":")))
    prefs["chat_model"] = proto_string(MODEL_ID)

    # A chosen assistant may override the global chat model. Change only that field
    # when an assistant is explicitly selected; preserve every other assistant field.
    selected = read_pref_string(prefs["select_assistant"], "select_assistant") if "select_assistant" in prefs else None
    assistant_raw = read_pref_string(prefs["assistants"], "assistants") if "assistants" in prefs else None
    if selected and assistant_raw:
        assistants = json.loads(assistant_raw)
        for assistant in assistants:
            if assistant.get("id") == selected:
                assistant["chatModelId"] = MODEL_ID
                break
        prefs["assistants"] = proto_string(json.dumps(assistants, ensure_ascii=False, separators=(",", ":")))
    updated = encode_map(prefs)
    # Read-after-write validation; refuse to replace settings if encoding is wrong.
    check = pref_map(updated)
    if read_pref_string(check["chat_model"], "chat_model") != MODEL_ID:
        raise ValueError("فشل التحقق من تفضيل chat_model")
    stored_providers = json.loads(read_pref_string(check["providers"], "providers"))
    if not any(p.get("id") == PROVIDER_ID and p.get("baseUrl") == base_url for p in stored_providers):
        raise ValueError("فشل التحقق من إعداد المزوّد المحلي")
    return updated, {"changed_keys": ["providers", "chat_model"] + (["assistants[active].chatModelId"] if selected and assistant_raw else []),
                     "provider_id": PROVIDER_ID, "model_id": MODEL_ID, "base_url": base_url,
                     "api_key": "upgrade-ci-local-only (mock only)"}


class MockState:
    def __init__(self, msg: str, reply: str, log_path: Path):
        self.message, self.reply, self.log_path = msg, reply, log_path
        self.lock = threading.Lock()
        self.requests: list[dict[str, Any]] = []
        self.replies: list[dict[str, Any]] = []
        self.error: str | None = None

    def record(self, req: dict[str, Any], result: dict[str, Any]) -> None:
        with self.lock:
            self.requests.append(req)
            self.replies.append(result)
            with self.log_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"time_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
                                    "request": req, "response": result}, ensure_ascii=False) + "\n")


class MockHandler(BaseHTTPRequestHandler):
    state: MockState
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, code: int, obj: dict[str, Any]):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code); self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body))); self.send_header("Connection", "close")
        self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            self._json(200, {"object": "list", "data": [{"id": MODEL_NAME, "object": "model", "created": 0, "owned_by": "local-mock"}]})
        else:
            self._json(404, {"error": {"message": "mock endpoint: unsupported GET", "type": "not_found"}})

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length)
            req = json.loads(raw.decode("utf-8"))
            req["_request_path"] = self.path
            if self.path.rstrip("/") != "/v1/chat/completions":
                self._json(404, {"error": {"message": "mock accepts only /v1/chat/completions"}}); return
            messages = req.get("messages", [])
            user_texts = [m.get("content", "") for m in messages if m.get("role") == "user"]
            flattened = json.dumps(user_texts, ensure_ascii=False)
            if self.state.message not in flattened:
                result = {"error": "request lacked the unique proof message", "got_user_messages": user_texts}
                self.state.record(req, result)
                self._json(400, {"error": {"message": "expected test proof message missing"}}); return
            result = {"id": "chatcmpl-upgrade-mock", "model": req.get("model", MODEL_NAME),
                      "stream": bool(req.get("stream")), "assistant_text": self.state.reply}
            self.state.record(req, result)
            if req.get("stream"):
                frames = [
                    {"id": "chatcmpl-upgrade-mock", "object": "chat.completion.chunk", "created": int(time.time()),
                     "model": req.get("model", MODEL_NAME), "choices": [{"index": 0, "delta": {"role": "assistant", "content": self.state.reply}, "finish_reason": None}]},
                    {"id": "chatcmpl-upgrade-mock", "object": "chat.completion.chunk", "created": int(time.time()),
                     "model": req.get("model", MODEL_NAME), "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
                    "[DONE]",
                ]
                data = "".join("data: " + (x if isinstance(x, str) else json.dumps(x, ensure_ascii=False)) + "\n\n" for x in frames).encode()
                self.send_response(200); self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Cache-Control", "no-cache"); self.send_header("Content-Length", str(len(data)))
                self.send_header("Connection", "close"); self.end_headers(); self.wfile.write(data)
            else:
                self._json(200, {"id": "chatcmpl-upgrade-mock", "object": "chat.completion", "created": int(time.time()),
                                 "model": req.get("model", MODEL_NAME),
                                 "choices": [{"index": 0, "message": {"role": "assistant", "content": self.state.reply}, "finish_reason": "stop"}],
                                 "usage": {"prompt_tokens": 12, "completion_tokens": 10, "total_tokens": 22}})
        except Exception as e:
            self.state.error = repr(e)
            self._json(500, {"error": {"message": "local mock handler failed"}})


def wait_adb(serial: str | None) -> None:
    for _ in range(30):
        p = adb(serial, "get-state", check=False)
        if p.returncode == 0 and p.stdout.strip() == "device": return
        time.sleep(1)
    raise RuntimeError("adb device unavailable after root restart")


def u2_element(device, xml: str, tag: str):
    # testTagsAsResourceId=true in RouteActivity exposes chat_input/chat_send_button.
    for selector in ({"resourceId": tag}, {"resourceIdMatches": ".*:id/" + tag + "$"}, {"resourceIdMatches": ".*" + tag + "$"}):
        obj = device(**selector)
        if obj.exists: return obj
    # Fail closed: do not fall back to coordinate taps or send via adb intents.
    raise RuntimeError(f"واجهة Compose لا تعرض عنصر UI المطلوب: {tag}; XML محفوظ للتشخيص")


def recursive_texts(obj: Any) -> list[str]:
    out: list[str] = []
    if isinstance(obj, str): out.append(obj)
    elif isinstance(obj, dict):
        for v in obj.values(): out.extend(recursive_texts(v))
    elif isinstance(obj, list):
        for v in obj: out.extend(recursive_texts(v))
    return out


def wait_for_db_message(db_file: Path, message: str, reply: str, timeout: int) -> tuple[bool, dict[str, Any]]:
    deadline = time.time() + timeout
    last: dict[str, Any] = {"checked": False}
    while time.time() < deadline:
        if db_file.exists():
            try:
                con = sqlite3.connect(f"file:{db_file}?mode=ro", uri=True, timeout=2)
                tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                if "message_node" in tables:
                    rows = con.execute("SELECT id,conversation_id,node_index,messages,select_index FROM message_node ORDER BY rowid DESC").fetchall()
                    by_conversation = {}
                    for row in rows:
                        parsed = json.loads(row[3])
                        by_conversation.setdefault(row[1], []).extend(parsed)
                    matches = []
                    for cid, messages in by_conversation.items():
                        users = [m for m in messages if m.get("role") == "user" and message in "\n".join(recursive_texts(m))]
                        assistants = [m for m in messages if m.get("role") == "assistant" and reply in "\n".join(recursive_texts(m))]
                        if users and assistants:
                            matches.append({"conversation_id": cid, "user_message_ids": [m.get("id") for m in users], "assistant_message_ids": [m.get("id") for m in assistants], "messages_json": messages})
                    last = {"checked": True, "table_count": len(tables), "message_node_rows": len(rows), "matching_conversations": matches}
                    if matches:
                        con.close(); return True, last
                con.close()
            except (sqlite3.Error, json.JSONDecodeError, OSError) as e:
                last = {"checked": True, "error": str(e)}
        time.sleep(1)
    return False, last


def main() -> int:
    pa = argparse.ArgumentParser(description="إرسال واجهة بعد الترقية إلى mock OpenAI محلي والتحقق من SQLite")
    pa.add_argument("--serial", help="adb serial؛ عند غيابه يستخدم الجهاز الوحيد")
    pa.add_argument("--package", default=PACKAGE_DEFAULT)
    pa.add_argument("--message", default=DEFAULT_MESSAGE)
    pa.add_argument("--reply", default=DEFAULT_REPLY)
    pa.add_argument("--port", type=int, default=8765)
    pa.add_argument("--timeout", type=int, default=150)
    pa.add_argument("--evidence-dir", type=Path, default=Path.cwd() / "upgrade-verification" / "send")
    args = pa.parse_args()
    if not args.message or not args.reply or args.message == args.reply: pa.error("رسالة الطلب والرد يجب أن تكونا غير فارغتين ومختلفتين")
    out: Path = args.evidence_dir.resolve(); out.mkdir(parents=True, exist_ok=True)
    # Avoid accidentally sharing evidence with a previous attempt.
    for name in ("endpoint.jsonl", "result.json", "before.png", "before.xml", "after.png", "after.xml"):
        (out / name).unlink(missing_ok=True)
    result: dict[str, Any] = {"started_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "package": args.package,
                              "mock_transport_only": True, "external_provider_contacted": False, "status": "failed"}
    server = None; dev = None; db_local = out / "rikka_hub.after.sqlite"
    try:
        # Require exact installed package and rooted emulator; no install or upgrade is done here.
        adb(args.serial, "shell", "pm", "path", args.package)
        root = adb(args.serial, "root", check=False)
        if root.returncode and "already running as root" not in (root.stdout + root.stderr).lower():
            raise RuntimeError("adb root غير متاح؛ لا يمكن سحب SQLite من بيانات التطبيق بأمان")
        wait_adb(args.serial)
        uid_info = adb(args.serial, "shell", f"stat -c '%u %g %a' /data/user/0/{args.package}/{STORE_REL}", check=False)
        if uid_info.returncode:
            raise RuntimeError("ملف DataStore غير موجود بعد تشغيل مرحلة الترقية؛ يجب أن يكون التطبيق قد أُطلق مرة واحدة")
        uid_gid_mode = uid_info.stdout.strip().split()
        if len(uid_gid_mode) != 3: raise RuntimeError("تعذر قراءة مالك/صلاحيات DataStore")
        # Snapshot pre-send config exactly as found after upgrade; capture is evidence, not seed DB.
        adb(args.serial, "pull", f"/data/user/0/{args.package}/{STORE_REL}", str(out / "settings-before-send.preferences_pb"))
        original = (out / "settings-before-send.preferences_pb").read_bytes()
        base_url = f"http://127.0.0.1:{args.port}/v1"
        modified, config_evidence = patched_preferences(original, base_url)
        (out / "settings-configured-mock.preferences_pb").write_bytes(modified)
        # Stop app before atomically replacing preferences, then restore the original app UID/mode.
        adb(args.serial, "shell", "am", "force-stop", args.package)
        temp_remote = "/data/local/tmp/rikka-settings-mock.preferences_pb"
        adb(args.serial, "push", str(out / "settings-configured-mock.preferences_pb"), temp_remote)
        data_path = f"/data/user/0/{args.package}/{STORE_REL}"
        adb(args.serial, "shell", f"cp {temp_remote} {data_path} && chown {uid_gid_mode[0]}:{uid_gid_mode[1]} {data_path} && chmod {uid_gid_mode[2]} {data_path}")
        result["datastore"] = config_evidence
        result["datastore_snapshot_before"] = str(out / "settings-before-send.preferences_pb")
        result["datastore_snapshot_mock"] = str(out / "settings-configured-mock.preferences_pb")

        state = MockState(args.message, args.reply, out / "endpoint.jsonl")
        Handler = type("BoundMockHandler", (MockHandler,), {"state": state})
        server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
        # Verify endpoint on host, then map emulator localhost to the same listener.
        with socket.create_connection(("127.0.0.1", args.port), timeout=3): pass
        adb(args.serial, "reverse", f"tcp:{args.port}", f"tcp:{args.port}")
        reverse = adb(args.serial, "reverse", "--list").stdout
        if f"tcp:{args.port}" not in reverse: raise RuntimeError("adb reverse لم يسجل منفذ mock")

        try:
            import uiautomator2 as u2
        except ImportError as e:
            raise RuntimeError("uiautomator2 غير مثبت؛ ثبته على runner: python -m pip install uiautomator2") from e
        dev = u2.connect(args.serial) if args.serial else u2.connect()
        dev.wait_timeout = 15
        conversation_id = str(uuid.uuid4())
        # Open an explicit fresh chat via RouteActivity extra; the message itself is entered/sent only in UI.
        adb(args.serial, "shell", "am", "start", "-W", "-n", f"{args.package}/me.rerere.rikkahub.RouteActivity", "--es", "conversationId", conversation_id)
        time.sleep(4)
        xml_before = dev.dump_hierarchy(compressed=False)
        (out / "before.xml").write_text(xml_before, encoding="utf-8")
        dev.screenshot(str(out / "before.png"))
        entry = u2_element(dev, xml_before, "chat_input")
        entry.click()
        entry.set_text(args.message)
        time.sleep(1)
        xml_typed = dev.dump_hierarchy(compressed=False)
        (out / "typed-before-send.xml").write_text(xml_typed, encoding="utf-8")
        dev.screenshot(str(out / "typed-before-send.png"))
        if args.message not in xml_typed:
            raise RuntimeError("حقل النص لم يعرض الرسالة بعد إدخالها؛ أُوقف الاختبار قبل الإرسال")
        send = u2_element(dev, xml_typed, "chat_send_button")
        if not send.info.get("enabled", True): raise RuntimeError("زر الإرسال معطّل رغم وجود نص الرسالة")
        send.click()

        deadline = time.time() + args.timeout
        found = False
        while time.time() < deadline:
            if state.error: raise RuntimeError("خطأ داخل mock endpoint: " + state.error)
            if any(args.message in json.dumps(r, ensure_ascii=False) for r in state.requests):
                found = True; break
            time.sleep(0.5)
        if not found: raise RuntimeError("لم يستقبل mock endpoint رسالة الاختبار من UI ضمن المهلة")
        # Require the assistant response to become visible in the ordinary app UI.
        ui_reply = False
        while time.time() < deadline:
            xml_now = dev.dump_hierarchy(compressed=False)
            if args.reply in xml_now:
                ui_reply = True; break
            time.sleep(1)
        if not ui_reply: raise RuntimeError("وصل الطلب للخادم لكن ردّه لم يظهر في واجهة التطبيق ضمن المهلة")
        time.sleep(2)
        xml_after = dev.dump_hierarchy(compressed=False)
        (out / "after.xml").write_text(xml_after, encoding="utf-8")
        dev.screenshot(str(out / "after.png"))
        result["conversation_id"] = conversation_id
        result["endpoint_request_count"] = len(state.requests)
        result["endpoint_requests"] = [{"path": x.get("_request_path"), "model": x.get("model"), "stream": x.get("stream"),
                                         "contains_proof_message": args.message in json.dumps(x, ensure_ascii=False)} for x in state.requests]
        result["reply_visible_in_ui"] = True
        result["ui_evidence"] = {"before_png": str(out / "before.png"), "before_xml": str(out / "before.xml"),
                                 "typed_png": str(out / "typed-before-send.png"), "typed_xml": str(out / "typed-before-send.xml"),
                                 "after_png": str(out / "after.png"), "after_xml": str(out / "after.xml")}

        # Close app so WAL is checkpointed, then pull DB and sidecars with adb root.
        adb(args.serial, "shell", "am", "force-stop", args.package)
        adb(args.serial, "pull", f"/data/user/0/{args.package}/{DB_REL}", str(db_local))
        # Preserve WAL/SHM if present and let SQLite consume any uncheckpointed committed pages.
        for suffix in ("-wal", "-shm"):
            remote = f"/data/user/0/{args.package}/{DB_REL}{suffix}"
            q = adb(args.serial, "pull", remote, str(db_local) + suffix, check=False)
            if q.returncode and suffix == "-wal": pass
        persisted, db_evidence = wait_for_db_message(db_local, args.message, args.reply, timeout=10)
        if not persisted:
            raise RuntimeError("تعذر إثبات رسالة المستخدم ورد المساعد في message_node SQLite بعد الإيقاف")
        result["sqlite_persistent"] = True
        result["sqlite"] = db_evidence
        result["sqlite_copy"] = str(db_local)
        result["status"] = "passed"
    except Exception as exc:
        result["error"] = str(exc)
        try:
            logcat = adb(args.serial, "logcat", "-d", "-t", "1200", check=False)
            (out / "failure-logcat.txt").write_text(logcat.stdout + "\n" + logcat.stderr, encoding="utf-8")
            result["failure_logcat"] = str(out / "failure-logcat.txt")
        except Exception:
            pass
        # Save whatever the real UI currently shows, but never report success on partial evidence.
        if dev is not None:
            try:
                (out / "failure.xml").write_text(dev.dump_hierarchy(compressed=False), encoding="utf-8")
                dev.screenshot(str(out / "failure.png"))
            except Exception: pass
    finally:
        if server is not None:
            server.shutdown(); server.server_close()
        try:
            if args.serial: adb(args.serial, "reverse", "--remove", f"tcp:{args.port}", check=False)
        except Exception: pass
        result["finished_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        result["endpoint_log"] = str(out / "endpoint.jsonl")
        (out / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "evidence_dir": str(out), "error": result.get("error")}, ensure_ascii=False))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"فشل اختبار الإرسال بعد الترقية: {exc}", file=sys.stderr)
        sys.exit(1)
