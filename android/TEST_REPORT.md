# Flux 1.6 verification

The root GitHub workflow builds the exact source commit and emits verification logs. Do not treat this source document as proof of a successful run: inspect the associated run conclusion and artifacts.

Existing generated-media suites cover 4K/audio integrity, queue/retry and actual process-restart recovery, concurrency limits and simulated Wi-Fi loss/resume. New suites cover single/multiple share intent streams and read-only ClipData grants, invalid/missing files, selection limits, selective history removal, retained saved bytes, protected active/pending-save records and removal persistence after force-stop. Release UI checks cover selection, confirmation dismissal/acceptance and selection exit.

The share-intent test validates preparation and URI access within Flux. It does not automate sending files to a third-party app. Native device/platform variation, recipient behavior, real Wi-Fi/cellular handovers and live YouTube remain unverified. A full native-library security audit is pending.

CI builds unsigned and signs only a separate UI-test copy using an ephemeral runner key. The owner update identity remains outside GitHub.

New progress checks validate exact, estimated and unavailable values, bad input and stale estimates; real generated-media transfers must emit structured bytes/speed/ETA with separate video/audio streams and independent concurrent job IDs. Release UI checks cover stages, unavailable estimates, indeterminate merging and saving percentage. No overall job ETA is claimed.
