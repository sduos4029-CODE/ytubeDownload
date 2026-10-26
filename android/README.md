# Flux for Android

Native Kotlin / Jetpack Compose YouTube and public Instagram post downloader for Android 8.0+.

**Delivery status: version 2.1.0 implementation prepared; exact-commit build and runtime verification run in GitHub Actions.** Download, separate-stream merging, audio-only saving, progress states, cancellation, persistent history, and chosen-folder permissions passed the documented checks. Live YouTube search, thumbnails and downloads remain unverified because the environment proxy denied YouTube requests. See `TEST_REPORT.md` for exact coverage and limits. The installable signed APK is delivered separately as `Flux-v2.1.0.apk`.

## Get an APK with GitHub Actions

This repository keeps the app in android/. The root workflow .github/workflows/flux-android.yml builds it on pushes to main or flux/** and through workflow_dispatch.

1. Open Actions → Flux Android build and device checks.
2. Select a successful run for your source commit.
3. Download and unzip Flux-2.1.0-unsigned.
4. Verify app-release-unsigned.apk against its SHA-256 sidecar.
5. Sign it outside GitHub with your persistent app key using Android Build Tools apksigner. Keep the owner key outside the repository and runner.

The unsigned artifact cannot be installed directly. The private Flux download page hosts the separately signed APK, using the existing certificate so installed Flux copies can update without losing history. CI signs a separate copy with an ephemeral key for release UI tests only.

## Build locally

Install Android Studio, Java 17 (or a compatible Gradle JDK), Android SDK Platform 35, and Build Tools 35.0.0. Open this folder as an Android project. Set `ANDROID_HOME` to your SDK or create `local.properties` with `sdk.dir=/absolute/path/to/android-sdk`.

On Linux or macOS, with `curl`, `unzip`, and `sha256sum` installed:

```sh
curl --fail --location --proto '=https' https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp -o /tmp/flux-upstream-ytdlp
python3 tools/package-extractor.py /tmp/flux-upstream-ytdlp
python3 tools/test-network-policy.py
python3 tools/test-instagram.py
./gradlew assembleRelease lintRelease
```

`gradlew` is a small shell bootstrap that downloads Gradle 8.10.2 and checks its official SHA-256 checksum over HTTPS. It is not the standard binary Gradle wrapper. On Windows, install Gradle 8.10.2 and run `gradle assembleRelease lintRelease`, or run the shell bootstrap through WSL. The GitHub workflow installs Gradle directly.

Output: `app/build/outputs/apk/release/app-release-unsigned.apk` unless FLUX_SIGNING_STORE and its signing variables are supplied.

## New in 2.1.0 — Instagram photos and carousels

- Public Instagram photo/Reel/video/carousel links. Text search remains YouTube. Preview photos with pinch zoom/reset, choose individual carousel items or confirm Download all (up to 20 items).
- Save the best returned JPEG/PNG/WebP photo without conversion. Source size is not a promise of original camera quality. Videos retain format selection and source audio/merging.
- Every selected item has an independent queue/progress/save/history record. Photos use Download → Save, including byte/speed/ETA reporting; missing or stale metrics remain unavailable.
- Item identities are stored with the post URL, not expiring CDN URLs. The bundled extractor selects by exact identity, preserves case, handles reordering and fails when an item disappears. Download-again dedup includes item identity and kind; older history defaults remain readable.
- Library adds Photos filtering, local saved-photo thumbnails, image sharing/read-only grants and compatible-viewer opening. Incoming image files must decode with matching JPEG/PNG/WebP MIME before saving.
- Public post extraction retains upstream access checks and HTTPS transport policy. Profiles, Stories, Threads, private/login-required content, cookie import and profile enumeration are unsupported.
- Generated-media and release UI checks are gating. An additional bounded public Instagram download attempt runs on the Android emulator and records PASSED or UNAVAILABLE separately; service/rate-limit failures never count as live-download success. Physical-device/OEM and broad platform compatibility remain unverified.

## Included from 2.0.0

- Instagram/YouTube source-aware link/share validation, quality metadata, history source copying and platform namespaces.
- Unknown resolution, codec, size or audio remain honestly unavailable. Searches clear stale results.

## Included from 1.9.0

- Library status menu filters All states / Completed / Failed / Cancelled, combined with title search, media type, favourites and sorting. Status is recorded history state, not a fresh availability scan.
- Select up to 100 entries and Favourite / Unfavourite completed entries in one action. Non-completed entries are skipped, and changes persist through restart.
- The selection toolbar shows the sum of known recorded completed-file sizes, explicitly counts unknown sizes and avoids overflowing totals. It is not current disk usage or a free-space estimate; files may have changed or been removed.
- Selection stays across filters, with the selected count and size summary reflecting all selected records. Select results replaces selection with up to 100 current matches.
- Existing sharing, history removal, per-item favourites/details, Download again and unavailable-file replacement remain.

## Included from 1.8.0

- Completed Library entries have Download again in the file menu, with confirmation before queuing. File details also offer Download replacement when a completed file cannot be opened.
- A new job gets a fresh ID, clean progress/retry/save state and the current download folder. The original history, favourite mark and saved file remain untouched. A paused queue stays paused.
- Matching active or paused jobs are reused by source video, format selection and audio mode instead of duplicating repeat requests. Show existing download only navigates; it does not queue a new copy if that job finishes meanwhile.
- The same quality selection is reused, and yt-dlp refreshes source metadata/URLs during execution. If the old format is unavailable, select another format in Discover. Availability probes cannot distinguish every deleted/offline/revoked provider case.
- Repeats accept validated HTTPS YouTube video links. No automatic recovery starts merely from opening file details, and no files are deleted by this feature.

## Included from 1.7.0

- Library file menus add/remove favourites, which persist across process restarts. A star marks favourites; the Favourites filter combines with title search, media filters and sorting.
- File details show provider-reported name/size, recorded download size, MIME format, quality, duration, recorded audio metadata, added date, destination type and original source.
- Availability is checked when details open or Check file again is tapped. Missing, inaccessible or offline files are reported as unavailable without deleting history. A successful check means the provider opened a read descriptor; it is not a full integrity scan.
- Copy source link accepts only ordinary HTTPS YouTube URLs. No browser or sharing app opens automatically.
- Menus keep Compact and Small Grid cards usable; existing per-file sharing remains in the menu, and bulk sharing is unchanged. Removing history also removes that entry's favourite mark, while saved files remain.
- Older history defaults to not favourited. No file is renamed, rewritten or moved by favouriting.

## Included from 1.6.0

- Structured current-stream progress: transferred/total bytes, reported speed and estimated remaining time. Estimated totals and percentages carry a tilde.
- Video, audio and combined stream labels. Stream switching resets the percentage for the new stream; it is not whole-download progress.
- Download / Merge / Save stage display. Merge remains indeterminate; saving shows local file-copy percentage. ETA excludes later streams, merging and saving.
- Unknown values stay unavailable. Rates older than five seconds disappear; live metrics are memory-only and reset after interruption/restart.
- Every concurrent task keeps independent telemetry. Existing file sharing, quality, resume, folder and Library controls remain.

## Included from 1.5.0

- Share one saved download or select up to 100 files for the Android share sheet. Only selected content URIs receive temporary read access; Flux grants no write access.
- Library multi-select works with List, Grid and Compact views. Select results chooses up to 100 matching results across the current search/type filter; Done or Android Back leaves selection mode.
- Remove selected history with confirmation. Saved files remain in their folder; failed partials are cleaned. Active work and unfinished save records are protected.
- Missing or revoked files show an error before opening the share sheet; no partial subset is silently shared.
- GitHub build instructions now describe the actual root workflow, unsigned artifacts and local signing process.

## Included from 1.4.0

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

- Search YouTube by words (up to five results), inspect supported YouTube/Instagram video links, or share those links into Flux.
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
- `../.github/workflows/flux-android.yml`: build, lint, emulator checks and unsigned release artifact.
- `LibraryActions.kt`: safe file sharing and selective history removal.

Uses AndroidX, Coil, yt-dlp via youtubedl-android, and the youtubedl-android FFmpeg package. Review upstream licenses, native dependency compatibility, platform terms, and distribution requirements before publishing.

## License

Flux source is supplied under GPL-3.0; see `LICENSE` and `THIRD_PARTY_NOTICES.md`.
