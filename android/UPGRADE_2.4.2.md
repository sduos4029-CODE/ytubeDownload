# Flux 2.4.2 — full-window video editor and multiple clips

Library → More actions → Media tools now opens a dedicated full-window editor.
Video appears first, followed by a playback-pointer slider, play/pause controls,
a two-handle trim range, Set start here / Set end here, and exact time fields.
Scrubbing the playback pointer seeks the saved source without starting playback.
Play selected clip auditions the current range. Changing ranges, leaving the
app, reviewing export or closing the editor pauses/releases preview.

Add clip saves a selection into a clip list. New clip starts another selection.
Select an existing clip to adjust its times, use Up/Down to change output order,
or remove it. A new selection must be added or discarded before list export.
Up to 20 ranges from the same opened source are supported; repeated/overlapping
ranges intentionally repeat those parts. Each combined clip needs at least
0.1 seconds, within the inspected source duration. Total output is at most seven
days. Different source videos, transitions, text overlays and speed changes
are not part of this upgrade.

Two or more selections export to one H.264/AAC MP4: each range is encoded to a
private intermediate file, then those consistent streams join in list order.
The first audio track is retained when present; silent sources stay silent.
Encoding quality is selectable and cannot restore lost detail. No upscaling is
performed except even-dimension padding from the existing H.264 preset. Audio
encoder padding and frame boundaries can make joins/duration differ slightly;
this is not a sample-accurate professional timeline. Each clip is previewable
individually from the original; preview does not render final encoding or play
the whole merged sequence automatically.

Progress reports the current clip and combined editing progress, then a separate
Merging → Verify → Save stage. Merge ETA is unavailable. The original is opened
read-only and export always creates a new file. All intermediate files live in
private no-backup storage and are removed after completion/failure/pause/cancel.
Queued clip ranges/order survive process restart. Paused/interrupted exports
restart; unsent editing selections are not saved as a persistent project.

Space estimates account for repeated source duration; encoded files and joins
can need additional space. Preview depends on device codecs. Existing single
trim, container conversion and audio extraction options remain. No new Android
permissions, dependency or server are added. Instagram hosting remains deferred;
the later full Library player/PiP/background-audio upgrade is separate.
