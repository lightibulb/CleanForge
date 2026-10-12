# Part 2 handoff: Shizuku engine

Status: **implemented, statically checked, NOT compiled, NOT run.** This environment has a Java runtime only: no Kotlin
compiler, Gradle, Android SDK or network. No test in this change has been executed. Everything below says which claims
rest on reading the code, on a JVM unit test that was written but not run, or on a device that nobody has used yet.
Parts 3 to 5 were not started.

## 1. Defects found and fixed

"Evidence" is the code at the starting commit, read, not run.

| ID | Defect (evidence) | Fix | Tests |
|---|---|---|---|
| S-01 | Every backend read collapsed to `null`: missing, refused, empty and "Shizuku service dead" were indistinguishable (`ShizukuFileBackend.call` defaulted to null). A dead service looked like "Already gone"; a failed listing looked like "nothing to clean". | `FsResult` (Ok / NotFound / Denied / Unavailable / Failed); service replies carry a status (`ReplyStatus`); engine and scanners handle each case. | `FileServiceCoreTest`, `PrivilegedExecutorTest`, `CleaningEngineTest`, `ScannerEvidenceTest` |
| S-02 | "Authorized" was treated as "operations work". | `CapabilityRegistry` per operation: verified / ready / needs permission / unavailable / unsupported; read-only probe; live Shizuku state overrides any cached status. | `CapabilityRegistryTest`, `PrivilegedExecutorTest` |
| S-03 | "Deny and don't ask again" was undetectable; the Grant button silently did nothing. | New states `PermissionPermanentlyDenied`, `ServerTooOld`; the card tells the user to open Shizuku. | `CapabilityRegistryTest` (state table); device D-02 |
| S-04 | Connector: one shared connection and pending holder across attempts, `onServiceDisconnected` not handled, nothing cleaned up after a bind timeout, `catch Throwable` with no reason, no protocol check, unobserved cancellation. | New `ShizukuFileServiceConnector`: fresh connection per attempt, deadline, removal of a half-started service, version handshake, drop on death or revoked permission, reasons for every failure. | device only (D-06, D-07, D-14) |
| S-05 | Binder calls were blocking with no timeout and could not be cancelled. | `PrivilegedExecutor`: deadline per call, restart of the service on a deadline (frees the stuck thread), cancel of a running delete between entries, bounded concurrency (2 reads, 1 delete). | `PrivilegedExecutorTest` (hang, cancel, concurrency); device D-08, D-09 |
| S-06 | Partial progress was lost (`LIMIT_EXCEEDED` fell into "failed" without bytes); only DELETED / SKIPPED / FAILED existed. | `PARTIAL` and `UNVERIFIED` outcomes; `classifyDeletion`; the report separates unconfirmed bytes. | `MaintenanceModelTest`, `PrivilegedExecutorTest`, `CleaningEngineTest` |
| S-07 | A lost reply, a timeout or a service death mid-delete had no defined result. | After an uncertain call the target is observed through a fresh binding: gone / empty -> Success with unknown bytes, otherwise Unverified. Deletions are never retried. | `PrivilegedExecutorTest` |
| S-08 | Install state was a boolean that failed open for leftovers: a failed package-manager query read as "not installed". | `InstallState` (INSTALLED / UNINSTALLED_DATA_KEPT / NOT_KNOWN / UNKNOWN); only `NameNotFoundException` means NOT_KNOWN; UNKNOWN skips the item. Folder names matched to known packages case-insensitively. | `ScannerEvidenceTest`, `CleaningEngineTest`, `PolicyGuardTest` |
| S-09 | `lstat` protects only the last path component: a symlink among the ancestors would be followed. | `FileServiceCore` requires every ancestor below the storage root to be a real directory. | `FileServiceCoreTest` |
| S-10 | The service deleted any package folder on request; the "installed" check lived only in the app. | Installed-apps guard pushed by the app and enforced by the service; tree deletes refused until it is set, for known packages (any case) and for CleanForge itself. | `FileServiceCoreTest`, `PrivilegedExecutorTest` |
| S-11 | Replies could not say why; a 20,000-name listing could approach the 1 MB binder limit; unreadable sub-folders were skipped silently and the size shown as exact. | AIDL methods 6 to 12 (append-only), bounded listing, `SizeResult.unreadable`, `SizeQuality` (measured / lower bound), "≥" in the UI. | `WireCodecTest`, `FileServiceCoreTest`, `ScannerEvidenceTest` |
| S-12 | A cleanup that threw lost its partial report; no way to stop it. | Stop button; `ItemStarted` / outcome in progress events; stopped or failed runs report finished, in-flight (unconfirmed) and not-attempted items. | `CleaningEngineTest` (events); VM is not unit-tested |
| S-13 | Shizuku binder calls (`refresh`, `requestPermission`) ran on the main thread. | Moved to IO; capability probes run on IO. | `PolicyGuardTest` (source-level guard) |
| S-14 | Empty scan after a failed listing looked like "No results yet". | Scanners report `incompleteReasons`; repository emits `ScannerIncomplete`; the empty state says it is not proof. | `ScanRepositoryTest`, `ScannerEvidenceTest` |

