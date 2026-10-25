# Flux for Android

Native Kotlin / Jetpack Compose YouTube downloader for Android 8.0+.

**Delivery status: version 1.4.0 compiled and tested with generated media on an Android 11 emulator.** Download, separate-stream merging, audio-only saving, progress states, cancellation, persistent history, and chosen-folder permissions passed the documented checks. Live YouTube search, thumbnails and downloads remain unverified because the environment proxy denied YouTube requests. See `TEST_REPORT.md` for exact coverage and limits. The installable signed APK is delivered separately as `Flux-v1.4.0.apk`.

## Get an APK with GitHub Actions

1. Create a GitHub repository and upload **the contents of this folder**, including `.github` (the Gradle settings must be at the repository root).
2. Open **Actions → Build Android APK → Run workflow**. The workflow also runs on pushes to `main` or `master`.
3. When the job succeeds, download the **Flux-release-apk** artifact and unzip it.
4. Transfer `app-release.apk` to your Android phone and open it. Allow installation from that file source if Android asks.

The APK is signed with the runner's debug key. Each fresh runner can generate a different key; replacing an earlier debug build may require uninstalling it, which removes app history and app-specific files. Use a persistent signing key for release builds. No upload to an app store is configured. The supplied private download page hosts the separately built and verified APK.

## Build locally

Install Android Studio, Java 17 (or a compatible Gradle JDK), Android SDK Platform 35, and Build Tools 35.0.0. Open this folder as an Android project. Set `ANDROID_HOME` to your SDK or create `local.properties` with `sdk.dir=/absolute/path/to/android-sdk`.

On Linux or macOS, with `curl`, `unzip`, and `sha256sum` installed:

```sh
./gradlew assembleRelease lintRelease
```

`gradlew` is a small shell bootstrap that downloads Gradle 8.10.2 and checks its official SHA-256 checksum over HTTPS. It is not the standard binary Gradle wrapper. On Windows, install Gradle 8.10.2 and run `gradle assembleRelease lintRelease`, or run the shell bootstrap through WSL. The GitHub workflow installs Gradle directly.

Output: `app/build/outputs/apk/release/app-release.apk`.

## New in 1.4.0

- Settings supports 1–3 simultaneous downloads. Each task owns its process ID, progress and staging directory. Reducing the limit lets existing items finish; increasing it fills additional slots.
- Wi-Fi-only preference prevents new transfers without active Wi-Fi and interrupts active network transfers when the connection changes. Partial data is retained and can resume when Wi-Fi returns. This is an application policy, not an OS firewall: bytes already in flight during a network handover cannot be guaranteed to be zero. VPNs that hide their underlying Wi-Fi transport may wait conservatively.
- Both preferences persist across process restart, with existing installations defaulting to one transfer and any connected network.
- GitHub Actions builds an unsigned release and runs generated-media emulator regression tests. The owner signs the CI artifact outside GitHub using the existing key; that key is never committed or uploaded to the runner.

## Included from 1.3.0

- One-at-a-time durable download queue with per-item pause/resume/cancel, Pause all / Resume all, Cancel all confirmation, Retry now and Download next.
- Partial media lives in app-private no-backup storage rather than a disposable cache. Pause and recoverable interruptions preserve data; completion and cancellation clean it up. Failed attempts retain data for a manual retry until history is cleared. An uninstall still removes private partial data/history.
- Up to three queue-level automatic retries for temporary network failures, using 30/60/120 second backoff. Access-denied, unavailable formats and storage/permission errors do not loop automatically. Other ready queue items run during backoff.
- Native requests refresh source metadata and use yt-dlp continuation support. Resume depends on byte-range/fragment support and source availability; some streams restart. In-progress pre-1.3 downloads may restart once because older versions did not retain durable partials.
- WorkManager network interruptions return work to queued state. Existing requests and opening Flux recover unfinished work. After Android Force stop, reopen Flux; Android intentionally prevents background recovery until then. Background execution and Android foreground-service limits still apply.
- Interrupted saves record their pending destination URI durably and remove unfinished output before retry. Cancellation waits for native process shutdown before another item accesses its files. Notification controls use a private receiver and immutable PendingIntents.

## Included from 1.2.0

