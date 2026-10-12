# CleanForge Capability Audit (Part 1: static review)

Audited: `CleanForge.zip`, git `HEAD c8ed888` ("Exhausted, Literally"), clean working tree, 8 commits, all dated 2026-10-08.
Audit date: 2026-10-10. No source file was modified. This file is a proposal; it does not replace `PROJECT_AUDIT.md` or `AUDIT_RESPONSE.md` (see section 6).

## 0. Method and limits

Evidence tags used throughout:

- **VERIFIED**: read in the source, with file and function cited.
- **LIKELY**: follows from the source but depends on platform behaviour not exercised.
- **UNKNOWN**: cannot be decided without running the app. Listed in section 7 as an experiment.
- **RECOMMENDATION**: my proposal, not a fact about the repo.

What was done: read all of `core/`, `data/`, `di/`, `domain/`, `CleanViewModel`, `CleanForgeNavHost`, `StatusCards`, `CleanScreen`, the manifest, AIDL, Gradle files, wrapper, CI workflow and `PolicyGuardTest`. Other UI files (`ReviewScreen`, `ItemComponents`, `Dialogs` body, `AboutScreen`, `Theme`) and the bodies of the other six test classes were skimmed or not read. Test names and counts were checked.
Static checks run: 55 Kotlin files, package equals directory in all; no unresolved in-project import; brackets balanced; no shell, `Runtime`, `ProcessBuilder`, `.exec(` or `newProcess` in `src/main`.

What could NOT be done: compile, run unit tests, lint, build an APK, or run on a device. This environment has only a JDK; no Android SDK, no Gradle distribution, no network. **Nothing in this audit is evidence that the app builds or that deletion works on a phone.**

## 1. Baseline

| Item | Finding |
| --- | --- |
| Architecture | Single module `:app`. `core/{backend,cleaning,fs,model,safety,scanner,shizuku}`, `data/repository`, `domain/usecase`, `di`, `ui/{components,screens,theme}`. Hilt wiring is explicit in `di/AppModule.kt`. 47 main + 8 test Kotlin files. |
| Toolchain | AGP 8.7.2, Kotlin 2.0.21, KSP 2.0.21-1.0.27, Hilt 2.56, Compose BOM 2024.11.00, coroutines 1.9.0, Shizuku api/provider 13.1.5, Gradle 8.9 (wrapper properties). JVM target 17. |
| SDK levels | minSdk 30, targetSdk 35, compileSdk 35. versionName 0.2.1 (code 3). applicationId `com.cleanforge` (placeholder per `app/build.gradle.kts` comment). |
| Permissions | `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`. `<queries>` for `moe.shizuku.privileged.api`. `ShizukuProvider` exported, guarded by `INTERACT_ACROSS_USERS_FULL`. `allowBackup=false`. No INTERNET. No `<service>`, no foreground service, no WorkManager. |
| Shizuku | `ShizukuManager` (state), `ShizukuFileServiceConnector` (bind on demand, 6 s timeout), `ShizukuFileService` (user service, `daemon(false)`, suffix `fs`), `ShizukuFileBackend` (AIDL calls). AIDL `IFileService` has `destroy, uid, lstat, list, directorySize, delete`. |
| Package inventory | `AndroidPackageInventory` via `PackageManager` in the app process (never through Shizuku). Two questions kept apart: `knownPackageNames()` (includes data-kept uninstalls) and `isCurrentlyInstalled()`. |
| Cache / RAM | Cache cleaning exists for `Android/data/<pkg>/cache` only. No RAM, process-memory, zRAM or swap code anywhere. |
| Tests | 91 `@Test` in 7 classes (matches docs). Fakes: real temp dirs, `HookedFs`, `InMemoryFs`, `FakeCapabilities`, `FakeInventory`. **No test touches `ShizukuFileBackend`, `ShizukuFileServiceConnector`, `ShizukuManager`, `ShizukuFileService`, `ScanRepository` or `CleanViewModel`.** |
| Gradle wrapper | **Present and complete in the tree**: `gradle-wrapper.jar` (43,504 bytes), `gradle-wrapper.properties` (8.9), `gradlew`, `gradlew.bat`. Scripts are committed as mode `100644` (not executable) and duplicated under `gradle/wrapper/`. The jar's authenticity was not checked (CI's `wrapper-validation` is meant to). |
| CI | `.github/workflows/build.yml`: tests, strict lint (non-blocking), `assembleDebug`, `assembleRelease`, artifact upload. I have no run results. Five consecutive commits are titled "Update build.yml". |
| Reproducible here? | **No** (no SDK/Gradle/network). |

