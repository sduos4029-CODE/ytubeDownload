# Flux 2.5.4 — Select and unselect results

In Library selection mode, Select results ticks the current filtered results (up to the existing 100-item limit). The action then changes to Unselect results. Tapping it removes those checkmarks and returns the action to Select results. It stays in selection mode, allowing another selection immediately.

Selections under other filters are retained. A partially selected view fills its remaining selection slots; at the 100-item limit, the action can clear its visible selected items. Empty results disable the action. Done continues to exit selection mode and clear the whole selection. Selection changes never delete or modify saved files.

Validation covers actual release UI button changes, visible checkbox clearing and reselection, plus partial selection, hidden selections, empty results and the 100-item cap. Existing compact Library, notification, download/editor/history/backup/Threads checks remain part of the release gates. Android30 x86_64 emulator checks do not certify every physical device.
