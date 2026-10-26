# Flux 2.1.1 verification

Inspect the final exact-commit GitHub Actions conclusion, logs and artifacts for pass/fail evidence. Existing 26 suites remain. Three new suites cover session storage/security, force-stop recovery and release connection controls. Live control/reported-Reel attempts are separate from gating generated checks.

The real bundled CookieJar and Instagram extractor are exercised with generated credentials and a mocked authenticated media-info response. Tests verify that the saved session selects the authenticated API and is only attached to Instagram HTTPS, never YouTube, unrelated/CDN hosts or HTTP. No fixture credential is sent to a real Instagram server.

Android checks cover AES-GCM ciphertext/non-exportable key, tampering detection, cookie input bounds, separate owner-only request files, FileProvider exclusion, active-request change protection, cleanup/disconnect and process-start removal of orphan files. Release UI opens the non-exported/screenshot-protected official login activity, rejects incomplete login and disconnects a generated saved connection. Fake sessions are cleared before all real service probes.

A prior exact-Reel API 35 diagnostic matched DezDcSUR2Gg, decoded its thumbnail and saved video with both video/audio tracks. Current release workflow repeats the public control and reported-Reel probes. Public live success must be read from explicit PASSED markers, not merely workflow completion. Physical devices, session expiry/provider edge cases, sound fidelity and all-post compatibility remain unverified.

There is no real Instagram account available for automated testing: live authenticated-session success is not verified. Connection must be established by the user through Instagram's official page on their device. Sessions remain local and encrypted, with temporary plaintext CookieJar files only during native Instagram requests. No passwords are read by Flux, no JavaScript bridge or SSL exception handler is added, and backup is disabled. Full native-library security auditing remains pending. The owner APK-update signing key stays outside GitHub.