## 2. Execution-path trace (what runs where)

| Operation | Process | Mechanism (VERIFIED) |
| --- | --- | --- |
| Shizuku state | app | `ShizukuManager.currentState`: `pingBinder`, `isPreV11`, `checkSelfPermission`. Does not bind the user service. |
| `PackageInventory` | app | `PackageManager.getInstalledPackages(MATCH_UNINSTALLED_PACKAGES)`; `getPackageInfo(pkg, 0)` + `FLAG_INSTALLED`. |
| `CacheScanner`, `CorpseScanner` | classification in app; `list`/`lstat`/`directorySize` in the Shizuku service (shell UID) | `AppModule.provideScanners` gives them the `shizuku` backend. |
| Thumbnail, Temp, APK, Large, Duplicate, Log, EmptyDir scanners | app | `AndroidFsOps` (`Os.lstat`, `File.list`) under All-files access. No Shizuku. |
| Delete `APP_CACHE`, `CORPSE_LEFTOVER` | Shizuku service | `CleaningEngine` routes on `item.requiresShizuku`; service runs `SafeDeleter`. |
| Delete everything else | app | `StandardFileBackend` runs `SafeDeleter`. |
| RAM / process memory | none | not implemented |

Consequence for your stated intent ("unusable without Shizuku"): in the code only 2 of 9 scanners need Shizuku. Without it the other 7 run under All-files access. The UI says so ("Shizuku is optional"). The code and docs agree with each other; they disagree with your description.

## 3. Findings

Priority: P0 security/data-loss/fundamental correctness, P1 core functionality, P2 important gap, P3 refinement.

### F-01 [P0 gate] Destructive code has never been compiled, tested or run
- **Where**: whole repo. README banner, `BUILD.md`, `SECURITY.md`, `AUDIT_RESPONSE.md` all say so.
- **Evidence**: VERIFIED statements in the docs; I could not run anything either. 91 tests exist and none are known to have run.
- **Verify**: `./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` on Linux with JDK 17 and SDK 35; then experiments E-01 to E-04.
- **Expected vs actual**: a tool that permanently deletes files should have a green build and a device smoke test before anyone points it at real data. Actual: unknown.
- **Fix**: Phase 0 of the roadmap. **Risks**: first build likely needs small fixes (`BUILD.md` lists the likeliest: `UserServiceArgs`, Shizuku listeners, Hilt/KSP, Material 3 names, AIDL ids).

### F-02 [P1] Shizuku failures look like "nothing found" or "already gone"
- **Where**: `core/backend/ShizukuFileBackend.kt` `call()` (L23-33); `CacheScanner.scan` L45 (`list ... ?: return@flow`), L53, L55; `CorpseScanner.scan` L47, L53, L57; `CleaningEngine.processItem` L97; `ScanRepository.scan` L55-58; `CleanScreen` L73-81.
- **Evidence**: when `connector.get()` is null (bind timeout, service dead, not authorized) or a `RemoteException`/`RuntimeException` occurs, every method returns its default (`null`). `null` also means "path missing" and "path not allowed". Scanners treat `null` as empty and `continue`/`return`. `ScanRepository` stores the empty list as a successful scan, so no `ScannerFailed` is emitted. The Clean tab then shows "No results yet. Run a scan..." The engine reports `null` from `lstat` as SKIPPED "Already gone".
- **Verify**: fake `IFileService` that returns null / throws `DeadObjectException` / never connects; assert scanner outcome and engine message. On device: stop Shizuku between the permission check and the scan.
- **Expected**: unavailable, denied, missing and empty are distinct, and the UI says the scan was incomplete. **Actual**: indistinguishable.
- **Fix**: typed results at the backend boundary (ARCHITECTURE_PLAN section 4). **Depends on**: none; do first.

