# Changelog

Format: [Keep a Changelog](https://keepachangelog.com). Versioning: [SemVer](https://semver.org).

## [0.2.1] - 2026-10-08 - Final independent audit fixes

> **Still not compiled or run.** Patched in an environment with no Android SDK, Gradle, Kotlin compiler or network.
> See `AUDIT_RESPONSE.md` for the finding-by-finding status and `BUILD.md` for the commands that must pass.

### Security
- **H1** `PackageInventory.isCurrentlyInstalled()` added, separate from `knownPackageNames()` (which includes apps uninstalled with data kept). `CacheScanner` and `CleaningEngine` now require a currently installed package for `APP_CACHE`; `CorpseScanner` and the engine also refuse to treat an installed package as a leftover.
- **M3** `SafeDeleter` re-`lstat`s every entry (and the root) immediately before removing it and leaves it untouched if its type/device/inode changed.
- **M2** Duplicates: the scanner lists every copy and no longer picks a keeper. `PlanBuilder` pins a copy the user did NOT tick as the keeper, refuses groups whose every copy is ticked (also via another category), and prefers the duplicate form when one path is flagged twice. The engine refuses a keeper identical to the file.

### Changed
- **M1** The confirmation dialog lists every item in full: path, why flagged, confidence, risk, exact action, owning app, `KEEP` / `DELETE CANDIDATE` for duplicates; refused items and the final report show full paths. Review rows show the full path.
- **M4** Wording: package checks are documented as covering the current Android user/profile only.
- **L1** Lint is a hard gate on demand (`-PstrictLint`); CI runs it that way (non-blocking until a clean baseline exists).
- **L2** `MIN_PLAUSIBLE_PACKAGES` is documented as a fail-closed guard, not proof the query succeeded.
- **H2** CI runs `./gradlew clean :app:testDebugUnitTest`, strict lint, `assembleDebug`, `assembleRelease`; validates a committed wrapper; if none is committed it builds with a generated one, uploads it as an artifact and fails until it is committed.
- Version 0.2.1 (code 3).

### Fixed
- Crash risk: the results list used the file path as a Compose key, which is not unique when two scanners flag the same path (e.g. large file + duplicate). Keys are now category + path.

### Tests
- 91 cases (was 66). New: data-kept-uninstall regression (scanner and deletion layer), unknown-package and reinstall cases, oldest-copy-may-be-deleted, all-copies-refused, cross-category duplicate guard, three child-race tests, confirmation-evidence tests, policy guards (no bare names in the dialog, installed vs known, no `deleteRecursively`).

## [0.2.0] - 2026-10-08 - Audit, fix and hardening pass

> **Not yet compiled or tested.** The audit environment had no Android SDK, no Gradle and no network.
> See `PROJECT_AUDIT.md` section 0 and `BUILD.md`.

### Security
- Added `SafeDeleter`: symlink-safe, mount-boundary-safe, depth/entry-bounded, identity-checked deletion, used by both the app and the Shizuku service.
- Fixed protected-folder bypass: `/sdcard/...` and `/storage/self/primary/...` paths skipped the DCIM/Pictures/... checks. Only the canonical `/storage/emulated/0` form is accepted now.
- Path policy is component-based and case-insensitive (shared storage is case-insensitive): rejects `..`, `.`, empty segments, trailing slash, NUL/control characters, over-long paths.
- Recursive deletion exists only for three exact shapes: `Android/data/<pkg>/cache` (contents only), `Android/{data,obb}/<pkg>` (uninstalled app), `DCIM/.thumbnails` (contents only).
- `Android/media`, `Android/data` and `Android/obb` themselves can never be deleted.
- Every item is re-validated immediately before deletion (policy, live capabilities, fresh package check, scan-time fingerprint, kept-copy check for duplicates), then verified afterwards.
- The Shizuku service re-validates every path itself and refuses anything but the two recursive shapes.

### Added
- Real Shizuku user service (AIDL) with lifecycle handling (bind on demand, process death, rebind, timeout, unavailable).
- Confirmation gate: the engine only accepts a `ConfirmedPlan`; the UI shows what, why, how much, risk, evidence, Shizuku need, and refused items with reasons.
- Material 3 UI: dynamic colour on Android 12+, hand-tuned green scheme below that; large top bars, navigation bar, storage hero card, setup cards, extended FAB, confirm/progress/report dialogs.
- About screen crediting Aman as author and owner.
- Tests: 66 cases covering traversal, aliases, symlinks, mount boundaries, races, unicode, long paths, scanner evidence, engine revalidation, plus guards for shell usage, permissions and analytics.
- GitHub Actions workflow for an independent build, test and lint run.

### Changed
- Scanners now require contextual evidence (see `PROJECT_AUDIT.md` section 6). Only well-evidenced, non-messaging app caches are pre-selected.
- Heavy scans (duplicates, large files, logs, empty folders) are on-demand; the default scan is light.
- Walks are bounded and never follow symlinks; duplicates hash in stages (size, 64 KB prefix, full) with cancellation.
- Permissions reduced to `MANAGE_EXTERNAL_STORAGE` and `QUERY_ALL_PACKAGES`.
- Project moved into a standard Gradle layout.

### Removed
- Hidden-API package-manager backends (could not compile against the public SDK) and the untrusted `api.xposed.info` Maven repository.
- Room, WorkManager and DataStore dependencies (unused).
- `READ_EXTERNAL_STORAGE`, `PACKAGE_USAGE_STATS`, `REQUEST_DELETE_PACKAGES` permissions (unused).
- Navigation entries for five screens that never existed (Apps, Large files, Duplicates, Analyzer, Settings). Large files and Duplicates now live in Review.

## [0.1.0] - initial AI-generated scaffold
- Could not build (see `PROJECT_AUDIT.md` section 2).
