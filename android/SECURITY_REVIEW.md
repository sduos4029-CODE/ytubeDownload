# Flux 1.1.0 security review

Reviewed 15 October 2026. Verdict: the APK needs security hardening before production distribution. No remotely exploitable attack against the normal Flux workflow was demonstrated. This is a source, APK configuration, dependency and bytecode review, not a security certification or malware sandbox verdict.

## Artifact and method

- APK: `/workspace/Flux-v1.1.0.apk`, 163438844 bytes.
- SHA-256: `e6cd0c77d8845d800bbbb6b1c27bc3c5141a7ad7536fc845ed3ed4459496419c`.
- Package `com.flux.downloader`, versionCode 3, minimum Android 8/API 26, target API 35.
- Inspected the compiled manifest using Android Build Tools, verified APK signatures, reviewed all application Kotlin code, inspected downloader/updater/archive helper JVM bytecode from the resolved dependencies, and read embedded native version strings.
- Resolved the actual debug runtime dependency graph offline, then queried OSV for five selected dependency versions. OSV matches are component-version matches, not independent, demonstrated vulnerabilities in Flux. Aliases and feature-specific findings must not be counted as separate exploitable app issues.
- Consulted upstream advisory pages. Existing Android runtime evidence establishes that the production native worker successfully downloads loopback HTTP fixtures even though the manifest disallows cleartext traffic. No emulator was restarted for this review, and no malicious media or remote exploit was run.
- Evidence files are in `/workspace/security-review/`; compiled manifest evidence is `/workspace/test-results/security-manifest.txt`.

## Prioritized findings

### 1. Medium: distributed debug build exposes debugging and development components

Confirmed in the shipped binary: `android:debuggable=true`, certificate subject `CN=Android Debug`, and exported `androidx.compose.ui.tooling.PreviewActivity`. Gradle includes `ui-tooling` through debugImplementation. Debugging can expose private app data and execution to an authorized ADB/debugging session; it does not give every ordinary installed app access to Flux data.

APK Signature Scheme v2 verification succeeds. The signing certificate matches the prior version. Neither the certificate subject nor the public certificate proves that the private signing key is publicly known; no such claim is made.

Remediation: distribute a release variant with debugging disabled and development tooling absent. Establish protected, persistent production signing and an explicit migration plan from the installed debug certificate. A different signing key normally cannot install over this package as a standard update; preserve user files/history during migration. Obfuscation alone is not a fix for debugging or dependency vulnerabilities.

Evidence: `app/build.gradle.kts`, compiled manifest, `security-review/signature.txt`.

Reference: https://developer.android.com/privacy-and-security/risks/android-debuggable

### 2. Medium, conditional: bundled dependencies contain known vulnerable versions

The APK bundles yt-dlp **2025.09.26**. Its version is read directly from `res/raw/ytdlp` → `yt_dlp/version.py`, not inferred from a Maven version. The runtime dependency graph also resolves Jackson core/databind **2.11.1**, Commons IO **2.5**, and Commons Compress **1.12**. OSV reports advisories for all five queried versions.

Examples and applicability:

| Advisory | Component condition | Observed Flux applicability |
| --- | --- | --- |
| GHSA-g3gw-q23r-pgqm / CVE-2026-26331 | yt-dlp command injection with `--netrc-cmd`; fixed 2026.02.21 | Flux does not enable this option. No direct exposure demonstrated. |
| GHSA-vx4q-3cr2-7cg2 / CVE-2026-50574 | Fragmented manifests downloaded with aria2c; fixed 2026.06.09 | Flux does not bundle/select aria2c. No direct exposure demonstrated. |
| GHSA-69qj-pvh9-c5wg | Unsafe yt-dlp `--exec` metadata expansion | Flux does not enable `--exec`. No direct exposure demonstrated. |
| GHSA-6v4j-43gg-vj32 | Generated shortcut files from `--write-link` variants; fixed 2026.07.04 | Flux does not generate shortcuts. No direct exposure demonstrated. |
| GHSA-c6mh-fpjc-4pr3 / CVE-2026-50023 | Unsafe shortcut extensions permitted as media/subtitle output; fixed 2026.06.09 | Flux restricts initial URLs to YouTube and does not request subtitle/thumbnail output. Its model and worker lack a strict final media-extension allowlist, so additional defense is appropriate. Android execution of a malicious shortcut was not demonstrated. |
| GHSA-h46c-h94j-95f3 / CVE-2025-52999 | Deeply nested JSON can overflow Jackson core's stack | The updater parses release JSON with Jackson. Its normal source is upstream release metadata over HTTPS; attacker control of this response was not demonstrated. Flux's own search parsing uses org.json. |
| GHSA-gwrp-pvrq-jmwv / CVE-2021-29425 | Commons IO FilenameUtils.normalize path handling | The dependency is affected by version. No use of the vulnerable method with attacker-controlled paths was identified in reviewed application/helper paths. |
| GHSA-78wr-2p64-hpwj / CVE-2024-47554 | Commons IO XmlStreamReader resource consumption | No applicable XmlStreamReader call was identified in the reviewed flow. |

Other Jackson/Compress advisory matches are retained in `security-review/osv-results.json`; their exploitability was not established. Do not interpret the above as confirmed remote code execution or arbitrary file access in Flux.

Embedded ARM64 native strings identify FFmpeg **7.1.4**, Python **3.12.11**, and OpenSSL **3.5.2**. These need a separate current patch/provenance review. Native backports and every codec path were not mapped to CVEs; no specific FFmpeg/OpenSSL exploit is claimed. Updating yt-dlp inside Settings does not update these native libraries or the Maven dependencies.

Remediation: bundle a currently reviewed, patched extractor; align/upgrade transitive Jackson and Apache dependencies with Android compatibility tests; rebuild patched native runtimes from traceable sources. Add dependency monitoring, a resolved SBOM and a strict allowed-media-extension check before saving/opening output. Disable unused downloader configuration/plugin loading where practical. Recheck live extraction, merging, cancellation and Android compatibility after upgrades.

References:

- https://github.com/yt-dlp/yt-dlp/security/advisories/GHSA-g3gw-q23r-pgqm
- https://github.com/yt-dlp/yt-dlp/security/advisories/GHSA-vx4q-3cr2-7cg2
- https://github.com/yt-dlp/yt-dlp/security/advisories/GHSA-c6mh-fpjc-4pr3
- https://github.com/yt-dlp/yt-dlp/security/advisories/GHSA-6v4j-43gg-vj32
- https://osv.dev/vulnerability/GHSA-69qj-pvh9-c5wg
- https://osv.dev/vulnerability/GHSA-h46c-h94j-95f3
- https://commons.apache.org/proper/commons-compress/security.html
- https://ffmpeg.org/security.html

### 3. Medium, conditional: executable extractor updates lack independent verification

`FluxModel.updateEngine()` invokes the dependency's stable updater. The inspected updater downloads the release asset using `FileUtils.copyURLToFile`, then copies it to the executable yt-dlp location. No checksum, detached-signature validation, pinned signing key, or rollback protection was found in this path.

HTTPS provides transport authentication; this is not an unauthenticated HTTP updater, nor proof of an actual compromised update. However, a compromised upstream release account/infrastructure or trusted TLS interception could supply code running with Flux's app permissions, including its selected-folder access. User initiation of an update does not authenticate the downloaded code independently.

Remediation: verify release signatures using a bundled trusted public key before installation, enforce acceptable versions and download limits, and replace the executable atomically. A checksum fetched from the same compromised source helps corruption detection but is not equivalent to an independently trusted signature. An alternative is distributing vetted extractor updates through signed APK updates.

Evidence: `MainActivity.kt` updateEngine; `security-review/Updater-bytecode.txt` download/update methods.

### 4. Medium hardening gap: Android cleartext policy does not cover the native downloader