### F-03 [P1] "Shizuku connected" does not prove the privileged path works
- **Where**: `ShizukuManager.currentState`; `AndroidCapabilities.shizukuAuthorized()`; `StatusCards.ShizukuCard` L137-144; `ShizukuManager.requestPermission`.
- **Evidence**: `Authorized` means binder alive, version >= 11 and permission granted. The card then states "App caches and uninstalled-app leftovers are available." Nothing binds the user service, calls `uid()`, or lists `Android/data` first. `shouldShowRequestPermissionRationale` is never used (0 hits), so a "deny and don't ask again" choice leaves the card on "Grant permission" with a button that does nothing visible.
- **Verify**: E-01 and E-02. **Expected**: capability states are proven by a probe and shown with a reason. **Actual**: inferred.
- **Fix**: `CapabilityProbe` (plan section 3). Add permanent-denial guidance. **Risk**: probe must stay read-only.

### F-04 [P1] Cleanup is not protected against interruption and leaves no record
- **Where**: `CleanViewModel.confirmClean` L171-212; `ShizukuFileServiceConnector` L34 `.daemon(false)`; manifest (no `<service>`).
- **Evidence**: cleaning runs in `viewModelScope`; if the ViewModel is cleared or the process dies mid-plan, the loop stops (the engine checks `ensureActive` only between items). Per Shizuku API semantics (LIKELY, verify) a non-daemon user service ends with its client. No journal exists, so after a kill the user cannot tell what was deleted. The catch block at L201-210 sets `cleaning = null`, discarding the partial report and skipping `repository.forget()`.
- **Verify**: E-03 (kill app mid-delete, kill Shizuku mid-delete, reboot between scan and delete). **Fix**: deletion journal plus a decision on foreground service vs in-process (plan D-2). **Risk**: a foreground service adds permissions and breaks `PolicyGuardTest` "exactly two permissions".

### F-05 [P1] The orphan age gate does not measure what it claims
- **Where**: `CorpseScanner.scan` L55 (`ageDays = (nowMs() - st.mtimeMs) / DAY_MS`); `minAgeDays = 14`; reason text L63-65.
- **Evidence**: `st` is `lstat` of `Android/<area>/<pkg>`. A directory's mtime changes when its direct entries change, not when nested files change, and it does not record when the app was uninstalled. A folder of an app uninstalled yesterday passes the gate if its top-level entries were last touched long ago. This gate guards the only `TREE`, `NOT_REVERSIBLE` category.
- **Verify**: E-09 (uninstall an app, check folder mtime). **Expected**: "at least 14 days since the app disappeared". **Actual**: "top-level entries unchanged for 14 days".
- **Fix**: persist first-seen-orphan time per package (needs the snapshot store, roadmap phase 4) or at least a small persisted map; until then reword the reason and raise the default. **Depends on**: persistence.

### F-06 [P2] Size results ignore completeness and unreadable subfolders
- **Where**: `StorageWalker.walk` L28 (`fs.list(dir) ?: continue`) and `sizeOf`; `CacheScanner` L55-56; `CorpseScanner` L57-58; `ShizukuFileBackend.directorySize`.
- **Evidence**: `SizeResult.complete` is produced but never read by either Shizuku scanner. Unreadable subdirectories are skipped silently and the sum is still `complete = true` unless a depth/entry bound was hit. Item sizes and "selected X MB" may be undercounts presented as exact.
- **Fix**: carry `complete` and "unreadable count" into `ScanItem` and show "at least". **Risk**: low.

