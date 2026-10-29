# Flux 3.0 Preview 2 — Saved-video playback controls

This preview retains Preview 1's roadmap items1–5 and adds the user's requested saved-video playback controls. It introduces the initial Library player from item37 earlier than the former player-last ordering. The 35 other roadmap items remain incomplete: item37 is now partial; the other34 remain planned. This is not final3.0. PiP and background audio remain future work.

Open a completed video from Library to use the internal player. Android's available decoders determine playback support; incompatible or missing files show an error and Open in another app remains available. Photos and audio-only items retain their existing opening behavior. The video keeps its aspect ratio.

- Swipe vertically on the right half for the device's media-stream volume. Volume follows the device's normal limits and remains at the chosen level after closing.
- Swipe vertically on the left half for this player window's brightness (5–100%). The system brightness setting is not written. Leaving/backgrounding restores the prior window brightness; returning keeps playback paused.
- Double-tap right to seek forward10seconds, left to seek back10seconds. Targets stop at the beginning/end.
- A fixed bar below the video contains the position slider, time display, back10, play/pause and forward10 controls. Playback settings also provides volume/brightness sliders and gesture instructions.
- Playback pauses when the activity backgrounds; the player releases playback resources when closed. Android audio focus and hardware media-volume buttons are used. This preview does not add background playback or PiP.
- Library Options → Item size now wraps Compact/Small/Medium/Large to fit the screen, removing the need to scroll the size row sideways.

The player activity is non-exported and accepts only a completed saved-video identity from the private download ledger, not arbitrary external URLs. The only new permission is Android's normal MODIFY_AUDIO_SETTINGS permission for media volume; no write-system-settings permission or service is added. Existing saved bytes, presets, history and queue data are retained. Same app ID/certificate, version code30: Android cannot install a lower version-code APK over it.

Validation adds a release-device suite covering Library size visibility/opening, real decoder playback, touch-injected double taps/swipes, bottom seek/play/pause controls, native media volume, window-only brightness, background pause/brightness reset, unchanged file hash, unknown-identity rejection and activity export boundary. Existing41 gates still apply. Device checks use Android30 x86_64; physical OEM behavior, Bluetooth routes, HDR brightness and every source codec are not verified by emulator tests.
