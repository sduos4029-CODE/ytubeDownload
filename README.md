# ytubeDownload

The original Python downloader remains at the repository root.

The Flux Android application is in [`android/`](android/). The 1.9 upgrade adds Library status filters, bulk favourite controls and recorded selection-size summaries while retaining source audio, 4K merging, Library views and interruption recovery.

GitHub Actions builds the Android app, runs generated-media emulator tests and publishes an **unsigned** release artifact. The existing app-update signing key is kept outside GitHub. An unsigned CI APK is not directly installable; the verified artifact must be signed with the existing identity before updating an installed Flux app.

See [`android/TESTING.md`](android/TESTING.md) and the [Android workflow](.github/workflows/flux-android.yml). Native dependency security auditing and live platform compatibility remain ongoing work.