### F-07 [P2] The Shizuku service cannot refuse deletion of an installed app's data
- **Where**: `ShizukuFileService.delete` L52-57; `PathRules.isServiceDeletable`.
- **Evidence**: the service checks path shape only. `TREE` is accepted for any `Android/{data,obb}/<valid package name>`, including installed apps and CleanForge itself. Installed-state is enforced only in the app process (`CleaningEngine` L71-85). The doc claim that the service "trusts nothing" is true for path shape, not for app state.
- **Expected**: defence in depth against an app-side logic bug. **Actual**: single guard.
- **Fix options**: app passes a per-session deny-list (installed set plus own package) that the service enforces; or accept the residual and document it. The service cannot independently verify installed state without hidden APIs. **Risk**: a deny-list from the app is not a security boundary against a compromised app, only against bugs.

### F-08 [P2] The Shizuku IPC layer and the ViewModel are untested
- **Where**: `src/test/**` (no class references `ShizukuFileBackend`, `ShizukuFileServiceConnector`, `ShizukuManager`, `ShizukuFileService`, `ScanRepository`, `CleanViewModel`). `AUDIT_RESPONSE.md` already lists Shizuku adversarial cases and reboot-between-scan-and-delete as having no automated test.
- **Fix**: extract an interface for `IFileService` use so a fake can be injected; add disconnect, timeout, null, oversize-list and rebind cases. Add `ScanRepository` tests for skipped/failed/empty distinction.

### F-09 [P2] Every scanner re-walks the tree; there is no shared inventory
- **Where**: `TempScanner`, `LogScanner` (Download), `ApkScanner` (Download, Documents), `LargeFileScanner`, `DuplicateScanner` (six roots), `EmptyDirectoryScanner` (four roots).
- **Evidence**: each builds its own `StorageWalker`; Download is traversed up to four times per full run, with one `Os.lstat` per entry. Heavy scans are on-demand, which helps, but the structure also blocks requirements 2, 3, 4 and 8 (section 4).
- **Fix**: single-pass indexer (plan section 5). Measure first (E-11).

### F-10 [P2] Scan coverage is a hard-coded list, and `Android/media` is unreachable
- **Where**: `LargeFileScanner.ROOTS` L65 (Download, DCIM, Pictures, Movies, Documents, Music), reused by `DuplicateScanner`; `SafetyValidator.validateDeletion` L66-71.
- **Evidence**: other top-level folders and `Android/media` are never scanned. The validator rejects any `Android/` path that is not an app cache or a leftover root, so even a file the user ticks under `Android/media` cannot be deleted. On Android 11+ messaging apps keep media there (LIKELY; the policy lists WhatsApp/Telegram top-level folders but the scanners never see `Android/media`).
- **Expected vs actual**: product decision, not a bug. Needs your call (plan D-3).

### F-11 [P2] All non-messaging external caches are pre-ticked as "high confidence, trivially recreatable"
- **Where**: `CacheScanner` L58-73; `ScanItem.isPreselected`; `ProtectedApps.MESSAGING` (11 package names).
- **Evidence**: the OS defines `Android/data/<pkg>/cache` as disposable, which is the right evidence for the category. But any app may put offline content there; the code's only exception list is the 11 messaging packages. Whether real apps do this on the target phone is UNKNOWN.
- **Verify**: E-02 on a real device; inspect the largest caches. **Fix**: show per-app sizes and keep pre-selection, or require a first-run confirmation per app. **Risk**: low.

### F-12 [P3] Fingerprint precision
- **Where**: `AndroidFsOps.lstat` L18 (`st_mtime * 1000L`).
- **Evidence**: seconds resolution (`st_mtim` is API 31+; minSdk is 30). A same-size file rewritten within one second is not detected by `sameObjectAs`. Inode reuse is already documented in `SECURITY.md`. How `st_dev`/`st_ino` behave on the FUSE-backed `/storage/emulated/0` is UNKNOWN (E-01).

### F-13 [P3] Report accuracy and cancellation
- **Where**: `CleaningEngine.processItem` L126 (`else -> failed(...)` drops `bytesFreed`) and L111-114 (verify treats `after == null` as gone, including when the Shizuku service is unreachable); `SafeDeleter.purge` (no cancellation check); `ShizukuFileBackend` (blocking binder calls).
- **Evidence**: partial progress under `LIMIT_EXCEEDED` is not credited. Freed bytes are logical `st_size`, not allocated blocks. A single item with up to 200,000 entries cannot be cancelled mid-item.

