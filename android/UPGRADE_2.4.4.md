# Flux 2.4.4 — direct, precise trimming

Library → More actions → Media tools opens **Trim one video** by default.
Choose Start and End with the two handles or exact time fields, play the range,
then tap **Export trimmed video → Trim & save**. The footer and confirmation
show the kept range and duration. The original stays unchanged; progress appears
in Downloads and the saved result in Library. If the queue is paused the
confirmation explains how to resume it.

Video output defaults to H.264/AAC MP4 at Higher quality. This encodes the chosen
range instead of copying packets from nearby keyframes. Export settings still
allow explicit stream copying, with its precision warning. Encoding/frame and
audio packet boundaries can affect exact duration slightly.

**Join clips** is a separate mode: choose ranges, Add clip, then Review export.
It cannot export an empty clip list. Saved clips, Save/Cancel, ordering and Undo
remain available. Switching away is disabled during an unsaved clip edit.
Start/End values label the handles; the playback pointer is clearly distinguished.
Dragging handles preserves at least 0.1 seconds, Set start/end here cannot create
reversed/empty ranges, and Reset to full video restores the entire selection.

No new permissions, dependencies or backend. Instagram hosting remains deferred.
Preview still uses the source codec. Unsent drafts and a final merged sequence
preview remain outside scope. Physical device/OEM codec coverage is not implied.
