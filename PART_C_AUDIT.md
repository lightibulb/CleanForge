# CleanForge Part C audit: scanners and RAM

Scope: `scanner/*` (Apk, Cache, Corpse, Duplicate, EmptyDirectory, LargeFile, Log, Temp, Thumbnail, plus the five platform scanners and `ForensicFolderScanner`), `memory/*` (5 files), `ClearMemoryUseCase`, `ObserveRamUseCase`. The Part C zip was byte-identical to the original files already in hand. The platform scanners and `ForensicFolderScanner` were audited earlier; this pass read the rest.

## Bugs found and fixed

| # | Severity | Where | Bug | Fix |
|---|----------|-------|-----|-----|
| 1 | High | `WhatsApp/Telegram/TikTok/TwitterX/InstagramScanner` | Did not compile: passed `isPreselected` to the `ScanItem` constructor (it is derived) and used the old nullable backend API; they also swallowed cancellation through `runCatching`. | Rewritten as thin subclasses of `ForensicFolderScanner` (rules and thresholds kept, matching `ForensicScannersTest`). TikTok/Twitter/Instagram documented as dormant (no single-file delete in the Shizuku service). |
| 2 | Medium | Apk, LargeFile, Duplicate, EmptyDirectory, Log, Temp | A walk cut off at the entry limit, or entries that could not be read, were never reported, so "could not check" looked like "nothing found" (violates `Scanner.incompleteReasons`). | New `WalkNotes`/`walkNoting` collects truncation and unreadable entries; all six scanners now report them. Unreadable log heads and un-listable folders are counted instead of skipped silently. |
| 3 | Medium | `ThumbnailScanner` | "Only small files, no sub-folders" was claimed even if part of the folder was cut off or unreadable; a missing-vs-denied folder looked the same; no cancellation check. | Fails closed (an unknown part means "not a cache"), reports why, distinguishes missing from denied, checks cancellation. |
| 4 | Low | `MemInfoParser` | `MemTotal: 0` produced a zero-byte snapshot instead of "unknown". | Returns null. |
| 5 | Low (compile hazard) | `BackgroundProcessKiller` | `catch (_: Exception)` is not valid on older Kotlin versions. | Named the parameter. |
| 6 | Low | `ApkScanner` | Parsing each APK (slow) had no cancellation check. | Added. |

## Checked and found sound

- `CacheScanner` / `CorpseScanner`: installed vs known stay separate, tri-state install state, plausibility floor, exact folder type, age, lower-bound sizes.
- `DuplicateScanner`: staged hashing, hard links excluded, never picks a keeper.
- `LargeFileScanner`: bounded heap, never pre-selected. `EmptyDirectoryScanner` and `TempScanner` rules match their docs.
- Memory: `/proc/meminfo` parsing, pressure bands, measured (not estimated) freed bytes, system apps and CleanForge itself are never trimmed.

## Notes (not changed)

- `ClearMemoryResult.processesTouched` counts kill REQUESTS sent, not processes that existed; Android does not let us see others' processes. Word the UI as "asked N apps to trim".
- `ClearMemoryUseCase` calls `isCurrentlyInstalled` once per known package (hundreds of binder calls); acceptable, but a single installed-apps query would be faster.

## Tests

- Not compiled or run (no Kotlin/Gradle here, `testutil/Fixtures.kt` missing). Checked by reading code against tests and brace/paren balance.
- Added: `WalkScannerReasonsTest` (4, new), `MemInfoParserTest` (+1). Run `./gradlew testDebugUnitTest`.
