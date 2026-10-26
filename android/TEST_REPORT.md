# Flux 1.8 verification

The root GitHub workflow builds the exact commit and produces verification logs. Inspect its run conclusion and artifacts for pass/fail evidence.

Existing Android suites cover generated 4K/audio integrity, queue/retry and actual process-restart recovery, concurrency/Wi-Fi, structured progress, Library sharing/removal and favourites/details. New checks cover validated repeat sources and canonical aliases, fresh IDs/current-folder/reset state, concurrent duplicate coalescing, paused queues and unchanged original history/favourite/file bytes. A generated-media repeat must produce a distinct saved URI and preserve source audio; a separate process restart checks both original and repeated history/files. Release UI covers confirmation dismissal, queueing, existing-job navigation and missing-file replacement.

The native repeat check queues a valid fixture-source record and substitutes only its transfer URL with the generated loopback server before execution. Release validation is unchanged; no loopback bypass is added. Live YouTube and continued availability of old format IDs are not established by this test. File availability is a descriptor probe, not an integrity scan.

Physical devices, provider-specific storage edge cases, third-party share recipients, OEM background limits and a full native dependency audit remain unverified. CI builds unsigned and signs a separate UI-test copy with an ephemeral key; the owner update key stays outside GitHub.
