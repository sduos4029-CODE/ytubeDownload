# Flux 1.2.0 — verification

15 October 2026. Android 11 / API 30 x86-64 software emulator. Minimum API 26; target API 35. ARM devices and other Android versions were not runtime-tested.

## Passed

- Debug and release builds, Android test APK and release lint complete. Release lint has no errors; nonblocking warnings include newer AndroidX dependencies and launcher icon guidance.
- Release compiled manifest excludes debug flag and Compose PreviewActivity. APK v2 signature verifies and matches 1.1.0 signing identity, allowing update installation.
- Resolved dependency graph uses Jackson core/databind 2.22.3, Commons IO 2.22.0 and Commons Compress 1.28.0. OSV has no matches for those selected versions or bundled yt-dlp 2026.8.19 at review time.
- Release extractor rejects plain HTTP, loopback HTTP, FTP, WS, scheme-relative HTTP and HTTPS downgrade in policy tests. Direct release zipapp HTTP invocation fails with Flux requires HTTPS. Only guarded urllib handler is enabled.
- Host generated 4K fixture merges with the exact secure downloader/postprocessor settings and explicit audio replacement.
- Android debug fixture suite: app launch, updated native Python/yt-dlp initialization (extractor 2026.08.19), unsupported-link validation, audio metadata/selection, legacy history migration, Library search/type filters/size/date sorting, video+audio download and merge, progress states, MediaStore save, 4K audio replacement, audio-only, cancellation and persisted history. Suite ends ALL RUNTIME CHECKS PASSED.
- Generated 3840×2160 H.264 fixture contains weak embedded AAC; chosen separate AAC replaces it. MediaExtractor finds one video and one audio track, preserves dimensions/duration, and compressed audio sample hashes match the selected source exactly.

- Exact release APK installed over the earlier app with its retained signing identity. A matching signed instrumentation APK tapped actual Library controls: List, Grid, Compact, Small, Medium and Large; all state assertions passed (LIBRARY UI CHECKS PASSED). Compiled release package flags exclude DEBUGGABLE.

- Grid/Medium preferences survive a process restart (PREFERENCE CHECKS PASSED).
- Exact distributed release APK contains the hash-matching main extractor and the HTTPS-only policy, with no debug loopback exception; resource optimization renames its APK entry to res/Nc.

## Test distinctions and limits

Generated fixture download tests run the same production worker in the debug variant, which has a compile-time loopback HTTP exception absent from release. The release transport policy is tested separately. Test WorkManager requests omit the validated-internet prerequisite because the proxy-connected emulator network is not validated; production retains its network constraint. External YouTube access was blocked, so live search, thumbnails and downloads are unverified. The software emulator encounters System UI ANRs under load; these do not establish phone performance. Earlier native package caches were present during the fixture run, so fresh native archive extraction with updated Apache dependencies requires an additional fresh-install device check.

See SECURITY_1.2.md for mitigations and remaining native/signing limits. No guarantee of vulnerability-free operation is made. Existing files remain unchanged; layout resizing changes cards on screen, not video resolution or file sizes.
