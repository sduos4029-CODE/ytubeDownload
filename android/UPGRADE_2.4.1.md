# Flux 2.4.1 — trim selection and preview

Library → More actions → Media tools → Trim clip now offers a two-handle range
slider alongside precise timestamp fields. Both controls update the same range
used by export. Play selected clip seeks to its start and stops at its end.
Pause preview stops playback; replay starts from the selected start again.
Changing the range, reviewing export, switching operations, closing the dialog
or leaving the app pauses/releases playback. Preview remains on-device.

Preview uses Android device codecs and the original saved file, not a rendered
export. Unsupported codecs show a preview error without preventing export.
Audio-only sources can be auditioned too. Export quality/encoding is applied
later; stream-copy cuts may differ at packet/keyframe boundaries. Display updates
and endpoint stopping are polled, so preview stopping is approximate, not a
sample-accurate editor. No new permissions or dependency are added.

2.4 media tools and original-file protection remain. This patch is not the later
full Library player, PiP or background audio upgrade. Instagram hosting remains
pending. Physical-device and OEM codec compatibility need real-device checks.