## 2. What changed

Architecture kept: scanners classify, the engine revalidates, deletes and verifies, `SafeDeleter` stays the only deleter.
Additions: `core/privileged/*` (executor, registry, service core, codec, results) and `AidlPrivilegedFileApi`.

Deviations from the Part 1 plan, with reasons:
- `ShizukuFileBackend` is read-only; privileged deletes are typed requests (`MaintenanceRequest`), never "path + mode".
- `FileOperationBackend` was split into `FileReadBackend` and `FileOperationBackend` (read + delete) for that reason.
- A new `deleteEx` method (id 10) was added so delete results can carry their detail text; methods 2 to 5 are untouched.
- List and delete replies are `String[]` with a header instead of a `Bundle`, so the whole codec runs in JVM tests.
- One existing test was edited on purpose: `PolicyGuardTest` "installed and known packages stay two separate questions" used to require `isCurrentlyInstalled(` in `CacheScanner` and `CleaningEngine`; it now requires `installState(`, forbids the old boolean there, and checks the new lookup. `CleaningEngineTest`'s `engine()` helper now builds the executor. No other existing test changed.

### Modified files (35)
- `CHANGELOG.md`
- `README.md`
- `SECURITY.md`
- `app/src/main/aidl/com/cleanforge/shizuku/IFileService.aidl`
- `app/src/main/java/com/cleanforge/core/backend/Capabilities.kt`
- `app/src/main/java/com/cleanforge/core/backend/FileOperationBackend.kt`
- `app/src/main/java/com/cleanforge/core/backend/PackageInventory.kt`
- `app/src/main/java/com/cleanforge/core/backend/ShizukuFileBackend.kt`
- `app/src/main/java/com/cleanforge/core/backend/StandardFileBackend.kt`
- `app/src/main/java/com/cleanforge/core/cleaning/CleaningEngine.kt`
- `app/src/main/java/com/cleanforge/core/fs/AndroidFsOps.kt`
- `app/src/main/java/com/cleanforge/core/fs/FsOps.kt`
- `app/src/main/java/com/cleanforge/core/fs/SafeDeleter.kt`
- `app/src/main/java/com/cleanforge/core/fs/StorageWalker.kt`
- `app/src/main/java/com/cleanforge/core/model/CleaningPlan.kt`
- `app/src/main/java/com/cleanforge/core/model/ScanItem.kt`
- `app/src/main/java/com/cleanforge/core/scanner/CacheScanner.kt`
- `app/src/main/java/com/cleanforge/core/scanner/CorpseScanner.kt`
- `app/src/main/java/com/cleanforge/core/scanner/Scanner.kt`
- `app/src/main/java/com/cleanforge/core/shizuku/ShizukuFileService.kt`
- `app/src/main/java/com/cleanforge/core/shizuku/ShizukuFileServiceConnector.kt`
- `app/src/main/java/com/cleanforge/core/shizuku/ShizukuManager.kt`
- `app/src/main/java/com/cleanforge/core/shizuku/ShizukuState.kt`
- `app/src/main/java/com/cleanforge/data/repository/ScanRepository.kt`
- `app/src/main/java/com/cleanforge/di/AppModule.kt`
- `app/src/main/java/com/cleanforge/ui/CleanForgeNavHost.kt`
- `app/src/main/java/com/cleanforge/ui/CleanViewModel.kt`
- `app/src/main/java/com/cleanforge/ui/Format.kt`
- `app/src/main/java/com/cleanforge/ui/components/Dialogs.kt`
- `app/src/main/java/com/cleanforge/ui/components/ItemComponents.kt`
- `app/src/main/java/com/cleanforge/ui/components/StatusCards.kt`
- `app/src/main/java/com/cleanforge/ui/screens/CleanScreen.kt`
- `app/src/test/java/com/cleanforge/core/CleaningEngineTest.kt`
- `app/src/test/java/com/cleanforge/core/PolicyGuardTest.kt`
- `app/src/test/java/com/cleanforge/core/ScannerEvidenceTest.kt`

