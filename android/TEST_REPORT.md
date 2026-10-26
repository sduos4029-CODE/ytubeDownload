# Flux 2.1 verification

Inspect the exact-commit GitHub Actions conclusion and artifacts for final pass/fail evidence.

Existing suites cover generated 4K/source-audio merging, queue/retry/force-stop recovery, concurrency/Wi-Fi, structured progress, Library sharing/details/favourites/bulk actions and Instagram video identity.

New gating checks exercise the actual bundled Instagram parser with generated product metadata (photo variants, mixed carousel order, HTTPS candidates, selected identity after reorder and missing-item rejection). Android generated checks cover best-image selection, stable dedup/repeat, JSON migration, byte-exact JPEG transfer/save/MIME, image sharing, Photos filtering, malformed image rejection and photo/source persistence across force-stop. The generic loopback photo fixture uses a media MIME for native extraction; its actual JPEG bytes and extension are verified. This is separate from public Instagram service access.

Release UI uses synthetic mixed-carousel results to preview/reset zoom/download a photo, select one item, cancel and confirm Download all, verify queued identities and copy the saved photo source through Library Photos filtering.

The optional live Android public Instagram Reel attempt records either INSTAGRAM LIVE DOWNLOAD PASSED (metadata, transfer, save, Android media parsing, reported audio-track presence) or UNAVAILABLE. Completion of an attempt is not a live-download pass. Do not claim real-service transfer unless the PASSED marker is present. This single Reel probe does not verify real photo/carousel endpoints, sound fidelity, physical devices, region/account-wide access or all public posts.

Profiles, Stories, Threads, private/login-required content and cookie import remain unsupported. Photo support preserves returned JPEG/PNG/WebP, not arbitrary format conversion. Rate limits and platform changes can prevent access. Full native dependency auditing remains pending. The owner update key stays outside GitHub.
