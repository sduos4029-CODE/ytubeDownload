# Flux 2.4.3 — simpler clip workflow

Open Library → More actions → Media tools. Choose a range and tap Add clip in
the fixed bottom controls. It immediately saves that clip and prepares the next
range, beginning at the previous clip's end. There is no New clip mode switch.
When the source end is reached, the next selection returns to the whole source.

Added clips appear in a compact horizontal strip with their times and duration.
Tap a card to adjust it: the editor scrolls back to the preview, and changes
require Save clip. Cancel clip edit leaves the saved range unchanged. Arrow
buttons move clips earlier/later; removing a clip offers one-level Undo removal.
Undo is cleared by another add/save/reorder so it cannot discard later changes.

Add clip and Review export stay visible while scrolling. A count and combined
duration show what is included. When added clips exist, export uses exactly
that saved list, even while another range is being prepared. The current range
is included only after Add clip; an open clip edit must be saved or cancelled
before exporting. With no saved list, Review export still exports the current
single range. Output format/quality and detailed encoding notes are collapsible.

The existing full-window video preview, playback pointer, trim handles, exact
timestamps, Set start/end here and 20-clip limit remain. Multiple clips from the
same source join into one H.264/AAC MP4; original files stay unchanged. Preview
plays the original range individually, not the final encoded merged sequence.
Queued exports persist; unsent editor drafts still do not persist after closing
or process recreation. Encoder padding/frame boundaries can affect exact joins.
No new permission, dependency or backend is added; Instagram hosting is deferred.