### Added files (26)
- `app/src/main/java/com/cleanforge/core/backend/FsResult.kt`
- `app/src/main/java/com/cleanforge/core/privileged/BlockingCall.kt`
- `app/src/main/java/com/cleanforge/core/privileged/CapabilityModel.kt`
- `app/src/main/java/com/cleanforge/core/privileged/CapabilityRegistry.kt`
- `app/src/main/java/com/cleanforge/core/privileged/FileServiceCore.kt`
- `app/src/main/java/com/cleanforge/core/privileged/MaintenanceModel.kt`
- `app/src/main/java/com/cleanforge/core/privileged/PrivilegedExecutor.kt`
- `app/src/main/java/com/cleanforge/core/privileged/PrivilegedFileApi.kt`
- `app/src/main/java/com/cleanforge/core/privileged/PrivilegedOperation.kt`
- `app/src/main/java/com/cleanforge/core/privileged/PrivilegedSessionProvider.kt`
- `app/src/main/java/com/cleanforge/core/privileged/WireCodec.kt`
- `app/src/main/java/com/cleanforge/core/shizuku/AidlPrivilegedFileApi.kt`
- `app/src/main/java/com/cleanforge/core/shizuku/ShizukuStateSource.kt`
- `app/src/test/java/com/cleanforge/core/CapabilityRegistryTest.kt`
- `app/src/test/java/com/cleanforge/core/FileServiceCoreTest.kt`
- `app/src/test/java/com/cleanforge/core/MaintenanceModelTest.kt`
- `app/src/test/java/com/cleanforge/core/PrivilegedExecutorTest.kt`
- `app/src/test/java/com/cleanforge/core/ScanRepositoryTest.kt`
- `app/src/test/java/com/cleanforge/core/WireCodecTest.kt`
- `app/src/test/java/com/cleanforge/testutil/PrivilegedFakes.kt`
- `docs/ARCHITECTURE_PLAN.md` (verbatim copy of the Part 1 file)
- `docs/CAPABILITY_AUDIT.md` (verbatim copy of the Part 1 file)
- `docs/IMPLEMENTATION_ROADMAP.md` (verbatim copy of the Part 1 file)
- `docs/PART2_HANDOFF.md`
- `docs/SHIZUKU_CAPABILITY_MATRIX.md`
- `docs/SHIZUKU_SETUP_AND_COMPATIBILITY.md`

## 3. Tests

**Actual results: none. The tests were written, not run.**

| | Before | Now |
|---|---|---|
| `@Test` methods | 91 | 211 (+120) |

