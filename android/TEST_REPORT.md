# Flux 1.7 verification

The root GitHub workflow builds the exact source commit and emits verification logs. Inspect the associated run and verification artifacts for pass/fail evidence.

Existing suites cover generated 4K/audio integrity, queue/retry and actual process-restart recovery, concurrency and simulated Wi-Fi, structured stream progress, Library sharing and selective history removal. New checks cover legacy/default favourite migration, JSON and process-restart persistence, combined search/type/favourite filtering, mark/unmark, provider metadata and missing-file handling, unchanged saved bytes and validated source links. Release UI checks use actual menu/filter/details/refresh/clipboard controls with scrolling.

File availability means a granted provider descriptor could be opened at check time. It does not certify file integrity or continued availability. Source-copy tests remain inside the app/emulator and do not contact YouTube. Live YouTube, physical devices, third-party share recipients, OEM background behavior and full native dependency security auditing remain unverified.

CI builds unsigned and signs only a separate UI-test copy with an ephemeral runner key. The owner update identity stays outside GitHub for local signing.
