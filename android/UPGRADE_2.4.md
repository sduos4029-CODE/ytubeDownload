# Flux 2.4.0 — saved-media tools

Open Library → the saved file's More actions → Media tools. Photos are excluded.
Every export creates a new file in the current download folder and a separate
Library entry. The original is opened read-only and never overwritten.

## Operations

- Trim clips using seconds or HH:MM:SS with up to three decimal places. End must
  follow start and cannot exceed the inspected duration. Stream-copy trimming is
  packet/keyframe-based and can differ from requested times. Short video ranges
  without a usable keyframe can fail safely. H.264/AAC re-encoding offers more
  precise cuts when that encoder is available.
- Extract the first audio track to original-stream MKA, AAC/M4A, MP3, FLAC or
  16-bit PCM WAV. Availability is checked against the installed media engine.
- Convert video containers to MKV or compatible MP4 without re-encoding, or
  encode H.264/AAC MP4 when supported. Incompatible MP4 stream copies fail with
  advice to choose MKV or another format. Audio-only sources offer audio outputs.

Higher quality/Balanced/Smaller file select H.264 CRF18/23/28 and AAC256/192/128
kbps or MP3 320/192/128 kbps. These are encoding targets, not a guarantee of
restored quality or final size. Source dimensions are kept for H.264 except for
up to one pixel of padding to satisfy even dimensions. FLAC cannot restore
lossy detail; WAV16 can reduce precision from higher-bit-depth material.

Only the first video/audio tracks are included. Additional tracks, embedded
subtitles, chapters and original metadata are excluded. No upscaling, invented
sound, built-in playback or remote media editing is included.

## Queue, storage and privacy

Local exports use a separate, durable WorkManager queue and run one at a time.
They do not need network access or send files to a server. Global Pause/Resume
and per-item cancellation still apply. Edits restart from the beginning after
pause, process interruption or a failed retry; original media stays unchanged.
Actual processed timestamps drive progress when duration is known; encoding ETA
is unavailable. Copy, edit, verification and save details appear in Downloads.

The source is copied to app-private, no-backup working storage; FileProvider
cannot share that workspace. Native FFmpeg receives fixed argument arrays and
only local file/pipe protocols with an explicit container allowlist. It receives
no download URLs, login cookies or server access keys. User-entered times and
preset/quality enums are validated; no shell command is constructed.

Working-space checks use the existing conservative estimate and a reserve;
large WAV/FLAC or re-encoded outputs may need more. Destination provider free
space may be unknown. Outputs are checked before publishing. Invalid/empty
files or missing expected tracks fail and pending saves are cleaned up;
containers Android cannot inspect are visibly unverified. Track/sample checks
are not decoder compatibility or full-playback certification.

Original folder permissions are retained while an edit needs them. Temporary
files are removed after completion, cancellation, failure or pause. Source
availability and provider write permissions can change; a failed destination
cleanup remains recorded so it can be retried after access is restored.

Instagram hosting remains deferred; no backend or user-phone Reel fix is
included. Physical-device/OEM behavior and a full native dependency security
audit remain unverified. Tests use generated media and are not security certification.
