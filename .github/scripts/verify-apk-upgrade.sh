#!/usr/bin/env bash
set -euo pipefail
PACKAGE=me.rerere.rikkahub.arabic.dev
ACTIVITY="$PACKAGE/me.rerere.rikkahub.RouteActivity"
mkdir -p upgrade-verification
# A real PackageManager replacement install proves certificate/package/version compatibility.
# Room migration tests separately verify preservation of populated conversation databases.
adb install previous-apk/app-universal-release.apk | tee upgrade-verification/install-previous.txt
adb shell am start -W -n "$ACTIVITY" | tee upgrade-verification/launch-previous.txt
sleep 8
adb shell am force-stop "$PACKAGE"
adb logcat -c
adb install -r app/build/outputs/apk/release/app-universal-release.apk | tee upgrade-verification/install-update.txt
adb shell am start -W -n "$ACTIVITY" | tee upgrade-verification/launch-update.txt
sleep 15
adb logcat -d > upgrade-verification/logcat.txt
adb shell dumpsys package "$PACKAGE" > upgrade-verification/package.txt
adb shell pidof "$PACKAGE" > upgrade-verification/pid.txt
# pidof exits nonzero if startup crashed. Restrict fatal checks to this app, not emulator noise.
if grep -A 3 'FATAL EXCEPTION' upgrade-verification/logcat.txt | grep -q "$PACKAGE"; then
  echo 'Updated Arabic app crashed on launch' >&2
  exit 1
fi
grep -q 'Success' upgrade-verification/install-update.txt
grep -q 'Status: ok' upgrade-verification/launch-update.txt
echo 'Signed in-place upgrade and startup passed.'
