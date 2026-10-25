# Reproducing the runtime checks

Use a disposable Android emulator (Android 8.0 or newer). The test runner clears Flux history and work requests, and writes generated test files into its download folder. It does not download third-party media.

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.flux.downloader.test/com.flux.downloader.RuntimeChecks
```

Look for `ALL RUNTIME CHECKS PASSED` in the output. An `adb` process exit code alone does not indicate whether the assertions passed.

The Android test APK includes a generated six-second H.264 video and AAC audio, served by a loopback HTTP server inside the test process. A DASH manifest exposes the separate streams. Checks cover:

- MainActivity launch and native Python/yt-dlp initialization.
- Unsupported-URL validation.
- The production DownloadWorker downloading separate streams, running FFmpeg, emitting Downloading/Merging/Saving states, and saving a merged file through MediaStore.
- Android MediaExtractor finding both video and audio tracks with the expected duration.
- Audio-only saving with one readable audio track.
- Cancelling active work without marking it failed or recording a saved file.
- Persisted download history and compatibility with pre-upgrade records.
- Generated 3840×2160 combined H.264/AAC video: replace embedded 32kbps 440Hz AAC with selected external 128kbps 880Hz AAC. Check exactly one audio track, preserved 4K dimensions, identical source/output compressed audio sample hashes, and saved audio metadata.
- Format metadata parsing, explicit audio selection and non-DRC grouped fallback selector.

Local fixture work requests intentionally omit the internet constraint. The authoring emulator's network was not validated because of the environment proxy. Production `Downloads.enqueue` still requires a connected network. These checks do not establish that live YouTube extraction, thumbnails, or all network scheduling conditions work.

Fixtures were generated using FFmpeg's `testsrc2` and `sine` sources at 320×180/24 fps with H.264 and 128 kbps AAC. Independent host checks compare decoded video frame hashes and copied audio packet hashes with the source fixtures; container metadata may differ after remuxing.

A real-device release pass is still needed for live YouTube formats and thumbnails, higher resolutions/codecs, Android 13+ notification permission, background restrictions, and folder-provider behavior.

## Chosen-folder probe

Choose and allow a folder through Settings in the app, then restart its process and run:

```sh
adb shell am force-stop com.flux.downloader
adb shell am instrument -w -r -e folderOnly true com.flux.downloader.test/com.flux.downloader.RuntimeChecks
```

Look for FOLDER CHECKS PASSED. This verifies persisted grants and create/write/read/delete through the selected provider, then removes its probe file.

## Library controls on the release APK

Install the signed release APK over the existing app and keep the matching instrumentation APK installed. Then run `adb shell am instrument -w -r -e uiOnly true com.flux.downloader.test/com.flux.downloader.RuntimeChecks`. This taps List/Grid/Compact and Small/Medium/Large on-screen controls and saves Grid/Medium. Restart the process and run with `-e prefsOnly true` to check persistence. The test APK must be signed by the same identity as the release APK.

Release transport policy can also be checked on the host with `python3 tools/test-network-policy.py`. To regenerate extractor resources, obtain the pinned upstream zipapp and run `python3 tools/package-extractor.py /path/to/yt-dlp`; the helper refuses other upstream SHA-256 values.

## 1.3 queue and restart checks

Use a disposable emulator. Build/install debug app plus instrumentation APK, then run:

```
adb shell am instrument -w -r -e queueOnly true com.flux.downloader.test/com.flux.downloader.RuntimeChecks
```

Look for QUEUE CHECKS PASSED. Generated loopback fixtures exercise non-zero byte-range resumption with exact audio sample hashes, paused queues, cancel-all, ordering, single-pipeline execution, permanent-error isolation, 503 backoff with other work proceeding, and WorkManager interruption. The suite leaves a paused fixture with real partial files.

Then stop/restart the actual application process:

```
adb shell am force-stop com.flux.downloader
adb shell am instrument -w -r -e restartOnly true com.flux.downloader.test/com.flux.downloader.RuntimeChecks
```

Look for RESTART CHECKS PASSED. The in-process fixture server has a new loopback port after restart, so this test updates only the generated test URL to identical content. It verifies partial bytes and ledger survive the real process restart, source Range requests are made, audio samples remain exact, and partials are cleaned after success.

For queue UI checks on the signed release APK (matching test certificate required), run with `-e queueUiOnly true`. This uses generated queue rows, checks Download next and Pause controls, Cancel all confirmation/abort, and preserves completed history. It leaves labelled paused rows for a screenshot; these are test fixtures, never shipped app content.

## GitHub environment and Flux 1.4

The repository workflow builds from android/, regenerates the checksum-pinned extractor, runs release transport checks and lint, then uses a fresh Android 11 x86-64 emulator. See tools/ci-device-checks.sh for the exact sequence and pass-marker assertions.

`-e transferOnly true` runs the new concurrency/Wi-Fi suite. `-e transferPrefsOnly true` after an app process stop verifies settings persistence. `-e transferUiOnly true` checks the release Settings chips/switch. The Wi-Fi test simulates allowed/disallowed transport in a debug build; it does not toggle a real carrier network.

Release builds are unsigned unless FLUX_SIGNING_STORE, FLUX_STORE_PASSWORD, FLUX_KEY_ALIAS and FLUX_KEY_PASSWORD are provided in the local environment. Keep keys outside the source tree. The GitHub unsigned artifact must be signed with the existing app identity for an in-place update; do not install a runner-signed UI-test APK over your own app.
