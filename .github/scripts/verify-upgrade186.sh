#!/usr/bin/env bash
set -euo pipefail

# Run from any directory. Override RIKKA_REPO_ROOT only when the candidate checkout
# is not the sibling /workspace/rikka-upstream-candidate-20261020 directory.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${RIKKA_REPO_ROOT:-$SCRIPT_DIR/../..}"
ROOT="$(cd -- "$ROOT" && pwd)"
OLD_APK="$ROOT/previous-apk/app-universal-release.apk"
NEW_APK="$ROOT/app/build/outputs/apk/release/app-universal-release.apk"
HELPER="$ROOT/.github/scripts/verify-apk-upgrade_data.py"
DATA_HELPER="$SCRIPT_DIR/upgrade186-data.py"
EVIDENCE="$ROOT/upgrade-verification"
WORK="$EVIDENCE/work"
PACKAGE="me.rerere.rikkahub.arabic.dev"
ACTIVITY="$PACKAGE/me.rerere.rikkahub.RouteActivity"
REMOTE="/data/user/0/$PACKAGE"
DB_NAME="rikka_hub"
DB="$REMOTE/databases/$DB_NAME"
PREFS="$REMOTE/files/datastore/settings.preferences_pb"
EXPECTED_OLD_CODE=186
EXPECTED_CERT_SHA256="27326144bdee8eefa9cd7a39e80c3a5322e8332419b86d4b13cfecbbe9eb7b73"

