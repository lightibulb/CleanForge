# CleanForge - Project Audit

Audited: `cleanforge.zip` (an AI-generated scaffold). Audit date: 2026-10-08. Project owner: Aman.

## 0. Verification status (read this first)

| Check | Status | Why |
|---|---|---|
| Compile (Kotlin/Gradle) | **NOT RUN** | Sandbox has no Android SDK, no Gradle, no Kotlin compiler, and its network is blocked (Maven/Gradle/Google hosts refused). |
| Unit tests (66 written) | **NOT RUN** | Same reason. |
| Lint | **NOT RUN** | Same reason. |
| `CleanForge-debug.apk` / `CleanForge-release.apk` | **NOT BUILT** | Same reason. No APK exists. |

What was done instead (this is *review*, not proof):

- Read every original source file and verified each finding below against the code (`grep` evidence cited).
- Rewrote the affected code, then ran a static sweep over all 53 Kotlin files: package matches directory, brackets balanced (string/comment-aware tokenizer), every in-project import resolves to a real declaration, named `ScanItem(...)` arguments exist, no JVM-illegal characters in test names.
- That sweep **found one real compile error in my own new code** (an unclosed `emit(` in `ThumbnailScanner`) and a manual review found two more in my tests (helper parameter order, a `;` in a test name). All three were fixed. A compiler will very likely find more; `BUILD.md` lists the likeliest spots.

Nothing in this document claims the project builds. It is "carefully reviewed, structurally consistent, never compiled".

## 1. Inventory of the original project

| Item | Finding |
|---|---|
| Kotlin sources | 35 source files + 2 test classes (2,181 lines in total), in packages `core/{safety,cleaning,backend,scanner,shizuku,model}`, `data/repository`, `domain/usecase`, `di`, `ui/{theme,screens}` |
| Where they live | **Project root** (`core/`, `ui/`, `di/` ...), outside any Gradle source set. Tests in root `test/java`. |
| Modules | One (`:app`); docs mention `:shizuku-service`, which does not exist |
| Gradle | Version catalog; AGP 8.7.2, Kotlin 2.0.21, KSP, Hilt 2.56, Compose BOM, Room, WorkManager, DataStore, Shizuku 13.1.5 |
| Manifest | 5 permissions; Shizuku provider; references `@string/app_name`, `@style/Theme.CleanForge`, `@mipmap/ic_launcher` |
| Resources | **None** (`app/src/main/res` absent). No `proguard-rules.pro`, `gradle.properties`, or wrapper. |
| Shizuku | State holder + two "backends"; file backend delegates to an interface with **no implementation**; package backend uses hidden APIs |
| Filesystem/deletion | `StandardFileBackend` uses `File.delete()`; `CleaningEngine` batches of parallel deletes |
| Scanners | 9: Cache, Corpse, Temp, Log, Thumbnail, EmptyDirectory, LargeFile, Duplicate, Apk |
| UI | One screen (`HomeScreen`); NavHost references 5 more that do not exist |
| Tests | 2 test classes, outside the test source set |

## 2. Findings

Severity: **C**ritical (data loss or cannot build), **H**igh, **M**edium, **L**ow.

### 2.1 Build blockers (project could not compile)

