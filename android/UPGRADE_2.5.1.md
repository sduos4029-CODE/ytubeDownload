# Flux 2.5.1 — easier audio, schedule and backup settings

Settings → Audio, schedule & backup now has three numbered sections.

1. Choose a language by name, such as Hindi, English, Tamil or Telugu, then tap
   Save audio preference. The screen distinguishes saved choices from unsaved
   changes. Source default keeps source ranking; a matching separate track is
   used only when available. This does not translate speech. Another language
   retains optional custom language-code support. Regional codes with an
   underscore or hyphen match consistently.
2. Schedule new downloads with a date/time picker or In 1 hour. Review the date,
   time, device timezone, saved audio preference, quality, network and power
   requirements before Apply schedule. Cancel leaves the current schedule alone.
   Start new downloads now clears only this default, not existing job schedules.
   Only subsequently added items inherit it. The summary refreshes when the time
   passes; Android/network/power restrictions can delay starts.
3. Export preferences first shows actual values. Save backup file opens Android's
   file picker and exports precisely the reviewed snapshot, even if a setting
   changes while that review is open. Import validates the file, shows the values
   to replace, and offers Restore or Keep current preferences before mutation.
   Invalid backups show errors without applying settings.

Backups include language, quality preset, charging/Wi-Fi rules, parallel limit,
per-download speed cap and Library layout/size/sort. They do not contain videos,
history, login credentials, server settings, folder permissions or schedules.
Restoring now explicitly preserves the current new-download schedule as well as
all existing job schedules; it does not import a schedule from the file.
Older valid preference backups remain supported. No JSON editing is required.

No new Android permission, dependency or hosted service. Existing quality
presets, priority, speed caps and trimming/merge behavior remain. Instagram
hosting is still deferred. Physical-device/OEM codec coverage is not implied.
