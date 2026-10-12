# Changelog

All notable changes to CleanForge. Format follows [Keep a Changelog](https://keepachangelog.com/).

---

## [0.5.1] — Part 3/4 audit fixes

### Fixed
- Part 3 scanners did not compile (passed `isPreselected` to `ScanItem`, used the old nullable `list`/`lstat` API instead of `FsResult`); rebuilt on a shared `ForensicFolderScanner`
- Part 3 scanners were never registered in `AppModule`; WhatsApp and Telegram now are
- WhatsApp/Telegram items could never be deleted: `Android/media` counts as a protected zone, so `SafetyValidator` refused every one; and `requiresShizuku = true` routed them to a Shizuku path that cannot delete files. They now use All-files access and the validator exempts only the exact allowlist
- Validator now requires `owningPackage` to match the path and accepts only direct-child files (no nested `Databases/x/msgstore-*`)
- "Sent" media was labelled "Already in Gallery / HIGH / SAFE" without evidence; now MEDIUM / ELEVATED. Chat backups are ELEVATED
- Scanners swallowed errors silently; they now report `incompleteReasons()` (unreadable folder, unknown install state, sub-folders, dead Shizuku channel) and honour cancellation
- Both WhatsApp and WhatsApp Business are scanned when both are installed
- `AppModule` did not compile (missing imports for `ActivityManager`, `MemoryInfoProvider`, `ProcMemInfoProvider`)
- `ClearMemoryUseCase`: used `isCurrentlyInstalled` instead of `installState`, counted packages as "processes touched", reported "Freed 0 MB" when memory could not be read, and would trim system apps (launcher, keyboard). Now skips system apps and CleanForge itself, reports packages asked, and says "unknown" when unmeasured
- `ClearMemoryUseCaseTest` imported Mockito (not a dependency) and would have broken compilation of the whole test source set; rewritten with fakes
- `MemInfoParser` accepted `MemTotal: 0`, which then read as CRITICAL pressure
- RAM screen polled while the app was in the background and showed "Reading…" forever when `/proc/meminfo` was unreadable; the RAM screen was not reachable from navigation

### Changed
- TikTok / Twitter-X / Instagram scanners are kept but intentionally NOT registered (no privileged single-file delete exists; `CacheScanner` already covers `cache/`)
- `ClearMemoryResult.processesTouched` renamed `packagesRequested`; added `measured`

### Added
- `ForensicScannersTest`, `ForensicValidatorTest`, extra `MemInfoParserTest` and `PolicyGuardTest` cases

---

## [1.0.0] — Part 5: UI/UX Polish & CI/CD *(AUDIT: Part 5 has not been done; entry kept as written, see notes inline)*

### Added
- GitHub Actions CI pipeline: unit tests, lint, debug + release APK builds
- Gradle wrapper binary committed and validated in CI
- Material 3 dynamic color on Android 12+, static scheme on Android 11
- RAM gauge integrated into Home screen as an expandable card *(AUDIT: not present in the tree; the RAM screen is now a "Memory" tab, see 0.5.1)*
- `LazyColumn` keys changed from path-only to category+path (fixes crash on duplicate paths)
- Final Compose UI polish: animations, transitions, empty states

### Changed
- `PolicyGuardTest`: lint gate now hard-fails on `abortOnError` with `-PstrictLint`
- NavHost: RAM screen accessible via Home card tap or direct nav route

### Fixed
- Gradle wrapper missing binary (CI now generates and validates it)
- Review screen crash when a path is flagged by two scanners simultaneously

---

## [0.5.0] — Part 4: RAM Monitoring

### Added
- `core/memory/RamSnapshot.kt` — honest memory reading with `MemoryPressure` levels
- `core/memory/MemInfoParser.kt` — pure `/proc/meminfo` parser (no I/O, fully testable)
- `core/memory/MemoryInfoProvider.kt` — `ProcMemInfoProvider` reads `/proc/meminfo`
- `core/memory/ClearMemoryResult.kt` — measured freed bytes, never estimated
- `domain/usecase/ObserveRamUseCase.kt` — periodic `Flow<RamSnapshot>`
- `domain/usecase/ClearMemoryUseCase.kt` — honest `killBackgroundProcesses` with measured delta
- `ui/RamViewModel.kt` — StateFlow-based UI state, start/stop observation
- `ui/screens/RamScreen.kt` — circular gauge, pressure colors, honest explainer
- `KILL_BACKGROUND_PROCESSES` permission (documented, deliberate, not creep)
- `MemInfoParserTest.kt` — 8 tests covering parsing, fallbacks, pressure, corruption
- `ClearMemoryUseCaseTest.kt` — 4 tests covering honest measurement, UNKNOWN state, exceptions

### Changed
- `PolicyGuardTest.kt`: permission assertion updated from 2 → 3 permissions
- `di/AppModule.kt`: added `ActivityManager` and `MemoryInfoProvider` bindings

### Design Decisions
- No "boost" claims. UI explicitly states Android manages RAM intentionally.
- `freedBytes` is measured from `MemAvailable` delta, clamped to ≥ 0.
- Packages in `InstallState.UNKNOWN` are never killed (fail-closed).

---

## [0.4.0] — Part 3: Storage Forensics

### Added
- `CleaningCategory.FORENSIC_MEDIA` — app-specific media & caches, `isQuick = false`
- `core/scanner/WhatsAppScanner.kt` — `.Statuses` (>24h), `Sent` folders, old DB backups (>7d)
- `core/scanner/TelegramScanner.kt` — Video/Audio/Images/Documents cache (>14d)
- `core/scanner/TikTokScanner.kt` — video + `.music` cache (>7d)
- `core/scanner/TwitterXScanner.kt` — media cache (>7d)
- `core/scanner/InstagramScanner.kt` — media cache (>14d)
- `SafetyValidator.isKnownForensicPath()` — strict allowlist for forensic paths
- `ProtectedApps.MESSAGING` set — messaging apps never pre-selected
- `docs/FORENSICS_MATRIX.md` — full forensic scanner documentation

### Changed
- `SafetyValidator.kt`: `FORENSIC_MEDIA` allowed in `Android/media` and `Android/data` via allowlist
- `SafetyValidator.kt`: personal folder protection extended to block forensic rules
- `ScanItem.kt`: `reasonForFlagging` field for evidence-based UI display
- `Enums.kt`: `FORENSIC_MEDIA` category added

### Security
- Forensic paths are allowlisted per-package, per-subfolder. No wildcard `Android/data` access.
- All forensic items: `isPreselected = false`, `requiresShizuku = true`, `DeleteMode.FILE`
- WhatsApp/Telegram/Instagram in `ProtectedApps.MESSAGING` — extra caution layer

---

## [0.3.0] — Part 2: Shizuku Engine

### Added
- `core/shizuku/ShizukuFileServiceConnector.kt` — bind/unbind/death/reconnect lifecycle
- `core/shizuku/ShizukuFileService.kt` — AIDL user-service implementation
- `core/shizuku/AidlPrivilegedFileApi.kt` — typed adapter over raw AIDL
- `core/privileged/FileServiceCore.kt` — privileged-side file operations
- `core/privileged/WireCodec.kt` — serialization for binder transport
- `core/privileged/PrivilegedExecutor.kt` — centralized execution, accepts `MaintenanceRequest` not raw paths
- `core/backend/ShizukuFileBackend.kt` — read-only Shizuku backend (no delete)
- `core/backend/Capabilities.kt` — runtime capability detection
- `core/backend/PackageInventory.kt` — tri-state `installState()` (INSTALLED / NOT_INSTALLED / UNKNOWN)
- `core/shizuku/ShizukuManager.kt` — Shizuku lifecycle state holder
- `app/src/main/aidl/com/cleanforge/shizuku/IFileService.aidl` — versioned AIDL contract
- `docs/SHIZUKU_CAPABILITY_MATRIX.md` — operation support matrix
- 25 new Shizuku-specific tests

### Changed
- `CacheScanner`: uses Shizuku backend for `Android/data/<pkg>/cache`
- `CorpseScanner`: uses Shizuku backend for `Android/data` and `Android/obb` leftovers
- `CleaningEngine`: re-validates Shizuku availability before every privileged deletion
- `CleanViewModel`: Shizuku state exposed via `StateFlow`, actionable error states in UI
- `PolicyGuardTest`: 4 new guards for privileged surface (no shell, no generic delete, AIDL versioning, no direct Shizuku API in UI)

### Security
- Shizuku service runs as user-service, NOT root
- AIDL contract uses fixed transaction IDs (only grows, never reassigns)
- `setProtectedPackages` prevents privileged deletion of messaging app data
- Service death → operation halts → auto-rebind → user informed

---

## [0.2.1] — Security Re-Audit

### Fixed
- H1: `MATCH_UNINSTALLED_PACKAGES` no longer used as proof of "installed"
- M1: Confirmation dialog shows full evidence for every item
- M2: Duplicate keeper chosen by user, not by age
- M3: Child-level TOCTOU narrowed (lstat re-check before every remove)
- M4: Multi-user wording corrected (current user/profile only)

---

## [0.2.0] — Part 1: Audit & Refactor

### Added
- Complete project restructure to standard Gradle layout
- `SafeDeleter` — bounded, symlink-safe, mount-aware deletion
- `SafetyValidator` — lexical + policy gate for every deletion
- `PlanBuilder` — refusal tracking with human-readable reasons
- `CleaningEngine` — revalidation at execution time
- 9 evidence-based scanners
- Material 3 UI with 3-tab navigation
- 66 JUnit tests across 6 suites
- `PROJECT_AUDIT.md`, `BUILD.md`, `SECURITY.md`

### Removed
- Hidden API usage (`IPackageManager`, `IPackageDataObserver`, `AppGlobals`)
- Untrusted Maven repository (`api.xposed.info`)
- Unused dependencies (Room, WorkManager, DataStore)
- Broken navigation references to non-existent screens

### Fixed
- 10 build blockers (B1–B10)
- 9 safety/data-loss findings (S1–S9)

---

## [0.1.0] — Original Scaffold

- AI-generated project scaffold (pre-audit)
- 35 source files, 2 test classes
- Multiple critical issues (see PROJECT_AUDIT.md)