- Original Flux F/download-arrow logo, adaptive Android launcher icon and Android 13 monochrome icon resource.
- Library List / Grid / Compact views and Small / Medium / Large card sizing. Layout, size and sort preference persist across restart.
- Local title search, All / Video / Audio filters, newest/oldest/title/size sorting. Read available file sizes for earlier history entries without moving their files.
- Non-debuggable release APK with development preview activity absent; updated yt-dlp 2026.08.19, Jackson 2.22.3, Commons IO 2.22.0 and Commons Compress 1.28.0.
- Unused persisted folder grants are released when no current folder, active worker, queued job or saved Library file needs them. Clearing old history can release old grants; saved files remain on disk.

The distributed APK retains the existing private signing identity for in-place updates. Its certificate subject still says Android Debug; the distributed application itself is non-debuggable. This is a private compatibility release, not a completed production signing-key migration or full native security certification. See SECURITY_1.2.md.

## Included behavior

- Search by words (up to five results), inspect YouTube links, or share a YouTube link into Flux.
- Display thumbnails, titles, channels, and duration.
- List every returned video format with its source resolution, frame rate, container, codec, and estimated size. There is no arbitrary resolution cap; options depend on the upload and what YouTube exposes.
- List source audio with codec, reported bitrate, sample rate, channels and language. Keep extractor quality ranking; prefer non-DRC streams in automatic video pairing. Files remain in their original format, such as M4A or WebM/Opus; MP3 conversion is not implemented.
- Pair video with the best available source audio or an explicitly chosen audio track. When separate audio exists, replace a combined format's embedded audio with the chosen track. FFmpeg stream-copies into MKV without re-encoding; saved history shows audio codec/sample-rate/channel metadata when Android can read it. A compatible player such as VLC may be required. Resolution does not determine audio quality, and missing source detail cannot be restored.
- Show current-stream download percentage, an animated merge bar, and file-copy percentage. Merge status depends on the extractor's emitted messages; yt-dlp does not provide a reliable numerical merge percentage through this callback. Download percentage can reset when the next stream starts. File size shown for video-only options excludes companion audio.
- Background downloads using WorkManager foreground execution, cancellation, retry from history, and notifications (optional Android 13+ permission).
- Persistent local history with file-opening actions. No cloud account or analytics.
- Default folder: `Download/Flux` on Android 10+. Android 8–9 use app-specific external Downloads, removed on uninstall. The system folder picker lets the user grant another writable folder; no broad storage permission is required. New folder settings apply to new jobs.
- Extractor updates arrive with signed APK updates; the separate executable updater has been removed. The extractor zipapp is verified against an APK-bundled hash on initialization. Release HTTP/HTTPS downgrade requests are rejected by the bundled network policy; debug-only loopback exceptions support tests. FFmpeg network fallback is restricted to local protocols.

## Device verification before release

The debug build has compiled successfully. Before release, verify the following on real Android devices:

- Search and URL inspection on a public video; share a link from YouTube.
- Download an integrated video format, a high-resolution video-only format with separate audio, and M4A/Opus audio. Check duration, sound, playback, and thumbnail.
- Exercise merge output, audio/video sync, byte progress, concurrent downloads, screen lock, background execution, pause/resume, interruption recovery and cancellation at each stage.
- Select a document-provider folder, restart the app, download again, and open the saved file. Test revoked access, deleted files, insufficient space, and failed connections.
- Deny and grant notification permission; test Android 8, 10, 13, and 15 foreground behavior.
- Run `lintDebug`; check 16 KB page-size compatibility of all native libraries on affected devices. The pinned native downloader/FFmpeg artifacts may need updates for current Android releases.

Public YouTube availability is not guaranteed. Bot checks, region/age restrictions, extractor changes, JavaScript runtime requirements, and protected media can prevent downloads. This implementation does not bypass access controls or supply authenticated cookies. Save content you own or have permission to download.

## Main files

- `Engine.kt`: extractor initialization, search, formats, durable local ledger.
- `DownloadWorker.kt`: yt-dlp/FFmpeg execution, progress, cancellation, notifications, SAF/MediaStore saves.
- `MainActivity.kt`: Compose Discover, Downloads, Library, Settings, quality sheet.
- `.github/workflows/android.yml`: build, lint, downloadable debug APK artifact.

Uses AndroidX, Coil, yt-dlp via youtubedl-android, and the youtubedl-android FFmpeg package. Review upstream licenses, native dependency compatibility, platform terms, and distribution requirements before publishing.

## License

Flux source is supplied under GPL-3.0; see `LICENSE` and `THIRD_PARTY_NOTICES.md`.