| ID | Sev | Finding (evidence) | Fix |
|---|---|---|---|
| B1 | C | Sources are outside `app/src/main/java`; `:app` compiled zero Kotlin | Moved to standard layout |
| B2 | C | No `res/`, but the manifest references string, style and mipmap resources | Added strings, themes (day/night), adaptive vector icon |
| B3 | H | No `proguard-rules.pro` (release uses minify), `gradle.properties` (`useAndroidX`), or wrapper properties | Added; wrapper jar must be generated (BUILD.md) |
| B4 | C | `ShizukuPackageManagerBackend` imports `IPackageManager`, `IPackageDataObserver`, `AppGlobals`: hidden, not in the public SDK. Also `maven("https://api.xposed.info/")` was added to repositories with no use | Backend removed; untrusted repository removed |
| B5 | C | Hilt: module provides only `@Named("standard"/"shizuku")` backends, but all 9 scanners inject an unqualified `FileOperationBackend` (no `@Named` anywhere outside the module); `CleaningEngine` takes two same-typed params; `ShizukuUserServiceBridge` is an interface with no binding | DI rewritten with explicit construction in `AppModule` |
| B6 | C | `FileOperationBackend.kt` imports `com.cleanforge.core.model.FileMetadata` (does not exist) *and* declares `FileMetadata` itself | Interface redesigned (`FsStat`) |
| B7 | C | NavHost references `AppManagerScreen`, `LargeFilesScreen`, `DuplicatesScreen`, `AnalyzerScreen`, `SettingsScreen`: none exist | New 3-tab navigation: Clean, Review, About |
| B8 | C | `HomeScreen` calls `mutableStateSetOf<String>()` but defines a local `mutableStateSetOf()` with no type parameter (compile error; even if fixed, a plain `mutableSetOf` would not trigger recomposition) | Selection state moved into the ViewModel (`StateFlow`) |
| B9 | M | Tests use `kotlinx.coroutines.test.runTest` but the dependency is not declared | Added |
| B10 | L | Room, WorkManager, DataStore declared (plus Room's KSP compiler); no source uses them | Removed |

### 2.2 Safety and data-loss findings

| ID | Sev | Finding (evidence) | Fix |
|---|---|---|---|
| S1 | C | `SafetyValidator` accepts `/sdcard` and `/storage/self/primary` as roots, but `topLevelName()` only strips `/storage/emulated/0/`. For `/sdcard/DCIM/Camera/x.jpg` it returns an empty name, so the protected-folder rule never fires | Only the canonical root is accepted; aliases refused (regression test) |
| S2 | H | Validation is lexical only. Only the final path component was checked for being a symlink; revalidation reused the stale scan item | Fingerprint (type, device, inode, size, mtime) recorded at scan and compared before deletion; `SafeDeleter` uses `lstat`, refuses symlink targets, re-checks directory identity |
| S3 | H | `StandardFileBackend` calls `File.delete()`, which cannot delete a non-empty directory. Every cache/leftover/thumbnail deletion would fail; the obvious "fix" (`deleteRecursively`) follows symlinks and has no bounds | New `SafeDeleter`: explicit-depth, bounded, no symlink following, no mount crossing |
| S4 | C | `CorpseScanner` treats any folder whose name is not in the installed list as a leftover. The Shizuku package backend returns `emptyList()` whenever Shizuku is unavailable, so *every* folder under `Android/data` and `Android/obb` would be flagged. Also excludes data-kept uninstalls, no package-name validation, no age check | Uses `MATCH_UNINSTALLED_PACKAGES`; refuses when the list is missing or implausibly small (< 20); requires a valid package name, a real directory, age >= 14 days; ELEVATED risk; never pre-selected; re-checked at deletion |
| S5 | M | Contradictory rules made features dead and hid why: a rule rejects HIGH+SAFE+NOT_REVERSIBLE (all corpse items); protected-folder rule rejects all thumbnail and empty-folder findings (they live under DCIM/Pictures/...); `dryRun` dropped rejects silently | Policy rewritten; refused items keep their reason and are shown in the confirmation dialog |
| S6 | H | Pipeline not wired: the Clean button is a `// TODO`; the plan from `dryRun` is discarded; the engine takes raw items; no confirmation | `ConfirmedPlan` type gate; `ConfirmDialog`; engine accepts nothing else |
| S7 | H | Duplicates: no protection that the kept copy survives; no re-check at deletion | `keepPath`/`keepFingerprint` recorded; PlanBuilder refuses if the keeper is also selected; engine refuses if the keeper changed |
| S8 | H | Shizuku file backend documented as using `IShizukuFileService.aidl` in a `:shizuku-service` module that does not exist; Android/data enumerated with `java.io.File` in the app process, which Android 11+ denies (returns null), so cache/leftover scanners silently found nothing | Real AIDL user service; scanners skipped with a visible notice when Shizuku is unavailable |
| S9 | M | Parallel deletion batches (`chunked(CONCURRENCY)` + `async`) on low-end eMMC, harder to reason about | Sequential, cancellable between items |

No shell usage was found in the original (`Runtime`, `ProcessBuilder`, `newProcess`: none). A test now keeps it that way.

## 3. Android API audit (Android 11+)

| Topic | Reality | Original | Now |
|---|---|---|---|
| `Android/data`, `Android/obb` | Hidden from normal apps even with `MANAGE_EXTERNAL_STORAGE`; SAF cannot select them on Android 11+ | Assumed readable via `File` | Only through the Shizuku service; scanners skip with a notice otherwise |
| `MANAGE_EXTERNAL_STORAGE` | Grants broad shared-storage file access (not the folders above). Needs the user to enable it in Settings | Requested; plus redundant `READ_EXTERNAL_STORAGE` | Kept; redundant permission removed; live check before scan and before delete |
| MediaStore | Deleting by path through the FUSE layer should keep the media index in sync | Not addressed | Not used; **verify on device** |
| SAF | Not usable for the problem folders | Not used | Not used (documented) |
| PackageManager visibility | Android 11 filters other apps unless `QUERY_ALL_PACKAGES` | Requested | Kept; `getInstalledPackages(MATCH_UNINSTALLED_PACKAGES)`; `getPackageArchiveInfo` for APKs |
| StorageStatsManager | Needs `PACKAGE_USAGE_STATS` (special "Usage access") for other apps. Original used `UserHandle.getUserHandleForUid(0)` (uid 0 is root, not the current user) and made two separate `queryStatsForPackage` calls per package (`queryDataSize`, `queryCacheSize`) | Used, with that permission | Feature (Apps screen) removed rather than half-built |
| Internal caches (`/data/data/<pkg>/cache`) | Not reachable without root or a hidden API | Via hidden `IPackageManager` | Not attempted; documented |

## 4. Shizuku audit

| Check | Original | Now |
|---|---|---|
| Detection | `NotInstalled` state existed but was never produced | Installed vs running vs permission, via `pingBinder` and a package lookup (`<queries>` + `QUERY_ALL_PACKAGES`) |
| Binder lifecycle | Received/dead listeners only | Received (sticky), dead, permission-result listeners; `unregister()` provided |
| Permission state | Cached | Re-read live before every scan and again before every deletion (`Capabilities.shizukuAuthorized()`) |
| Service disconnection / process death | No service existed | `ShizukuFileServiceConnector`: bind on demand, 6 s timeout, `onServiceDisconnected`, `RemoteException` invalidation and rebind |
| Unavailable | Package backend returned empty lists (see S4) | Scanner skipped with visible reason; deletion item skipped; never falls back to guessing or a shell |
| Root | Not distinguished | `Authorized(uid)`; UID 0 is shown as "root mode" and gets the same path rules |
| Exploits / undocumented APIs | Hidden APIs used | None. Only the documented user-service API |
| Shell | None | None; guard test |

## 5. Filesystem security audit (matrix)

| Attack / case | Handling | Test |
|---|---|---|
| `../../` traversal, `.` segments | Component-based parser rejects `..`, `.`, empty segments | `SafetyValidatorTest` |
| Absolute vs relative paths | Must be absolute and below the canonical root | `SafetyValidatorTest` |
| `/sdcard`, `/storage/self/primary` aliases | Refused (was S1) | `SafetyValidatorTest` |
| Case variants (`dcim`, `ANDROID/data`) | Structural names compared case-insensitively | `SafetyValidatorTest` |
| Leaf symlink / symlink root | `lstat`; symlink root refused in every mode; symlink *children* unlinked as links only | `SafeDeleterTest` |
| Symlink to a directory inside a tree | Never followed; target survives | `SafeDeleterTest` |
| Ancestor swapped for a symlink | Item fingerprint (device, inode) no longer matches, so the item is skipped | `CleaningEngineTest` (swap) |
| Unicode, spaces, shell metacharacters (`$(...)`, backticks, `;`) | Names are data; no shell anywhere; no normalisation | `SafeDeleterTest`, `SafetyValidatorTest` |
| Long paths | Paths > 4096 refused; 12 x 200-char nesting deleted fine | both |
| Nested directories / unbounded recursion | Explicit depth limit (48) and entry limit (200,000) | `SafeDeleterTest` |
| Mount boundaries | Entries with a different `st_dev` than the root are never entered or removed | `SafeDeleterTest` |
| Missing files | Reported as skipped, no crash | both |
| Files changing during scan | Fingerprint compare before delete; size/mtime change means skip | `CleaningEngineTest` |
| File appearing mid-delete | Folder not removed, reported `PARTIAL`, new file survives | `SafeDeleterTest` |
| NUL / control characters | Refused | `SafetyValidatorTest` |
| Accidental parent deletion | Recursive modes only for three exact path shapes; top-level folders and `Android/*` roots never deletable | `SafetyValidatorTest` |
| Shell injection | No shell/exec/`newProcess` in sources | `PolicyGuardTest` |

Residual: a tiny window between the last `lstat` and `remove` (no `unlinkat` from Kotlin). See `SECURITY.md`.

## 6. Classification audit: "Why is this safe to delete?"

| Scanner | Original evidence | Verdict on original | Now |
|---|---|---|---|
| Cache | Directory named `cache` under an `Android/data` folder; package not verified installed | Weak | Package confirmed installed **and** exact `Android/data/<pkg>/cache` (the OS-defined disposable cache); contents only; messaging apps MEDIUM and not pre-selected. **The only category that can be pre-selected** |
| Corpse (leftovers) | Folder name not in installed list (list may be empty) | Dangerous (S4) | Unknown to the OS incl. data-kept uninstalls, valid package name, real dir, >= 14 days old, plausible package list; ELEVATED; never pre-selected; re-verified at deletion |
| Temp | `.tmp` / `.bak` anywhere in DCIM, Pictures, Download | Filename only; `.bak` is user data | Download only; partial-download extension (`.crdownload`, `.part`, `.partial` strong; `.tmp`, `.download` weak) **and** untouched >= 7 days; `.bak` never; ELEVATED; not pre-selected |
| Log | `*.log` anywhere scanned | Extension only | Download only; `.log` **and** >= 30 days **and** first bytes are line-oriented text; LOW; manual review |
| Thumbnail | Directory name `.thumbnails` | Name only | Real directory containing only small files (<= 2 MB), no sub-folders; contents only; not pre-selected |
| Empty directory | Empty folder in personal roots | Rejected by its own validator (S5) | Verifiably empty now, nested only, hidden skipped, `rmdir` semantics; frees 0 bytes; on demand |
| Large file | Size | Large is not junk | UNKNOWN confidence, DANGEROUS risk, top 200 in a bounded heap, never pre-selected |
| Duplicate | Same size + hash; keeps oldest | Reasonable, but unbounded and no keeper guarantee | Staged hashing, hard links excluded, keeper recorded and re-verified; on demand |
| APK | Regex over *binary* manifest as UTF-16 text (never matches, so package always unknown) | Broken | Platform `getPackageArchiveInfo`; MEDIUM only if the installed version is the same or newer; unreadable APKs skipped |

## 7. Personal-data protection

- Pre-selection is limited to non-messaging app caches with strong evidence. Large, old, duplicate or unknown never implies junk.
- DCIM, Pictures, Movies, Music, Documents, Download(s), Recordings, Audiobooks, Podcasts, Ringtones, Alarms, Notifications, WhatsApp, Telegram, and `Android/media`: single files only, ticked by the user. A validator rule rejects any pre-selected item in them. Cache/leftover rules cannot apply inside them.
- Game saves and app databases: only leftovers of *uninstalled* apps are ever eligible, flagged ELEVATED and never pre-selected; installed apps' `files/` and databases are never touched (only `cache/`).

## 8. Cleaning pipeline

`SCAN -> CLASSIFY -> VALIDATE -> PLAN -> USER CONFIRMATION -> REVALIDATE -> DELETE -> VERIFY -> REPORT` is now enforced by types: scanners emit evidence-bearing `ScanItem`s; `PlanBuilder` yields a `CleaningPlan` with refusals and reasons; `CleaningPlan.confirmedByUser()` is the only way to a `ConfirmedPlan`; `CleaningEngine.execute` accepts nothing else; each item is revalidated, deleted, verified and reported.

## 9. Performance (low-end Android 11)

- All filesystem work runs on `Dispatchers.IO`; the UI thread only touches state.
- Walks are bounded (depth 32/entries 200k; duplicates 100k) and never follow symlinks (the originals used `walkTopDown`, which follows symlinks).
- Default scan is light; hashing, large-file and log scans are on demand.
- Duplicate hashing is staged (size, 64 KB prefix, full) and cancellable; large-file results use a bounded heap.
- Scan batches (50) bound memory; deletion is sequential; no database, so no unnecessary writes.
- A per-scan job can be cancelled from the UI.

## 10. Privacy

- Original manifest had **no** INTERNET permission and no analytics: good, and kept.
- Removed unused permissions: `READ_EXTERNAL_STORAGE`, `PACKAGE_USAGE_STATS`, `REQUEST_DELETE_PACKAGES`.
- `allowBackup=false`; unvetted Maven repository removed.
- `PolicyGuardTest` fails the build on: any INTERNET/network-state permission, extra permissions, analytics/ads/crash libraries, or `xposed`/`jitpack` repositories.

## 11. UI audit

- Shows what will be deleted, why (evidence text), size, risk, evidence strength, "no undo", and whether Shizuku is involved, both per item and in the confirmation dialog.
- No misleading claims were present in the original UI (no "boost"/"RAM"/"CPU"); the About screen states that it makes none.
- Now Material 3: large collapsing top bars, navigation bar, storage hero card, setup cards with actions, extended FAB, dialogs, dynamic colour on Android 12+ and a complete hand-tuned scheme below (the Vivo Y11 is Android 11, so it gets the static scheme).
- Credits Aman as creator and owner (About card and README); see section 13 for decisions pending.

## 12. Tests

66 JUnit cases in 6 suites: `SafeDeleterTest` (14), `SafetyValidatorTest` (18), `PlanBuilderTest` (6), `CleaningEngineTest` (12), `ScannerEvidenceTest` (13), `PolicyGuardTest` (3). Fixtures: real temp directories with symlinks, hard links, unicode and long names (`NioFsOps`), a `HookedFs` for simulated mount points and mid-delete races, and an `InMemoryFs` that lives under `/storage/emulated/0` so the real validator can be exercised. **None have been run.**

## 13. Remaining limitations and decisions for Aman

Limitations: unverified build (section 0); no on-device test (Vivo Funtouch OS behaviour and Android/data access through Shizuku on Android 11 follow the documented model but are unconfirmed); internal app caches and a per-app storage screen are not available; no recycle bin; the residual race in `SECURITY.md`.

Decisions only you can make:
1. **Licence.** FOSS needs one. Suggest GPL-3.0-or-later (typical for F-Droid) or Apache-2.0. Add `LICENSE`.
2. **Application id.** Currently `com.cleanforge`. Choose your own (for example `io.github.<you>.cleanforge`) *before the first public release*; it cannot change later without breaking updates. One line in `app/build.gradle.kts`.
3. **Security contact** in `SECURITY.md` (placeholder).
4. Whether you want the removed features back (per-app storage needs Usage access; internal-cache clearing needs a verified hidden-API approach).

## 14. Appendix: files changed

Original sources were at the project root; all code now lives under `app/src/main/java/com/cleanforge/`. Paths below are relative to that.

**Rewritten in place (29)** - same name, new content: `CleanForgeApp.kt`, `MainActivity.kt`, `core/backend/FileOperationBackend.kt`, `core/backend/ShizukuFileBackend.kt`, `core/backend/StandardFileBackend.kt`, `core/cleaning/CleaningEngine.kt`, `core/model/CleaningPlan.kt`, `core/model/Enums.kt`, `core/model/ScanItem.kt`, `core/safety/SafetyValidator.kt`, `core/scanner/ApkScanner.kt`, `core/scanner/CacheScanner.kt`, `core/scanner/CorpseScanner.kt`, `core/scanner/DuplicateScanner.kt`, `core/scanner/EmptyDirectoryScanner.kt`, `core/scanner/LargeFileScanner.kt`, `core/scanner/LogScanner.kt`, `core/scanner/Scanner.kt`, `core/scanner/TempScanner.kt`, `core/scanner/ThumbnailScanner.kt`, `core/shizuku/ShizukuManager.kt`, `core/shizuku/ShizukuState.kt`, `data/repository/ScanRepository.kt`, `di/AppModule.kt`, `domain/usecase/ExecuteCleaningPlanUseCase.kt`, `domain/usecase/ScanDeviceUseCase.kt`, `ui/CleanForgeNavHost.kt`, `ui/Format.kt`, `ui/theme/Theme.kt`

**Removed (6)**: `core/backend/PackManagerBackend.kt`, `core/backend/ShizukuPackageManagerBackend.kt`, `core/backend/StandardPackageManagerBackend.kt`, `core/shizuku/ShizukuUserServiceBridge.kt`, `ui/HomeViewModel.kt`, `ui/screens/HomeScreen.kt`

**Added (17)**: `core/backend/Capabilities.kt`, `core/backend/PackageInventory.kt`, `core/cleaning/PlanBuilder.kt`, `core/fs/AndroidFsOps.kt`, `core/fs/FsOps.kt`, `core/fs/PathRules.kt`, `core/fs/SafeDeleter.kt`, `core/fs/StorageWalker.kt`, `core/shizuku/ShizukuFileService.kt`, `core/shizuku/ShizukuFileServiceConnector.kt`, `ui/CleanViewModel.kt`, `ui/components/Dialogs.kt`, `ui/components/ItemComponents.kt`, `ui/components/StatusCards.kt`, `ui/screens/AboutScreen.kt`, `ui/screens/CleanScreen.kt`, `ui/screens/ReviewScreen.kt`

**Build/config added or replaced:** `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/aidl/com/cleanforge/shizuku/IFileService.aidl`, resources (`strings`, `themes`, `themes (night)`, launcher icon files), `.gitignore`, `.github/workflows/build.yml`.

**Tests (new, under `app/src/test/java/com/cleanforge/`):** `core/SafeDeleterTest.kt`, `core/SafetyValidatorTest.kt`, `core/PlanBuilderTest.kt`, `core/CleaningEngineTest.kt`, `core/ScannerEvidenceTest.kt`, `core/PolicyGuardTest.kt`, `testutil/Fixtures.kt`. The two original test classes were replaced.

**Docs (new):** `PROJECT_AUDIT.md`, `README.md`, `BUILD.md`, `SECURITY.md`, `CHANGELOG.md`.
