# Flux 1.3.0 — verification

16 October 2026. Android 11 / API 30 x86-64 software emulator. Minimum API 26; target API 35. The signed release retains the existing private signing identity for in-place updates. ARM and other Android versions remain untested.

## Passed

- Debug/release builds, instrumentation build and release lint: no lint errors (12 nonblocking warnings).
- Baseline Android media regression suite ends ALL RUNTIME CHECKS PASSED: native engine, validation, separate streams, progress states, saving, generated 3840×2160 audio replacement with exact sample hashes, audio-only, cancellation and persisted history.
- Targeted queue suite ends QUEUE CHECKS PASSED. Active pause retained 13407 partial bytes in private no-backup storage. Resume used a non-zero HTTP Range and the output's compressed audio samples matched the generated source exactly. Partial files were removed after completion.
- Paused queues hold newly added downloads; cancel-all preserves completed history and clears queue hold.
- Download next changes fetch order; at most one pipeline is active. Cancelled queued items are skipped. A permanent 403 fails without automatic retries and does not block subsequent work.
- A temporary 503 schedules retry 1/3, other ready work completes during backoff, and the failed item subsequently completes automatically with exact source audio samples. Retry policy excludes permission/storage/access-denied failures and selects 30/60/120 second delays.
- WorkManager interruption stops the native process, retains partial bytes and returns state to Queued. A paused fixture with real partial data is durably persisted for the separate process-restart check.
- Release HTTPS policy tests pass. Manifest excludes debuggability and Compose PreviewActivity. The notification receiver is not exported and action PendingIntents are immutable. APK v2 signature verifies and matches the prior signing certificate.

- Real process restart check ends RESTART CHECKS PASSED. The paused ledger and partial bytes survived Force stop/restart, the downloader requested a non-zero Range, exact source audio samples were preserved, and staging data was removed on completion.

- Exact signed release APK installed over the debug/test version with the retained signing identity. Release UI test ends QUEUE UI CHECKS PASSED: Download next reorders an item, Pause changes its state, cancel-all confirmation can be dismissed or confirmed, and completed history is preserved. First UI attempts were blocked by a software-emulator System UI ANR and stale generated fixture state; the final clean run passed after the test harness reset fixtures and supported scrolling.
- The exact distributed release embeds the checksum-matching HTTPS-only extractor, with no debug loopback exception. Package version is 1.3.0 / code 5; receiver export and non-debuggable flags were checked in the compiled manifest.
- APK SHA-256: 1a625aa9a0982e2886e1149190445757efdb96f31f063fceca108000210591ef; 157913429 bytes. No native dependency audit or new all-dependency vulnerability certification is implied by these checks.

## Test method and limitations

Generated fixture transfers use the same production worker in the debug variant, which has a compile-time loopback HTTP exception absent from release. Test work requests omit validated-network constraints because the emulator's proxy-connected network is not validated. Production requests still require CONNECTED. No third-party media is downloaded for these tests. The in-process source server changes port across a real app restart, so only the generated fixture URL is updated to identical content before resuming. Live YouTube search, thumbnails, current format availability and real YouTube resume are unverified because this environment blocks YouTube access.

Retry policy and one actual transient retry are tested; all possible errors, long backoff exhaustion, revoked document-provider permissions, disk-full behavior, notification action taps and saving-stage crash windows were not fully exercised. Pending-save URI recording/cleanup is implemented but not fault-injected at every instruction boundary. Existing native package caches were present; fresh native extraction and newer Android/OEM foreground-service behavior still need device testing.

Partial continuation depends on the source. Some streams restart or require selecting a currently available format. Android Force stop requires reopening Flux before recovery. Paused and failed partials use device storage until resumed, cancelled or cleared; uninstall removes them. Downloads from pre-1.3 versions may restart once because they did not retain durable partials. Full native-library security audit and production signing-key migration remain pending as documented in SECURITY_1.2.md.
