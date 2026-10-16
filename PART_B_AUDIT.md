# CleanForge Part B audit: privileged layer (Shizuku)

Scope: `core/privileged/*` (10 files), `core/shizuku/*` (6 files), `AndroidManifest.xml`, `proguard-rules.pro`, and the five Part B tests (`CapabilityRegistryTest`, `FileServiceCoreTest`, `MaintenanceModelTest`, `PrivilegedExecutorTest`, `WireCodecTest`). Part B sources were byte-identical to the copies in the Part A zip; none had been read before this audit.

## Bugs found and fixed

| # | Severity | File | Bug | Fix |
|---|----------|------|-----|-----|
| 1 | High | `privileged/FileServiceCore.kt` | `list()` built `ListReply(..., detail = ...)`, but `ListReply` has no `detail` parameter: the module did not compile. | Removed the argument. `FileServiceCoreTest` already expects `OS_DENIED` with no names. |
| 2 | Low | `shizuku/ShizukuFileServiceConnector.kt` | `acquire()` and `restart()` made Shizuku status and bind/unbind binder calls on the caller's thread (possibly main), contradicting the `PrivilegedSessionProvider` contract. | Both now run on `Dispatchers.IO`. |
| 3 | Low | `shizuku/ShizukuManager.kt` | Shizuku's listeners fire on the main thread and `refresh()` makes binder calls (also from `register()` in `Application.onCreate`), against its own "never on main" note. | Listeners and `register()` refresh on a worker; `refresh()` is `@Synchronized` so an older reading cannot overwrite a newer one. |
| 4 | High (cross-part) | `fs/SafeDeleter.kt` (Part A) | Compared `st_dev` of every ancestor from `/storage`, which is a tmpfs on Android. Every real deletion through this service (and the app) came back `REFUSED`. | Only folders below `/storage/emulated/0` are inspected, as non-symlink directories; no device comparison. Regression tests added. |

## Checked and found sound

- **Service core:** only two delete shapes (`Android/data|obb/<pkg>` tree, `Android/data/<pkg>/cache` contents); installed-apps guard required for tree deletes; own package never deletable; identity (dev/ino) required; ancestors and symlinks checked.
- **Protocol:** `WireCodec` bounds match the service caps (20k names, 255-char names); transaction ids match the AIDL contract test.
- **Executor and registry:** write lock plus read permits, deadlines with restart, post-delete verification, and capability states all behave as the tests describe. No lock cycle between registry, executor and connector.
- **Manifest:** permissions match `PolicyGuardTest` (exactly `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `KILL_BACKGROUND_PROCESSES`); `ShizukuProvider` correctly declared; `allowBackup=false`; no INTERNET.
- **ProGuard:** the user-service constructor, `IFileService$Stub` and the AIDL interface are kept.

## Remaining items, now resolved

- **Unreachable forensic cleanup (Android/data):** by design. `TikTokScanner`, `TwitterXScanner` and `InstagramScanner` are dormant (not registered) because the service has no single-file delete. They are now documented as dormant in their KDoc. Registering them requires a new, strictly allowlisted file-delete shape first.
- **Per-item revalidation cost:** `CleaningEngine` now asks for the full package list and the Shizuku capability probe once per run (per-package install state and Shizuku authorization are still asked per item).
- **Platform scanners did not compile** (`isPreselected` constructor argument, old nullable backend API): WhatsApp, Telegram, TikTok, Twitter and Instagram scanners are now thin subclasses of `ForensicFolderScanner`, matching `ForensicScannersTest`.

## Tests

- Not compiled or run: the sandbox has no Kotlin/Gradle toolchain and `testutil/Fixtures.kt` was not supplied. All changes were checked by reading code against tests, plus brace and paren balance.
- Regression tests added for the cross-part fixes: `SafeDeleterTest` (3), `StorageWalkerTest` (4, new), `PlanBuilderTest` (2), `CleaningEngineTest` (2), `ForensicValidatorTest` (1), `ScanRepositoryTest` (1), `StandardFileBackendTest` (1, new).
- Run: `./gradlew testDebugUnitTest`.

## Still needed from you

- **Part D:** confirm `buildFeatures { buildConfig = true }` (the Shizuku files use `BuildConfig.DEBUG`, `APPLICATION_ID`, `VERSION_CODE`).
- **`di/AppModule.kt`:** `CleaningEngine` has no `@Inject`; it must be provided there, and the executor and registry must be singletons (each owns a coroutine scope).
- **`testutil/Fixtures.kt` and `IFileService.aidl`:** to compile the tests and verify the stub signatures.