### F-14 [P3] Service lifecycle details
- **Where**: `ShizukuFileServiceConnector`; `ShizukuFileService.list`.
- **Evidence**: `unbind()` and `ShizukuManager.unregister()` have no callers. `onServiceDisconnected` does not complete `pending`. Whether `bindUserService` re-delivers `onServiceConnected` when re-called with the same `ServiceConnection` after a disconnect is UNKNOWN (E-08). `MAX_NAMES = 20_000` bounds entry count, not parcel size (`TransactionTooLargeException` is a `RemoteException`, so the backend would invalidate a healthy service; E-07).

### F-15 [P3] Build and supply-chain hygiene
- **Evidence**: `gradlew`/`gradlew.bat` are mode `100644` (`git ls-files -s`), so `./gradlew` fails on Linux/macOS until `chmod +x` (CI runs `chmod`); duplicate scripts under `gradle/wrapper/` from commit c8ed888; no `distributionSha256Sum`; no dependency verification metadata; CI actions pinned by tag, no `permissions:` block; stray `#Aman` first line in `build.yml`; `BUILD.md`, README banner and CI comments still say the wrapper is missing; `PolicyGuardTest` skips silently via `assumeTrue` if it cannot find the module directory; versions are about two years old (AGP 8.7, Compose BOM 2024.11).

### F-16 [P3] Release blockers outside code
- No `LICENSE` file (README: "not chosen yet"); applicationId placeholder; `SECURITY.md` contact is `<contact: TODO by Aman>`; accessibility not audited (5 icons with null content description, presumably decorative); only English strings.

## 4. Product completeness (your 12 requirements)

| # | Requirement | Status | Notes |
| --- | --- | --- | --- |
| 1 | Shizuku-aware detection and privileged maintenance | **PARTIAL** | Detection implemented but unverified (F-03). Privileged work is limited to read/size on `Android/{data,obb}` and two recursive delete shapes. Failure states are conflated (F-02). |
| 2 | Recursive storage inventory, directory sizes | **PARTIAL** | Bounded walker and `directorySize` exist (primitives). No inventory, no directory-size tree, no UI. |
| 3 | Largest files / large directories | **PARTIAL** | Large files: IMPLEMENTED BUT UNVERIFIED (>= 100 MiB, top 200, six roots). Large directories: MISSING. |
| 4 | File-type and category breakdowns | **MISSING** | "Category" in the app means cleaning category only. |
| 5 | App storage statistics, accessible app data | **PARTIAL** | Only external cache size per installed app (via Shizuku). Per-app screen was removed (needs Usage access, which is a permission the app can request; not blocked by the platform). `/data/data` is BLOCKED BY PLATFORM LIMITATIONS without root or hidden APIs. |
| 6 | Duplicate detection | **IMPLEMENTED BUT UNVERIFIED** | Staged SHA-256, hard links excluded, user picks survivors. Six roots, files >= 1 MiB. |
| 7 | Evidence-based orphan candidates | **PARTIAL** | Uninstalled-app folders in `Android/{data,obb}`: IMPLEMENTED BUT UNVERIFIED, with F-05. Orphans elsewhere: MISSING. |
| 8 | Persistent snapshots, growth analysis | **MISSING** | Results live in memory (`ScanRepository`); Room/DataStore were removed as unused. |
| 9 | RAM, process memory, pressure, swap/zRAM | **MISSING** | The README disclaims "RAM/boost" claims. Per-process data of other apps is BLOCKED for an ordinary app; with Shizuku it is UNKNOWN (E-10). |
| 10 | Efficient scanning on low-end phones | **PARTIAL** | Bounded walks, batches of 50, sequential deletion, on-demand heavy scans. Repeated traversals (F-09), full-file SHA-256, no measurements. |
| 11 | Safe preview, execution, verification | **IMPLEMENTED BUT UNVERIFIED** | Strongest module: `ConfirmedPlan` type gate, revalidation, identity checks, verification. Gaps F-02, F-04, F-07, F-13. |
| 12 | Diagnostics, error reporting, documentation | **PARTIAL** | Per-item outcomes and skip notices exist. No exportable diagnostics, no capability report. Docs are extensive but partly stale (F-15). |

