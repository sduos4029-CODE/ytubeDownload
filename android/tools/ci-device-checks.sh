#!/usr/bin/env bash
set -euo pipefail
mkdir -p test-results
capture_failure() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    adb exec-out screencap -p > test-results/failure.png || true
    adb shell uiautomator dump /sdcard/flux-failure.xml >/dev/null 2>&1 || true
    adb pull /sdcard/flux-failure.xml test-results/failure.xml >/dev/null 2>&1 || true
    adb logcat -d > test-results/logcat.txt || true
  fi
}
trap capture_failure EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
check() {
  local mode="$1" marker="$2"
  local args=()
  if [ "$mode" != baseline ]; then args=(-e "$mode" true); fi
  adb shell am instrument -w -r "${args[@]}" com.flux.downloader.test/com.flux.downloader.RuntimeChecks | tee "test-results/$mode.log"
  python3 tools/assert-marker.py "test-results/$mode.log" "$marker"
}
check serverDiagnosticsOnly 'SERVER DIAGNOSTICS CHECKS PASSED'
check serverOnly 'SERVER RESOLVER CHECKS PASSED'
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$ANDROID_USER_HOME/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$RUNNER_TEMP/flux-release-ui.apk" app/build/outputs/apk/release/app-release-unsigned.apk
adb install -r "$RUNNER_TEMP/flux-release-ui.apk"
check serverUiOnly 'SERVER UI CHECKS PASSED'
adb install -r app/build/outputs/apk/debug/app-debug.apk
check sessionOnly 'SESSION CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check sessionRestartOnly 'SESSION RESTART CHECKS PASSED'
check baseline 'ALL RUNTIME CHECKS PASSED'
check queueOnly 'QUEUE CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check restartOnly 'RESTART CHECKS PASSED'
check transferOnly 'TRANSFER CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check transferPrefsOnly 'TRANSFER PREFERENCE CHECKS PASSED'
check progressOnly 'PROGRESS CHECKS PASSED'
check libraryOnly 'LIBRARY ACTION CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check libraryRestartOnly 'LIBRARY RESTART CHECKS PASSED'
check favouritesOnly 'FAVOURITES CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check favouritesRestartOnly 'FAVOURITES RESTART CHECKS PASSED'
check repeatOnly 'REPEAT CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check repeatRestartOnly 'REPEAT RESTART CHECKS PASSED'
check libraryToolsOnly 'LIBRARY TOOLS CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check libraryToolsRestartOnly 'LIBRARY TOOLS RESTART CHECKS PASSED'
check platformOnly 'PLATFORM CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check platformRestartOnly 'PLATFORM RESTART CHECKS PASSED'
check galleryOnly 'GALLERY CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check galleryRestartOnly 'GALLERY RESTART CHECKS PASSED'
# Use only the runner's temporary debug identity for release UI testing. The
# unsigned artifact is unchanged; the owner's update key never enters CI.
adb install -r "$RUNNER_TEMP/flux-release-ui.apk"
check transferUiOnly 'TRANSFER UI CHECKS PASSED'
check libraryUiOnly 'LIBRARY UI CHECKS PASSED'
check progressUiOnly 'PROGRESS UI CHECKS PASSED'
check favouritesUiOnly 'FAVOURITES UI CHECKS PASSED'
check repeatUiOnly 'REPEAT UI CHECKS PASSED'
check libraryToolsUiOnly 'LIBRARY TOOLS UI CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check platformUiOnly 'PLATFORM UI CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check galleryUiOnly 'GALLERY UI CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check sessionUiOnly 'SESSION UI CHECKS PASSED'
adb shell am force-stop com.flux.downloader
check instagramLiveOnly 'INSTAGRAM LIVE ATTEMPT FINISHED'
adb shell am force-stop com.flux.downloader
check reportedInstagramOnly 'REPORTED REEL ATTEMPT FINISHED'
adb shell am start -n com.flux.downloader/.MainActivity
adb logcat -d > test-results/logcat.txt
