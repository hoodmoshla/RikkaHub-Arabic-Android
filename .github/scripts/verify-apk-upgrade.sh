#!/usr/bin/env bash
set -euo pipefail

PACKAGE="me.rerere.rikkahub.arabic.dev"
ACTIVITY="$PACKAGE/me.rerere.rikkahub.RouteActivity"
OLD_APK="previous-apk/app-universal-release.apk"
NEW_APK="app/build/outputs/apk/release/app-universal-release.apk"
EXPECTED_OLD_VERSION_CODE="${EXPECTED_OLD_VERSION_CODE:-178}"
EXPECTED_OLD_VERSION_NAME="${EXPECTED_OLD_VERSION_NAME:-3.0.0.178}"
HELPER=".github/scripts/verify-apk-upgrade_data.py"
EVIDENCE="upgrade-verification"
WORK="$EVIDENCE/work"
REMOTE="/data/user/0/$PACKAGE"
DB_NAME="rikka_hub"
PREFS="$REMOTE/files/datastore/settings.preferences_pb"
DB="$REMOTE/databases/$DB_NAME"

mkdir -p "$EVIDENCE" "$WORK"
python3 "$HELPER" self-test | tee "$EVIDENCE/host-self-test.txt"
[[ -s "$OLD_APK" ]] || { echo "Previous APK missing: $OLD_APK" >&2; exit 1; }
[[ -s "$NEW_APK" ]] || { echo "Update APK missing: $NEW_APK" >&2; exit 1; }

# CI must be running the requested rootable API 35 x86_64 Google APIs emulator.
SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
ABI="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$SDK" == 35 ]] || { echo "Expected API 35 emulator; found SDK=$SDK" >&2; exit 1; }
[[ "$ABI" == x86_64 ]] || { echo "Expected x86_64 emulator; found ABI=$ABI" >&2; exit 1; }
adb root > "$EVIDENCE/adb-root.txt" 2>&1 || { cat "$EVIDENCE/adb-root.txt" >&2; echo 'adb root is required; refusing to run without root.' >&2; exit 1; }
ROOT_UID="$(adb shell id -u | tr -d '\r')"
[[ "$ROOT_UID" == 0 ]] || { echo "adb root failed closed (uid=$ROOT_UID)" >&2; exit 1; }
printf 'sdk=%s\nabi=%s\nroot_uid=%s\n' "$SDK" "$ABI" "$ROOT_UID" > "$EVIDENCE/emulator.txt"

# Never take over a pre-existing app/data set: this test requires a fresh emulator.
if [[ -n "$(adb shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r')" ]]; then
  echo "App is already installed on this emulator; refusing to overwrite existing app data." >&2
  exit 1
fi

adb install "$OLD_APK" | tee "$EVIDENCE/install-previous.txt"
grep -q '^Success' "$EVIDENCE/install-previous.txt"
adb shell dumpsys package "$PACKAGE" > "$EVIDENCE/package-previous.txt"
grep -Eq "versionCode=${EXPECTED_OLD_VERSION_CODE}([[:space:]]|$)" "$EVIDENCE/package-previous.txt" || {
  echo "Previous APK is not expected release code $EXPECTED_OLD_VERSION_CODE (requested v3.0.0.178)." >&2
  exit 1
}
grep -Fq "versionName=${EXPECTED_OLD_VERSION_NAME}" "$EVIDENCE/package-previous.txt" || {
  echo "Previous APK versionName does not match $EXPECTED_OLD_VERSION_NAME." >&2
  exit 1
}

launch_and_check() {
  local phase="$1"
  adb logcat -c
  adb shell am start -W -n "$ACTIVITY" | tee "$EVIDENCE/launch-${phase}.txt"
  grep -q 'Status: ok' "$EVIDENCE/launch-${phase}.txt"
  sleep 12
  adb shell pidof "$PACKAGE" > "$EVIDENCE/pid-${phase}.txt"
  [[ -s "$EVIDENCE/pid-${phase}.txt" ]] || { echo "$phase app process is not alive after startup" >&2; exit 1; }
  adb logcat -d > "$EVIDENCE/logcat-${phase}.txt"
  if grep -A 8 'FATAL EXCEPTION' "$EVIDENCE/logcat-${phase}.txt" | grep -Fq "$PACKAGE"; then
    echo "$phase app crashed during startup" >&2
    exit 1
  fi
  adb shell am force-stop "$PACKAGE"
}

