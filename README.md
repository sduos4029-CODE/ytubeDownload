# ytubeDownload

The original Python downloader remains at the repository root.

The Flux 2.2 preview includes an optional anonymous Instagram resolver connection,
but its backend is not hosted automatically. If a public post returns no thumbnail
or formats, see [the hosting setup](HOSTING.md). Hosting priority is
**Google Cloud Run first, Render second**. The [Google Cloud guide](server/GOOGLE_CLOUD.md)
requires a billing-enabled project and owner-started deployment; Render remains
the fallback. Neither hosting provider nor the user's phone transfer has been
verified.

The Flux Android application is in [`android/`](android/). The 2.1.1 upgrade adds an optional encrypted on-device Instagram connection for requests that fail anonymously, alongside the 2.1 photo/carousel features and YouTube while retaining source audio, 4K merging, Library views and interruption recovery.

GitHub Actions builds the Android app, runs generated-media emulator tests and publishes an **unsigned** release artifact. The existing app-update signing key is kept outside GitHub. An unsigned CI APK is not directly installable; the verified artifact must be signed with the existing identity before updating an installed Flux app.

See [`android/TESTING.md`](android/TESTING.md) and the [Android workflow](.github/workflows/flux-android.yml). Native dependency security auditing and live platform compatibility remain ongoing work.
