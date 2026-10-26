# Flux 2.0 verification

Inspect the exact-commit GitHub Actions conclusion and verification artifacts for pass/fail evidence.

Existing suites cover generated 4K/audio, queue/retry/restart, concurrency/Wi-Fi, progress, Library/favourites/details/bulk actions and repeat/recovery. New platform checks cover HTTPS host/route/shared-text validation, Instagram URL and namespace preservation, honest unknown/no-audio formats, rejected photos/carousels/profiles/Threads, source-copy/repeat compatibility and process-restart persistence. A generated A/V transfer validates source audio; only the transport URL is substituted with the loopback fixture, so this is not a live Instagram download test. Release UI uses a synthetic Instagram result to select quality and queue its correct source URL, copy its source and reject a profile without stale results.

A separate host probe used the same bundled extractor with no optional impersonation backend. One upstream public Instagram Reel returned nine formats. Only metadata was retrieved. It does not certify all public posts, real Android Meta service requests or CDN downloads.

Photos, carousels, Stories, profiles, Threads, private/login-required media and cookie import are unsupported in this phase. Platform access/rate limits and changes can prevent public requests. Physical devices, third-party share recipients, provider edge cases and full native dependency auditing remain unverified. The owner update key stays outside GitHub.
