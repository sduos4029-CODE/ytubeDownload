# Flux 2.5.0 — download presets and queue controls

## Remembered quality presets
Choose Best available, Up to 1080p, Up to 720p or Audio only in Choose quality.
Use this preset by default remembers the choice; Settings → Default download
preset also changes it. Every download still shows the actual source formats
before you confirm. Picking a format manually switches to Custom selection.

Caps select the highest reported source resolution within the limit, using the
smaller reported dimension for portrait video. Missing/unknown matching formats
show a message and require another preset or a manual format choice. Nothing
is upscaled. Source audio selection and the preferred audio language remain.
These defaults apply to single-video format selection, not photos or playlist
batches. Presets are four built-in choices, not custom named profiles.

## Queue priority
In Downloads, eligible waiting/paused items offer High, Normal and Low priority.
High starts before Normal/Low when due; insertion order breaks priority ties.
Current transfers continue. Scheduled/retrying items must still become eligible;
Wi-Fi, power and pause rules remain. Download next raises priority to High and
moves the item ahead within that class. Local exports use the same priority
ordering within their separate queue. Priority survives process restarts;
older history defaults to Normal.

## Optional speed cap
Settings → Download controls → Speed cap offers Unlimited, 256/512 KiB/s,
1/2/5/10 MiB/s per download. New or resumed media transfers use this approximate
cap; pause/resume active items to apply a change. Parallel items each have their
own cap. Capped downloads use one media fragment at a time. Metadata retrieval,
merging, saving and local edits are not throttled. Unlimited preserves the
previous transfer behavior. Settings export/import includes the preset and cap;
older preference backups restore to Best available and Unlimited.

No new Android permissions, dependencies or hosted backend. Existing precise
trimming, clip joining, source-file preservation and Library history remain.
Instagram production hosting remains deferred. Device/OEM codec compatibility,
custom named profiles and a final merged editor preview remain outside scope.
