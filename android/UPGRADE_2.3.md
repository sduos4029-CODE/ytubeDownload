# Flux 2.3.0

This combines the planned quality/reliability release and Library organisation release.

- Source codec, bitrate, channel/sample-rate and language details remain available before download. A saved language code selects a matching separate source track where available, with source-ranking fallback and manual override. Audio stays in its source encoding.
- A working-space estimate includes source transfer, merge/save copies and a reserve. Unknown source sizes remain unavailable. The worker checks internal free space before starting. External document-provider free space may be unknown.
- Before publishing a completed file, Flux checks its signature, container tracks and samples, including expected audio. Invalid files fail and pending saves are cleaned. Containers Android cannot inspect are visibly marked unverified; track checks do not promise decoder compatibility or complete playback.
- Set a date/time for new downloads, independently of existing items. Android WorkManager defers future work without holding a foreground service open. Each scheduled item offers Start now; network and power requirements still apply. Charging-only and existing Wi-Fi rules can delay transfers. Android/OEM background limits can delay scheduled starts.
- Pure YouTube playlist links show the first 50 entries for selection; unavailable/private entries are omitted or skipped during format lookup. A watch link with a playlist parameter still opens its single video. Batch preparation is cancellable and shows progress.
- Available manual VTT/SRT subtitle languages can be selected and requested for embedding in MKV. Automatic captions are excluded. Source availability can change between inspection and download; these controls do not invent unavailable subtitles.
- Export/import an allowlisted preferences JSON through Android's document picker. Credentials, server addresses, session cookies, folder grants, schedules and history are excluded. Import requires confirmation and validates every field before changing preferences.
- Collections and up to 12 comma-separated tags persist in history and are searchable. Collection filtering and metadata editing are in Library → Collections & file tools. Select downloads first to edit multiple entries. Blank metadata clears the selected fields.
- Duplicate scanning compares SHA-256 of up to 100 distinct readable content URIs, supports cancellation and removes nothing. Unavailable files are skipped. This can take time on large videos.
- Bulk rename previews sequential file names before changing the actual saved files. Extensions and media bytes are preserved. Provider failures are reported per item; successful renames remain in history. Some providers, including app-specific legacy FileProvider storage, cannot rename through this interface.

Instagram backend hosting remains deferred and unprovisioned. This release does not fix the reported Instagram Reel on the user's phone. The built-in player remains a later upgrade. Live YouTube/playlist/subtitle availability and OEM/physical-device behaviour require separate testing; generated-media CI checks are not a security certification.
