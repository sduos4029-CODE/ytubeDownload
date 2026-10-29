# Flux 3.0 roadmap

Target: 40 accepted features/enhancements. Preview releases implement and verify bounded stages. Final 3.0 is not complete until every acceptance criterion is implemented and verified. The full Library player remains last. Source restrictions can affect public-media access; Instagram service work requires working hosting and separate live verification.

| # | Acceptance scope | Status |
|---|---|---|
| 1 | Named resolution/source-format/audio-language presets, edit/delete/default | Preview 1 implementation |
| 2 | Apply stock or named presets to playlist batches | Preview 1 implementation |
| 3 | Select displayed playlist ranges and skip matching readable saved choices | Preview 1 implementation |
| 4 | Edit/remove individual queued or paused download schedules with review | Preview 1 implementation |
| 5 | Apply/remove schedules for a selected group; preserve active/pause/retry rules | Preview 1 implementation |
| 6 | Thumbnail filmstrip for precise trimming | Planned |
| 7 | Drag clips to reorder | Planned |
| 8 | Duplicate and insert clips | Planned |
| 9 | Persistent editing drafts | Planned |
| 10 | Complete joined-sequence preview before export | Planned |
| 11 | Multi-action editing undo/redo | Planned |
| 12 | Join clips from different saved videos | Planned |
| 13 | Crop, rotate and flip videos | Planned |
| 14 | Original/16:9/9:16/square framing presets | Planned |
| 15 | Resize/compress video with output-size estimates | Planned |
| 16 | Export playback-speed changes | Planned |
| 17 | Video and audio fades | Planned |
| 18 | Mute, replace or mix background audio | Planned |
| 19 | Volume adjustment and optional normalization | Planned |
| 20 | Audio trimming and joining | Planned; existing single-file tools retained |
| 21 | Extract still images from video | Planned |
| 22 | JPEG/PNG/WebP photo conversion | Planned |
| 23 | Photo crop, rotation and compression | Planned |
| 24 | Storage manager with cleanup review | Planned |
| 25 | Reconnect missing or moved Library files | Planned |
| 26 | Review and remove orphaned partials | Planned |
| 27 | Clearer source, codec and verified-file details | Planned enhancement |
| 28 | Broader reboot/app-termination/network recovery validation and improvements | Planned enhancement |
| 29 | Dependency/security reviews and broader physical-device testing | Planned enhancement; no full-audit claim |
| 30 | Verified public Instagram service: Google Cloud Run first, Render fallback | Planned; hosting not configured |
| 31 | Accessible public Instagram/Threads profile galleries | Planned |
| 32 | Profile-item preview and selection | Planned |
| 33 | Selected profile batch downloads with duplicate detection | Planned |
| 34 | Library metadata/collections/tags export and reviewed restore | Planned; preference-only backup already exists |
| 35 | Broader font-size/screen-reader accessibility | Planned enhancement |
| 36 | Additional UI languages and appearance options | Planned |
| 37 | Full Library video/audio player | Last stage |
| 38 | Subtitles, speed, gestures and resume position in the player | Last stage |
| 39 | Picture-in-picture playback | Last stage |
| 40 | Background audio and lock-screen controls | Last stage |

Preview2 adds the explicitly requested initial saved-video player/gestures ahead of the previous player-last ordering. Item37 is partial; advanced player work, subtitles, PiP and background audio remain incomplete. Items1–5 stay implemented; 35 roadmap items remain incomplete (one partial,34 planned). See UPGRADE_3.0.0-preview.2.md.
