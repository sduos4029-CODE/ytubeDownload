# Flux 1.2.0 security changes

This is a mitigation update following SECURITY_REVIEW.md, which describes the previous 1.1.0 artifact.

- Distributed release variant is non-debuggable and excludes Compose development preview components. APK v2 signature verifies. Existing private signing identity is retained for updates; its certificate subject still reads Android Debug. No private key is shipped in the source or APK. A production signing-key migration and protected release pipeline remain future work.
- Pinned yt-dlp 2026.08.19 is bundled. Original artifact SHA-256 1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6 matches upstream release checksum. The source-controlled packaging helper adds the Flux transport policy deterministically and emits a new resource hash. Engine initialization checks installed extractor bytes against that APK-authenticated hash and atomically replaces stale/modified copies.
- Separate executable updates are removed. Settings opens the HTTPS download page for signed APK updates. Android signature checking protects replacement installations; user should retain the existing app and install over it.
- Resolved Jackson core/databind 2.22.3, Commons IO 2.22.0 and Commons Compress 1.28.0 replace affected transitive versions. OSV returned no advisory matches for these four versions and yt-dlp 2026.8.19 at review time. This is selected-component coverage, not a guarantee that every dependency is vulnerability-free.
- APK-bundled transport policy allows only the guarded urllib request handler and rejects plain HTTP/FTP/WS URLs, scheme-relative HTTP, and HTTP requests including redirects. FFmpeg input protocol whitelist excludes remote network protocols. Debug resource has a compile-time loopback-only exception for generated local media tests; release resource does not. Certificate verification remains enabled. The wrapper is a maintained patch that must be retested whenever the extractor is updated.
- Ignore external yt-dlp config and plugin directories. Restrict saved outputs to supported media extensions.
- Manifest disables backup, with explicit cloud/device-transfer exclusion resources. Device/OEM behavior has not been penetration-tested.
- Persisted folder grants are retained only for the current destination, queued/running work and saved Library files. Obsolete grants are released during initialization, folder changes, history clearing and work completion. Runtime worker references prevent revocation in the middle of cancellation cleanup. Saved Library files intentionally keep their folder permission so Open continues to work.

## Remaining limitations

Bundled native FFmpeg 7.1.4, Python 3.12.11 and OpenSSL 3.5.2 were not rebuilt or exhaustively audited in this update. Security of every enabled codec/demuxer and native backport provenance remains unverified. Updating Java libraries and the extractor does not certify those runtimes. No full native fuzzing, malware sandbox scan or third-party service upload was performed. Live YouTube extraction remains blocked/unverified in this environment; new HTTPS enforcement intentionally refuses sources that require insecure transport.

Before public/store release, complete native dependency patch/provenance review, production signing arrangements, ARM and current-Android security/runtime testing, and a broad SBOM-based advisory scan.
