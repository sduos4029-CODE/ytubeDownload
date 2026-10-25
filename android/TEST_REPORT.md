# Flux 1.4 verification

This release adds bounded parallel transfers and a Wi-Fi-only preference. Automated evidence is produced by the GitHub Actions workflow for the exact branch commit; see the linked run and its verification-report artifact for pass/fail results.

The workflow checks baseline 4K/audio merging, queue ordering and retries, actual process-restart resume, 2/3-way concurrency, lowering the limit without overfilling slots, simulated Wi-Fi loss and byte-range recovery, persisted preferences, and release Settings controls. It fails if an instrumentation pass marker is absent, even if adb itself exits successfully. Fresh emulator installation also exercises native package extraction with the resolved dependencies.

Wi-Fi handover is simulated through a debug-only test override, which is rejected in release builds. Release UI runs with a temporary CI signing identity; the distributed app is signed afterward with the existing private identity. CI retains the original unsigned artifact unchanged. No private update key is uploaded.

Only generated local media is used. These checks do not establish live YouTube compatibility, all real mobile/Wi-Fi/VPN transitions, every Android version or every device's foreground-service behavior. A full native-library security audit remains outstanding. Resume is source-dependent. Background quotas and Force stop still apply.