fail() { echo "FAIL: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "required command unavailable: $1"; }

[[ -f "$OLD_APK" && -s "$OLD_APK" ]] || fail "known previous APK missing: $OLD_APK"
[[ -f "$NEW_APK" && -s "$NEW_APK" ]] || fail "candidate APK missing: $NEW_APK"
[[ -f "$HELPER" && -s "$HELPER" ]] || fail "original fixture helper missing: $HELPER"
[[ -f "$DATA_HELPER" && -s "$DATA_HELPER" ]] || fail "host data verifier missing: $DATA_HELPER"
need adb
need python3
need sha256sum
BT=$(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
export PATH="$BT:$PATH"
need apksigner

mkdir -p "$EVIDENCE" "$WORK"
python3 "$DATA_HELPER" --helper "$HELPER" self-test | tee "$EVIDENCE/host-self-test.txt"

# APK hashes and signing certificate fingerprints are safe metadata; APKs and private keys
# are never copied into the evidence directory.
OLD_SHA="$(sha256sum "$OLD_APK" | awk '{print $1}')"
NEW_SHA="$(sha256sum "$NEW_APK" | awk '{print $1}')"
cert_sha256() {
  local apk="$1" out fingerprint
  out="$(apksigner verify --verbose --print-certs "$apk" 2>&1)" || { printf '%s\n' "$out" >&2; return 1; }
  fingerprint="$(printf '%s\n' "$out" | awk -F': ' '/certificate SHA-256 digest:/ {print $NF}' | tr -d ':' | sort -u | tr '[:upper:]' '[:lower:]')"
  [[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || { printf '%s\n' "$out" >&2; return 1; }
  printf '%s' "$fingerprint"
}
OLD_CERT="$(cert_sha256 "$OLD_APK")" || fail "could not verify previous APK signer"
NEW_CERT="$(cert_sha256 "$NEW_APK")" || fail "could not verify candidate APK signer"
[[ "$OLD_CERT" == "$EXPECTED_CERT_SHA256" ]] || fail "previous APK signing certificate mismatch: $OLD_CERT"
[[ "$NEW_CERT" == "$EXPECTED_CERT_SHA256" ]] || fail "candidate APK signing certificate mismatch: $NEW_CERT"
printf 'previous_apk_sha256=%s\nprevious_cert_sha256=%s\ncandidate_apk_sha256=%s\ncandidate_cert_sha256=%s\n' \
  "$OLD_SHA" "$OLD_CERT" "$NEW_SHA" "$NEW_CERT" > "$EVIDENCE/apk-metadata.txt"

# This task must run on a pristine, rootable Google APIs API 35 x86_64 emulator.
SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
ABI="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$SDK" == 35 ]] || fail "expected emulator API 35; got SDK=$SDK"
[[ "$ABI" == x86_64 ]] || fail "expected x86_64 emulator; got ABI=$ABI"
adb root > "$EVIDENCE/adb-root.txt" 2>&1 || { cat "$EVIDENCE/adb-root.txt" >&2; fail 'adb root required; refusing to proceed'; }
adb wait-for-device
ROOT_UID="$(adb shell id -u | tr -d '\r')"
[[ "$ROOT_UID" == 0 ]] || fail "emulator root check failed (uid=$ROOT_UID)"
printf 'sdk=%s\nabi=%s\nroot_uid=%s\n' "$SDK" "$ABI" "$ROOT_UID" > "$EVIDENCE/emulator.txt"
adb shell mkdir -p /data/local/tmp/rikka-native
adb push "$ROOT/upgrade-native/." /data/local/tmp/rikka-native/ > "$EVIDENCE/push-native-checker.txt"
adb shell chmod 755 /data/local/tmp/rikka-native/android-db-check
native_snapshot() {
  local label="$1" libphase="$2"
  local remote="/data/local/tmp/rikka-native"
  adb shell mkdir -p "$remote/state"
  adb push "$WORK/$label" "$remote/state/" > "$EVIDENCE/push-native-state-$label.txt"
  adb shell "LD_LIBRARY_PATH=$remote/$libphase $remote/android-db-check $remote/$libphase/libsqlite3x.so $remote/$libphase/libsimple.so $remote/state/$label/$DB_NAME" > "$EVIDENCE/native-$label.json"
}

# Never overwrite an installed app or pre-existing data: the upgrade must start fresh.
if [[ -n "$(adb shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r')" ]]; then
  fail "package already installed; use a fresh emulator (no uninstall/clear is performed)"
fi

adb install "$OLD_APK" | tee "$EVIDENCE/install-previous.txt"
grep -q '^Success' "$EVIDENCE/install-previous.txt" || fail 'previous APK install did not report Success'
adb shell dumpsys package "$PACKAGE" > "$EVIDENCE/package-previous.txt"
grep -Eq "versionCode=${EXPECTED_OLD_CODE}([[:space:]]|$)" "$EVIDENCE/package-previous.txt" || fail "previous package versionCode is not $EXPECTED_OLD_CODE"

launch_capture() {
  local phase="$1"
  adb logcat -c
  if [[ "$phase" == previous-seeded || "$phase" == update ]]; then
    adb shell am start -W -n "$ACTIVITY" --es conversationId f9fba7c0-cc8d-4e40-bac7-0a18ef4d7131 | tee "$EVIDENCE/launch-$phase.txt"
  else
    adb shell am start -W -n "$ACTIVITY" | tee "$EVIDENCE/launch-$phase.txt"
  fi
  grep -q 'Status: ok' "$EVIDENCE/launch-$phase.txt" || fail "$phase launch failed"
  sleep 12
  adb shell pidof "$PACKAGE" > "$EVIDENCE/pid-$phase.txt" || true
  [[ -s "$EVIDENCE/pid-$phase.txt" ]] || fail "$phase app process did not remain alive"
  adb logcat -d > "$EVIDENCE/logcat-$phase.txt"
  if grep -A 8 'FATAL EXCEPTION' "$EVIDENCE/logcat-$phase.txt" | grep -Fq "$PACKAGE"; then
    fail "$phase app crashed during startup"
  fi
}

# First real app launch creates Room schema 31 and Preferences DataStore; no DB is fabricated.
launch_capture previous-initialize
adb shell test -f "$DB" || fail "Room database was not initialized: $DB"
adb shell test -f "$PREFS" || fail "Preferences DataStore was not initialized: $PREFS"

pull_state() {
  local label="$1"
  local dest="$WORK/$label"
  mkdir -p "$dest"
  adb pull "$DB" "$dest/$DB_NAME" > "$EVIDENCE/pull-$label-db.txt"
  adb pull "$PREFS" "$dest/settings.preferences_pb" > "$EVIDENCE/pull-$label-settings.txt"
  if adb shell test -f "$DB-wal"; then adb pull "$DB-wal" "$dest/$DB_NAME-wal" >> "$EVIDENCE/pull-$label-db.txt"; fi
  if adb shell test -f "$DB-shm"; then adb pull "$DB-shm" "$dest/$DB_NAME-shm" >> "$EVIDENCE/pull-$label-db.txt"; fi
}

adb shell am force-stop "$PACKAGE"
pull_state pristine
# Import and invoke the repository's original fixture writer; it supplies the exact
# application-compatible Arabic conversation/message JSON and PreferencesProto encoding.
python3 "$DATA_HELPER" --helper "$HELPER" seed "$WORK/pristine/$DB_NAME" "$WORK/pristine/settings.preferences_pb" | tee "$EVIDENCE/seed.txt"
# An additional low-impact setting (suggestions disabled) is encoded with the same
# imported PreferencesProto codec. No messages are sent and no provider/network is used.
: > "$WORK/pristine/$DB_NAME-wal"

APP_UID="$(adb shell stat -c %u "$DB" | tr -d '\r')"
PACKAGE_UID="$(adb shell stat -c %u "$REMOTE" | tr -d '\r')"
[[ "$APP_UID" =~ ^[0-9]+$ && "$APP_UID" == "$PACKAGE_UID" && "$APP_UID" -ge 10000 ]] || fail 'app DB owner does not match installed app UID'
printf 'app_uid=%s\n' "$APP_UID" > "$EVIDENCE/app-uid.txt"
adb push "$WORK/pristine/$DB_NAME" "$DB" > "$EVIDENCE/push-seed-db.txt"
adb push "$WORK/pristine/$DB_NAME-wal" "$DB-wal" > "$EVIDENCE/push-seed-wal.txt"
adb push "$WORK/pristine/settings.preferences_pb" "$PREFS" > "$EVIDENCE/push-seed-settings.txt"
adb shell "chown $APP_UID:$APP_UID '$DB' '$DB-wal' '$PREFS' && chmod 660 '$DB' '$DB-wal' '$PREFS'"

# Previous release reads the actual fixture. Snapshot is deliberately after this read
# and before the candidate replacement install.
launch_capture previous-seeded
adb exec-out screencap -p > "$EVIDENCE/screenshot-before.png"
adb shell am force-stop "$PACKAGE"
pull_state before-upgrade
native_snapshot before-upgrade stable
python3 "$DATA_HELPER" --helper "$HELPER" snapshot "$WORK/before-upgrade/$DB_NAME" \
  "$WORK/before-upgrade/settings.preferences_pb" previous-release \
  "$EVIDENCE/before-upgrade.json" --expected-version 31 --native-snapshot "$EVIDENCE/native-before-upgrade.json" \
  | tee "$EVIDENCE/before-upgrade-query.txt"

# The sole app replacement operation. Deliberately no uninstall, clear-data, or data removal.
adb install -r "$NEW_APK" | tee "$EVIDENCE/install-update.txt"
grep -q '^Success' "$EVIDENCE/install-update.txt" || fail 'candidate replacement install did not report Success'
adb shell dumpsys package "$PACKAGE" > "$EVIDENCE/package-update.txt"
NEW_CODE="$(sed -nE '/versionCode=/{s/.*versionCode=([0-9]+).*/\1/p;q;}' "$EVIDENCE/package-update.txt")"
[[ "$NEW_CODE" =~ ^[0-9]+$ ]] && (( NEW_CODE > EXPECTED_OLD_CODE )) || fail "candidate code ($NEW_CODE) is not greater than $EXPECTED_OLD_CODE"

launch_capture update
adb exec-out screencap -p > "$EVIDENCE/screenshot-after.png"
adb shell am force-stop "$PACKAGE"
UPDATED_UID="$(adb shell stat -c %u "$DB" | tr -d '\r')"
[[ "$UPDATED_UID" == "$APP_UID" ]] || fail "package/data UID changed across replacement ($APP_UID -> $UPDATED_UID)"
printf 'previous_app_uid=%s\nupdated_app_uid=%s\n' "$APP_UID" "$UPDATED_UID" > "$EVIDENCE/uid-comparison.txt"
pull_state after-upgrade
native_snapshot after-upgrade candidate
python3 "$DATA_HELPER" --helper "$HELPER" snapshot "$WORK/after-upgrade/$DB_NAME" \
  "$WORK/after-upgrade/settings.preferences_pb" candidate-release \
  "$EVIDENCE/after-upgrade.json" --expected-version 32 --native-snapshot "$EVIDENCE/native-after-upgrade.json" \
  | tee "$EVIDENCE/after-upgrade-query.txt"
python3 "$DATA_HELPER" compare "$EVIDENCE/before-upgrade.json" "$EVIDENCE/after-upgrade.json" \
  | tee "$EVIDENCE/logical-comparison.txt"
printf 'previous_version_code=%s\ncandidate_version_code=%s\nprevious_schema=31\ncandidate_schema=32\nresult=PASS\n' \
  "$EXPECTED_OLD_CODE" "$NEW_CODE" > "$EVIDENCE/result.txt"
echo "PASS: signed in-place schema-31 to schema-32 upgrade; candidate versionCode $NEW_CODE."