# First launch creates the real Room-31 schema and the AndroidX Preferences DataStore files.
launch_and_check previous-initialize
adb shell test -f "$DB" || { echo "Room database not initialized at $DB" >&2; exit 1; }
adb shell test -f "$PREFS" || { echo "Preferences DataStore not initialized at $PREFS" >&2; exit 1; }

pull_state() {
  local label="$1" dest="$WORK/$label"
  mkdir -p "$dest"
  adb pull "$DB" "$dest/$DB_NAME" > "$EVIDENCE/pull-${label}-db.txt"
  adb pull "$PREFS" "$dest/settings.preferences_pb" > "$EVIDENCE/pull-${label}-settings.txt"
  # Include the SQLite WAL when present so the host-side seed/checkpoint sees committed state.
  if adb shell test -f "$DB-wal"; then adb pull "$DB-wal" "$dest/$DB_NAME-wal" >> "$EVIDENCE/pull-${label}-db.txt"; fi
  if adb shell test -f "$DB-shm"; then adb pull "$DB-shm" "$dest/$DB_NAME-shm" >> "$EVIDENCE/pull-${label}-db.txt"; fi
}

pull_state pristine
python3 "$HELPER" seed "$WORK/pristine/$DB_NAME" "$WORK/pristine/settings.preferences_pb" | tee "$EVIDENCE/seed.txt"
# On a brand-new package install the test database may have an existing WAL. Push an empty
# WAL over it (rather than deleting app data) after the host checkpoint has merged transactions.
: > "$WORK/pristine/$DB_NAME-wal"
UID="$(adb shell dumpsys package "$PACKAGE" | tr -d '\r' | sed -nE 's/.*userId=([0-9]+).*/\1/p' | head -n1)"
[[ "$UID" =~ ^[0-9]+$ ]] || { echo "Could not determine installed app UID" >&2; exit 1; }
adb push "$WORK/pristine/$DB_NAME" "$DB" > "$EVIDENCE/push-seed-db.txt"
adb push "$WORK/pristine/$DB_NAME-wal" "$DB-wal" > "$EVIDENCE/push-seed-wal.txt"
adb push "$WORK/pristine/settings.preferences_pb" "$PREFS" > "$EVIDENCE/push-seed-settings.txt"
adb shell "chown $UID:$UID '$DB' '$DB-wal' '$PREFS' && chmod 660 '$DB' '$DB-wal' '$PREFS'"

# Old release must successfully consume the seeded model and leave its records intact.
launch_and_check previous-seeded
pull_state after-previous-launch
python3 "$HELPER" capture "$WORK/after-previous-launch/$DB_NAME" \
  "$WORK/after-previous-launch/settings.preferences_pb" previous-release \
  "$EVIDENCE/previous-release-data.json" | tee "$EVIDENCE/previous-release-query.txt"

# Replacement install only: no uninstall, clear-data, or package-data removal between releases.
adb install -r "$NEW_APK" | tee "$EVIDENCE/install-update.txt"
grep -q '^Success' "$EVIDENCE/install-update.txt"
adb shell dumpsys package "$PACKAGE" > "$EVIDENCE/package-update.txt"
NEW_VERSION_CODE="$(sed -nE '/versionCode=/{s/.*versionCode=([0-9]+).*/\1/p;q;}' "$EVIDENCE/package-update.txt")"
[[ "$NEW_VERSION_CODE" =~ ^[0-9]+$ ]] && (( NEW_VERSION_CODE > EXPECTED_OLD_VERSION_CODE )) || {
  echo "Updated package version code ($NEW_VERSION_CODE) is not newer than $EXPECTED_OLD_VERSION_CODE" >&2
  exit 1
}
launch_and_check update
pull_state after-update-launch
python3 "$HELPER" capture "$WORK/after-update-launch/$DB_NAME" \
  "$WORK/after-update-launch/settings.preferences_pb" updated-release \
  "$EVIDENCE/updated-release-data.json" | tee "$EVIDENCE/updated-release-query.txt"
python3 "$HELPER" compare "$EVIDENCE/previous-release-data.json" "$EVIDENCE/updated-release-data.json" \
  | tee "$EVIDENCE/logical-comparison.txt"
printf 'previous_version_code=%s\nupdated_version_code=%s\nresult=PASS\n' \
  "$EXPECTED_OLD_VERSION_CODE" "$NEW_VERSION_CODE" > "$EVIDENCE/result.txt"
echo "Signed in-place data-preserving upgrade passed: $EXPECTED_OLD_VERSION_CODE -> $NEW_VERSION_CODE."
