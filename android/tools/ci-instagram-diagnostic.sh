#!/usr/bin/env bash
set -euo pipefail
mkdir -p test-results
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e instagramDiagnosticOnly true com.flux.downloader.test/com.flux.downloader.RuntimeChecks | tee test-results/instagram-diagnostic.log
python3 tools/assert-marker.py test-results/instagram-diagnostic.log 'INSTAGRAM DIAGNOSTIC FINISHED'
