# CleanForge update 2: features and fixes

Built on your uploaded repo (the CI build that works). Not compiled or run here; the CI build is the test.

## Backend
| Request | What changed |
|---|---|
| Scan shows nothing | Results now appear **as each batch arrives** (before, nothing showed until a whole scanner finished, which on a slow Shizuku scan looks empty). After a scan a **"What the scan checked"** card lists every scanner and what it found ("App caches: 38 found (412 MB)", "Thumbnails: nothing found"); skipped or failed scanners still get their amber note with the reason. Tell me what that card says if it is still empty. |
| Memory: which app eats RAM | New read-only call in the Shizuku service (`processMemoryEx`, app processes only, no argument) + `TopProcessesUseCase`. The Memory hero is tappable; a list slides down from behind it and refreshes every 4 s while open. Needs Shizuku; without it the card says why. `versionCode` bumped to 4 so Shizuku restarts the service with the new call. |
| Storage card by type | New `StorageBreakdownUseCase`: Images, Videos, Audio, Documents (from Android's media index, by top folder), Apps you installed (installed code, top apps) and Caches and junk (from your last scan), plus "System and other". Tap the storage hero; tap a category for its sub-categories. |
| Run scan "useless" | Same fix as the first row, plus the hero ring no longer stays empty (it only animated from its first, still-zero value). |

Limits that Android sets, not the app: private app data is hidden from other apps (so the Apps row is installed code), and per-app memory is resident memory including pages shared with the system.

## UI
- **Theme follows the system** by default. About has a Theme button cycling System, Light, Dark.
- **Review: "Select everything & delete…"** ticks every suggestion (keeps one copy per duplicate group), then opens the normal confirmation listing every path. Nothing is removed without that confirmation.
- **Nav bar** is now opaque, higher contrast, with a border and shadow.
- **Easter egg sheet**: it sat halfway up because a Compose dialog window wraps its content and is centred; the window now fills the screen and anchors the sheet to the bottom.
- **Animations**: tab changes slide and fade by direction, cards expand and collapse with springs, rows animate selection and size, the rings follow live values.

## Housekeeping
- `apply-update2.sh` deletes the three stray copies in `app/src/test/java/com/cleanforge/data/` (Preferences, ScanModels, Verses).
- Tests added: `ProcessMemoryTest`, `ThemeModeTest`, and a summaries test in `ScanRepositoryTest`.

## Remaining
- Send me the CI result (or the first error) after this update, and what the new scan card says on your phone.
- App Android/data and obb sizes per app (needs a new Shizuku call, like memory); not included.