The compiled application declares `usesCleartextTraffic=false`. Nevertheless, the earlier instrumented tests successfully used the production DownloadWorker to download and merge files over loopback **HTTP** through bundled Python/yt-dlp. This confirms that the manifest declaration alone does not enforce an HTTPS-only policy on this subprocess.

Initial search URLs are validated against HTTPS YouTube hosts. No actual YouTube request was observed downgrading to HTTP. The remaining concern is media/manifest URLs and redirects resolved inside the extractor, where Flux does not independently enforce HTTPS. A reachable cleartext leg could expose or allow modification of metadata/media on an untrusted network.

Remediation: enforce transport/protocol checks for resolved resources and redirects in the downloader layer, including FFmpeg network inputs where applicable. Keep any HTTP exception confined to test configuration. Retain TLS certificate validation. Domain restrictions must account for legitimate YouTube CDN hosts and redirects rather than merely checking the initial link.

Evidence: manifest, `DownloadWorker.kt` request construction, `RuntimeChecks.kt` HTTP fixture server, `test-results/audio-final-runtime-checks.log`.

Reference: https://developer.android.com/guide/topics/manifest/application-element

### 5. Low privacy issue: old selected-folder grants remain retained

The folder picker takes persistent read/write URI permission. Choosing another folder or resetting to default only changes the stored URI; no `releasePersistableUriPermission` call was found. Flux therefore retains access to previously chosen folders beyond their use as the current destination. Existing queued jobs might legitimately still need an old grant, so blindly revoking on selection could break downloads.

Remediation: track grants used by queued work and revoke obsolete grants once no job needs them; provide an explicit control for forgetting folder access. Explain that clearing history does not delete saved files or necessarily revoke folder access.

Evidence: `MainActivity.kt` folder picker/onReset and `Engine.kt` Store.setFolder.

## Positive controls verified

- No camera, microphone, contacts, SMS, accessibility, package-install, or broad all-files permission in the compiled manifest. Network, notifications, foreground work, wake lock and boot receiver permissions match the current implementation.
- Backup is disabled in the manifest. OEM/device-to-device transfer behavior was not tested; explicit data-extraction rules remain advisable.
- FileProvider is not exported, grants only temporary URI permissions and exposes an app-specific Download subtree, not the filesystem root.
- WorkManager's exported job/diagnostic components are protected by BIND_JOB_SERVICE or DUMP. Their exported status alone is not an unprotected component vulnerability. MainActivity is intentionally exported for launcher/share functionality.
- Shared links are validated again by the HTTPS YouTube host allowlist. No automatic download is triggered by a share: selection still requires a user action, although inspection starts automatically.
- Download invocations use ProcessBuilder argument lists, not a shell containing video titles or search text. The library's shell-based cancellation helper uses process IDs; no attacker-controlled shell input was identified there.
- Output basenames remove separators/control characters; job IDs are generated UUIDs. No application-level path traversal from shared text was found.
- The ZIP helper checks canonical entry paths against the destination before extraction. Its symlink targets and all archive edge cases were not fully audited; this observation does not certify arbitrary untrusted ZIP handling.
- Private history uses app-private preferences. Public downloads are intentionally outside private storage; users wanting private files should choose an appropriate destination. No hardcoded application secrets, ad SDK or analytics integration was identified in the reviewed application code; this is not an exhaustive native binary malware scan.
- The ARM64 FFmpeg launcher inspected has a non-executable stack, GNU_RELRO and BIND_NOW. This does not establish every bundled library's hardening or absence of memory-safety bugs.

## Scope and next step

No source behavior, APK, signing configuration or hosted download was changed by this review. The existing APK remains version 1.1.0 with the hash above. Prioritize release build/signing, dependency updates and verified extractor updates, then re-test transport handling and permissions on supported Android versions. A hardened APK needs a separate build and validation cycle; the current APK must not be described as fixed or vulnerability-free.

Not performed: full native source/provenance audit, all-dependency CVE coverage, fuzzing, traffic interception, ARM runtime penetration testing, malware service upload, or testing the user's installed extractor version after in-app updates. External malware scanning would involve sending the APK to a third party; none was done.
