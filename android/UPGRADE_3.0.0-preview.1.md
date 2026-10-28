# Flux 3.0 Preview 1 — Presets, playlists and schedule management

This is the first implementation stage for the 40-item Flux 3.0 roadmap: items 1–5. It is not the final 3.0 release; the other 35 roadmap items remain planned. Existing 2.5.4 behavior and saved data are retained.

## Named presets

Settings → Default download preset → Custom presets creates, edits, deletes and selects named defaults (up to 20). Each combines video resolution or audio-only, available source container format and a preferred source audio language. Single-video quality selection includes these presets. Choosing a stock default clears the named default. Editing or deleting a preset does not change already-queued selectors.

Source formats are selected without upscaling, transcoding or speech translation. Missing resolution/format matches require another choice; batches skip those items. A language is preferred when an eligible separate track is available, otherwise source audio is used. Choosing an MP4 video source does not guarantee an MP4 final merge: the existing compatible MKV merge policy remains in use. Photo batches keep their original behavior.

## Playlist batches

YouTube playlist results (first 50 accessible entries) include a stock/named preset selector, a displayed-position range selector and Skip identical saved choices. Ranges apply to the displayed results after unavailable/private entries have been excluded. Skip compares canonical source, selected stream choice, media type and a readable saved file. Different formats and missing files are not skipped. Metadata may still need retrieval before a match can be checked.

Batch confirmation snapshots the chosen preset, audio preference, destination folder and new-download schedule. Matching plans are prepared first; cancellation during preparation queues none of that batch. A completion message distinguishes queued items, already-saved choices and unavailable/no-match items. The existing network/power rules apply.

## Schedule management

Downloads → Manage schedules selects up to 100 queued/retrying/paused network downloads. Schedule on an individual item opens the same panel preselected. Choose a date/time or In 1 hour, review the device timezone, then Apply to selected. Remove schedules has its own review; it removes timing, not the download.

Paused jobs stay paused, retries retain their delay, and active, completed and local-edit jobs are not changed. Eligibility is checked again when applying. Existing wake-ups remain harmless and the queue re-evaluates the current times. Android may start later due to network, power or background restrictions.

## Backup and upgrade

New preference backups include named presets/defaults and retain the existing credential/media/history/folder/schedule exclusions. Older preference backups can be imported: the named list is kept and the older stock default is restored. Older Flux versions reject backups containing the new optional named-presets field.

This preview uses the same app ID and update certificate, with a higher version code. It installs over Flux and keeps its data. Android does not install an older APK over a newer version code; do not expect an in-place downgrade to 2.5.4. The stable APK remains separately available on the download page.

## Validation

New native, force-stop/restart and release UI checks cover presets and named backups; resolution/portrait/language/container constraints; immutable batch choices; ranges and readable matching-file skipping; schedule eligibility, review/cancel/apply/removal, pause/retry rules and persistence. The existing 38 gates cover prior download, file integrity, notification, Library selection, media editing, server diagnostics and Threads behavior. Emulator checks are Android30 x86_64; physical OEM behavior and all future roadmap items remain unverified.
