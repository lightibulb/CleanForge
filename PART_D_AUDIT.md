# CleanForge Part D audit: UI, app shell, build

Scope: `MainActivity`, `CleanForgeApp` (Application), `ui/*`, `res/*`, Gradle files, `ConfirmationEvidenceTest`. Files audited in Parts A-C were not re-read.

## Which UI

The repo held two UIs:

1. **Material 3 UI** (`CleanForgeNavHost`, Material components), real engine, unfinished.
2. **Warm UI** (clay/sage palette, Fraunces + Inter, dock, frosted top bar, verses, 7-tap egg), the design of `CFwebApp.html`, but running on fake `SampleData`.

I first completed (1). You prefer (2), so the final result is **the Warm UI wired to the real engine**; (1) is removed. The HTML sample was not needed: the Warm Kotlin files already implement that design.

## What changed

- **Warm shell on the real engine.** `CleanForgeApp` now runs on `CleanViewModel` and `RamViewModel`: real scans, selection, plan, cleaning and RAM readings replace `SampleData` and the fake timers.
- **Warm screens use the real model.** `CleanScreen`, `ReviewScreen`, `ItemRow`, `MemoryScreen` take the real `ScanItem`. Sizes say "at least" when part of a folder could not be read. Scan notes now also show on the Review tab (they were passed in but never drawn). A Stop button appears during scans.
- **Real Shizuku status.** The static "Shizuku is optional" card is replaced by the real card (state, next step, re-check). The All-files card shows only while access is missing.
- **Real confirmation kept on purpose.** Deleting goes through the audited `ConfirmDialog` (lists every path, explains refused items), `ProgressDialog`, `ReportDialog`. They wear the Warm colours because `CleanForgeTheme` derives the Material scheme from the Warm palette.
- **Theme.** `CleanForgeTheme(dark)` = Warm palette + `WarmTypography` + a derived Material scheme. The in-app dark toggle in About drives it, and status/navigation bar icons follow it.

## Bugs found and fixed

| # | Severity | Bug | Fix |
|---|----------|-----|-----|
| 1 | High | `MainActivity`: wrong package (crash on launch), no `@AndroidEntryPoint`, called a theme function that no longer existed. | Rewritten. |
| 2 | High | Whole shell was fake: scans and deletes were timers over `SampleData`. | Wired to the view models (above). |
| 3 | High | 24 Warm files in the placeholder package `com.yourpackage.cleanforge`, mixed with `com.cleanforge`. | All renamed to `com.cleanforge`. |
| 4 | High | `CleanScreen` used `.sp` without importing it (did not compile). | Import added. |
| 5 | Medium | Edge-to-edge drew the title under the status bar and the dock/FAB under the navigation bar. | Insets applied to top bar, content, dock and FAB. |
| 6 | Medium | Memory observed `/proc/meminfo` for as long as the tab was composed, and a `MemTotal` of 0 divided by zero. | Observes only while the Memory tab is visible and the app is started; guarded. |
| 7 | Low | Duplicate `material-icons-extended`; DataStore version hard-coded. | One catalog entry each. |
| 8 | High | `ItemRow`: the evidence label `when` listed 3 confidence levels but `ConfidenceLevel` has a 4th (`UNKNOWN`), so it did not compile. | Added `UNKNOWN -> "Not verified"`. |

`buildConfig = true` is present (the Shizuku files need it).

## Removed (superseded)

`ui/CleanForgeNavHost.kt`, `ui/screens/RamScreen.kt`, `ui/components/ItemComponents.kt`, `data/ScanModels.kt` (fake data), and the old fake confirm/progress/report sheets (only the easter-egg sheet is kept). `partD-apply.sh` removes them and fixes the package of `Preferences.kt` and `Verses.kt`. Nothing remaining references them (checked).

## Notes (not changed)

- The Warm shell uses one scrolling Column rather than a lazy list, as in your design. Fine for hundreds of rows; thousands would need a lazy list.
- `applicationId = "com.cleanforge"`: pick your own reverse-domain id before the first public release (it cannot change later).
- `ReportDialog` lists the first 6 problems plus a count.

## Tests

- Not compiled or run (no Kotlin/Gradle/Android SDK here). Compose UI cannot be unit-tested on the JVM; `ConfirmationEvidenceTest` still covers the data the dialog renders. Every project import in the final UI was checked to resolve, and brace balance was checked.
- Run: `./gradlew testDebugUnitTest assembleDebug`.

## Files received after the first pass

- `IFileService.aidl`: ids 1-12 plus `destroy` match `ShizukuFileService` and the contract test. No change.
- `AppModule.kt`: every constructor it calls matches the real classes; `ShizukuManager`, the connector, `ScanRepository`, `SafetyValidator` and `PlanBuilder` are injectable; the three dormant scanners are correctly left out. No change.
- `Fixtures.kt`: used to re-check my new tests (`HookedFs`, `NioFsOps`, `InMemoryFs`, `scanItem(pkg = ...)`). `StandardFileBackendTest` no longer depends on `FaultyFs`.

## Still needed from you

- `testutil/PrivilegedFakes.kt` (defines `FaultyFs` and the privileged fakes that `FileServiceCoreTest` and `PrivilegedExecutorTest` use); it was not in any zip.
- The first build output: I cannot compile here, so send me the errors from `./gradlew testDebugUnitTest assembleDebug`.
