# CleanForge Part D audit: UI, app shell, build

Scope: `MainActivity`, `CleanForgeApp` (Application), `ui/*` (NavHost, ViewModels, dialogs, components, theme, screens), `res/*`, Gradle files, `ConfirmationEvidenceTest`. Files already audited in Parts A-C were not re-read.

## The main finding

The repo holds **two UIs**. The real one (Material 3, Hilt ViewModels, the cleaning engine) is in `com.cleanforge.ui`, but the app never reached it: `MainActivity` launched an old prototype in the placeholder package `com.yourpackage.cleanforge` that showed fake `SampleData`. Even if launched, the real `CleanForgeNavHost` referenced `CleanScreen(vm)`, `ReviewScreen(vm)` and `AboutScreen()` that did not exist (only prototype versions with other signatures did).

## Bugs found and fixed

| # | Severity | Where | Bug | Fix |
|---|----------|-------|-----|-----|
| 1 | High | `MainActivity` | Wrong package (`com.yourpackage...`, the manifest expects `com.cleanforge.MainActivity`: crash on launch); no `@AndroidEntryPoint` (`hiltViewModel()` would throw); showed the prototype with fake data, not the real engine. | Rewritten: package fixed, `@AndroidEntryPoint`, hosts `CleanForgeNavHost()` in `CleanForgeTheme`. Status/navigation bar icons now follow the theme (they were forced "light" and vanished in dark mode). |
| 2 | High | `ui/screens/*` | `CleanScreen(vm)`, `ReviewScreen(vm)`, `AboutScreen()` missing, so the real UI could not compile. | Written with the existing Material 3 components: Clean tab = quick categories, Review tab = suggestion-only categories (none ticked), About credits Aman and states the privacy and Shizuku limits plainly. |
| 3 | High | 24 prototype files | Whole second UI in the placeholder package; would break the build once packages are unified, and would ship fake data. | `partD-apply.sh` removes exactly those files (list inside). The real UI references none of them. |
| 4 | Medium | `CleanForgeNavHost` | RAM screen was built but had no route (unreachable). Edge-to-edge content drew under the status bar (only the bottom inset was applied). | Added the "Memory" tab; content now keeps clear of the status bar. |
| 5 | Low | `RamScreen` | Observed `/proc/meminfo` for as long as the screen was composed, including in the background. | Observes only while the screen is STARTED (`LifecycleStartEffect`, lifecycle-aware state). |
| 6 | Low | `app/build.gradle.kts` | Duplicate `material-icons-extended` (catalog + hard-coded 1.7.5); DataStore version hard-coded. | Single catalog entry for each. |

`buildConfig = true` is present, so the Shizuku files' `BuildConfig` use compiles.

## Checked and found sound

- `CleanViewModel`: scan, selection, plan, confirm, cancel; a stopped run still produces an honest partial report (in-flight item UNVERIFIED, rest SKIPPED); Shizuku/binder calls stay off the main thread (matches `PolicyGuardTest`).
- `Dialogs`: every path listed in full, refused items listed with reasons, "delete permanently" is the only way through, progress dialog not dismissible.
- Manifest, `ShizukuProvider`, `Application` registering Shizuku, Gradle versions (AGP 8.7.2, Kotlin 2.0.21, Gradle 8.9) consistent.

## Notes (not changed)

- `Preferences.kt` and `Verses.kt` are now unused (dark mode follows the system). Kept; delete them if you will not use the dark toggle or verses.
- `applicationId = "com.cleanforge"`: choose your own reverse-domain id before the first public release (it cannot change later).
- `ReportDialog` lists the first 6 problems and a count; the full list is not shown.

## Tests

- Not compiled or run (no Kotlin/Gradle/Android SDK here). Compose screens cannot be unit-tested on the JVM; `ConfirmationEvidenceTest` still covers the data the dialog renders. All imports of the remaining UI were checked to resolve, brace balance was checked.
- Run: `./gradlew testDebugUnitTest assembleDebug`.

## Still needed from you

- `di/AppModule.kt`: it must provide `Capabilities` (`AndroidCapabilities`), `CleaningEngine`, the executor and registry as singletons, the backends and the scanner set (without the three dormant Android/data scanners).
- `testutil/Fixtures.kt` and `IFileService.aidl` (see earlier parts).
