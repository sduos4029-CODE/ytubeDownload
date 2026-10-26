# Flux 1.9 verification

The root GitHub workflow builds the exact source commit and emits verification logs. Inspect the run conclusion and artifacts for pass/fail evidence.

Existing suites cover generated 4K/audio integrity, queue/retry/restart, concurrency/Wi-Fi, structured progress, Library sharing/removal, favourites/details and repeat-download/recovery. New checks cover combined status/type/search/favourite filtering, known/unknown/overflow selection sizes, bounded idempotent bulk favourite changes, protection of non-completed entries and unchanged file bytes. A separate process restart checks favourite persistence and file access. Release UI uses actual search, status menu, filtered selection, size summary and Favourite/Unfavourite controls with scrolling.

Selection sizes are recorded history values for completed entries; they do not measure current disk use or certify availability. Unknown sizes are counted, failed/cancelled entries are excluded, and overflow is reported as unavailable.

Tests use generated media. Live YouTube, physical devices, provider-specific failures, third-party share recipients and full native dependency security auditing remain unverified. CI builds unsigned and signs a separate UI-test copy with an ephemeral key; the owner update key stays outside GitHub.