New files: `FileServiceCoreTest` (22), `PrivilegedExecutorTest` (29), `CapabilityRegistryTest` (15), `MaintenanceModelTest` (14), `WireCodecTest` (9), `ScanRepositoryTest` (8). Extended: `CleaningEngineTest` (+9), `ScannerEvidenceTest` (+10), `PolicyGuardTest` (+4).

What the new tests cover, as requested:
- authorized / unauthorized / disconnected / unsupported / too-old / permanently denied: `CapabilityRegistryTest`, `PrivilegedExecutorTest` (gate), `ScanRepositoryTest`;
- service death before, during and after a delete, rebinding, retry of reads only: `PrivilegedExecutorTest`, `CleaningEngineTest`;
- hang and timeout, caller cancellation, bounded concurrency: `PrivilegedExecutorTest` (real time; 150 to 750 ms deadlines);
- invalid targets (`../`, spaces, `;`, `&&`, `$()`, backticks, newline, empty), traversal, trailing slash, symlinked ancestors: `FileServiceCoreTest`, `MaintenanceModelTest`, `PrivilegedExecutorTest`;
- file names with spaces, unicode, quotes, `;`, `&&`, `|`, `$`, newline: `WireCodecTest`, `FileServiceCoreTest`, `PrivilegedExecutorTest`;
- operation failure, partial cleanup, files reappearing, unreadable end state: `PrivilegedExecutorTest`, `CleaningEngineTest`, `MaintenanceModelTest`;
- result verification and classification of every delete status: `MaintenanceModelTest`;
- the AIDL ids and no-shell rules: `PolicyGuardTest`.

What **was** run: `docs`-independent static checks only (`tools/static_check.py`, kept outside the repository): all 75 Kotlin files have a package matching their directory, balanced brackets, every project import resolves, no project type or top-level function is used without an import. This does not catch type errors, wrong parameter names, exhaustiveness errors or AIDL/Hilt generation problems.

Needs a device (not covered by any JVM test): Binder, the AIDL stub and generation, `ShizukuFileServiceConnector`, `ShizukuManager`, `AidlPrivilegedFileApi`, the "don't ask again" semantics, service restart on timeout, concurrent `cancelCurrent`, `Android/data` readability per Android version and vendor, the Compose UI. Checklist D-01 to D-14 in `docs/SHIZUKU_CAPABILITY_MATRIX.md`.

Where the first build is most likely to fail: the AIDL file (`in String[]`, appended ids), the Shizuku signatures listed in `BUILD.md`, Kotlin exhaustiveness in `when` over the new states, Hilt wiring in `AppModule`.

## 4. Remaining limitations

- Nothing has compiled or run; the first `./gradlew :app:testDebugUnitTest` will probably need small fixes.
- No device verification of any Shizuku behaviour, including whether the shell can list `Android/data` on the owner's phone.
- Cancellation is best effort: a cancel that reaches the service before a delete starts is lost; the app-side wait ends at once and the item is reported from what is observed.
- Standard-path (non-Shizuku) deletes do not yet check symlinked ancestors; only the service does.
- Sizes are logical bytes; no `StorageStatsManager` figures (Part 5). Primary user only.
- The leftover reason text still says "last changed N days ago" from the folder's own timestamp (roadmap item for a later part).
- There is still no deletion journal.
- A probe binds the service (a small process) whenever the app resumes with Shizuku authorized; probes younger than 3 s are reused.
- `ShizukuManager` / `ShizukuFileServiceConnector` are not unit-testable as written; further coverage needs a seam over `Shizuku`'s static API.

## 5. For Part 3

Nothing in Part 2 blocks Part 3. The seams to build on: `FsResult`, `PrivilegedExecutor`, `MaintenanceResult`, `CapabilityRegistry`. Before Part 3 starts, someone with an Android toolchain should run the build and the unit tests and report failures, then go through D-01 to D-14.