## 5. Preserve (do not rewrite)

`core/fs/SafeDeleter`, `PathRules`, `StorageWalker`; `core/safety/SafetyValidator`; `core/cleaning/PlanBuilder` and the `ConfirmedPlan` gate; `CleaningEngine` (fix messages, not structure); the `known` vs `installed` split in `PackageInventory`; the `FsOps` seam; the AIDL service shape; fixtures and the 91 tests; the explicit-Hilt `AppModule`; the "no INTERNET, no shell" guards.

## 6. Reconciliation with existing audit documents

| Claim in repo docs | Result |
| --- | --- |
| Wrapper jar and scripts not committed (`BUILD.md`, README banner, CI comments) | **STALE.** Jar and `gradlew` were added in 7e65024 ("Wrapper Upload"); c8ed888 added duplicate copies under `gradle/wrapper/`. Mode and duplicates: F-15. |
| 91 unit tests | Count VERIFIED. Execution not verified. |
| Only two recursive delete shapes in the service | VERIFIED for shape (`PathRules.isServiceDeletable`); installed-state not enforced there (F-07). |
| Shizuku is optional; only app caches and leftovers need it | VERIFIED (`AppModule.provideScanners`). Contradicts your description. |
| No shell anywhere | VERIFIED by grep. |
| Walks bounded, symlinks never followed | VERIFIED (`StorageWalker` uses `lstat`; only directories are listed). |
| Fails closed when the package list is missing or under 20 | VERIFIED (`CacheScanner`, `CorpseScanner`, `CleaningEngine`). It is a heuristic; E-04. |
| Freed bytes come from what was really removed | PARTLY: F-13. |
| List-key crash fixed (category + path) | VERIFIED (`ItemComponents` L139). |
| "Child race narrowed, not closed" | VERIFIED as documented in `SafeDeleter` header and `SECURITY.md`. |
| `PROJECT_AUDIT.md` 53 Kotlin files | Now 55 (47 main + 8 test). Not an error, just changed. |

## 7. Experiments required (nothing here is assumed to work)

| ID | Experiment | Answers |
| --- | --- | --- |
| E-01 | Android 11 target phone + Shizuku (wireless debugging): bind service, call `uid()`, list `Android/data`, `lstat` the same dirs twice and across an app restart; record `st_dev`/`st_ino`/mtime | F-03, F-12; does shell see `/storage/emulated/0/Android/data` through the service |
| E-02 | Scan caches; inspect the 10 largest `cache/` dirs for offline media; delete one test app's cache; verify with `adb shell ls` | F-11; delete works |
| E-03 | Kill Shizuku mid-scan and mid-delete; kill the app mid-delete; reboot between scan and delete | F-02, F-04 |
| E-04 | `pm uninstall -k --user 0 <pkg>`; compare `knownPackageNames` and `isCurrentlyInstalled` on API 30, 33, 34, 35; check package count on a clean profile | `MIN_PLAUSIBLE_PACKAGES`, H1 fix |
| E-05 | Dual apps / work profile / secondary user: where do their `Android/data` folders live | multi-user claims |
| E-06 | Android 13 to 15: shell access to `Android/data` through FUSE; All-files grant flow for a sideloaded APK | version support |
| E-07 | `list()` on a directory with more than 10,000 entries | F-14 |
| E-08 | Re-call `bindUserService` after `onServiceDisconnected` | F-14 |
| E-09 | Uninstall an app; compare its `Android/data/<pkg>` mtime with the uninstall time | F-05 |
| E-10 | Memory signals from the app process and from a Shizuku service: see ARCHITECTURE_PLAN section 6 | req. 9 |
| E-11 | Time and I/O of a full scan on the low-end target with about 100k files | F-09, req. 10 |
| E-12 | Folder name `COM.Example.App` vs package `com.example.app` on the case-insensitive layer | package-name matching |
