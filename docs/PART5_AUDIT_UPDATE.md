# CleanForge — Part 5 Integration & Security Audit Update

Status: **In progress — not release-ready yet**  
Scope: **Whole-project integration, security audit, feature verification, build/release preparation**  
Audit method: **Static code review and architecture inspection only**  
Important: **No Gradle build, unit test, emulator test, or physical-device test has been executed as part of this audit.**

---

## 1. Purpose of this document

This document records the current state of the CleanForge Part 5 audit.

It covers:

- what has been audited so far,
- what was found,
- what has been fixed or must be fixed,
- what remains unsupported or unresolved,
- what must be verified before release.

This is intended to be a living repository document until the final release verdict is produced.

---

## 2. High-level status

| Area | Status |
|---|---|
| Gradle wrapper and build structure | Audited, structurally sound |
| Manifest and permissions | Audited, strong privacy posture |
| Shizuku integration | Audited, strong trust-boundary design |
| Storage forensics core | Audited, strong safety model |
| RAM diagnostics | Audited, honest measurement model |
| Dependency injection | Issues found, fixes defined |
| Cleanup execution engine | Not fully audited yet |
| UI / ViewModel wiring | Not fully audited yet |
| Automated test execution | Not executed yet |
| Documentation reconciliation | Pending |
| Release readiness | **Not ready until Phase 5–7 are completed** |

---

## 3. Audit environment limitations

The following limitations apply to this audit:

1. The audit was performed by reviewing provided source excerpts and repository structure.
2. No Gradle build was executed in this environment.
3. No unit tests were executed in this environment.
4. No emulator or physical-device verification was performed.
5. No Shizuku authorization flow was exercised on a device.
6. No destructive filesystem operation was performed during the audit.

Any statement in this document saying a feature is “verified” means:

> verified by code inspection and architectural review, not by executed runtime testing.

---

# Phase 1 — Build System, Manifest, and Project Structure

## 4.1 Project structure

The repository uses a clean and maintainable architecture:

```text
core/backend
core/cleaning
core/fs
core/memory
core/model
core/privileged
core/safety
core/scanner
core/shizuku
data/repository
domain/usecase
di
ui
```

This separation is appropriate and supports:

- testability,
- security boundaries,
- future maintenance,
- clear dependency direction.

No obvious circular dependency was identified in the audited layers.

---

## 4.2 Gradle wrapper

Verified from repository contents:

- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`

Wrapper configuration:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
```

Verdict:

- Wrapper files appear present.
- Wrapper version is modern and appropriate.
- No wrapper repair is currently required.

---

## 4.3 Android build configuration

Key settings:

```kotlin
compileSdk = 35
minSdk = 30
targetSdk = 35
```

Important implications:

- CleanForge targets Android 11+ only.
- This is appropriate for the storage model used.
- `MANAGE_EXTERNAL_STORAGE` is required for broad shared-storage access.
- `Android/data` and `Android/obb` require Shizuku-backed access.

Release signing:

- Signing configuration reads from optional `keystore.properties`.
- This is the correct secret-handling approach.
- No signing credentials were found committed in the reviewed build files.

Application ID:

```kotlin
applicationId = "com.cleanforge"
```

Recommendation:

- Before public release, choose a unique reverse-domain application ID, for example:

```text
io.github.lightibulb.cleanforge
```

Changing the application ID later will break updates.

---

## 4.4 Manifest audit

Important manifest properties:

```xml
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
<uses-permission android:name="android.permission.KILL_BACKGROUND_PROCESSES" />
```

Strong privacy property:

```text
The app declares NO INTERNET permission.
```

This is a major privacy advantage. It prevents telemetry, remote code fetching, and network-based data leakage.

Other observations:

- `android:allowBackup="false"` is good for privacy and forensic hygiene.
- Shizuku provider declaration matches the expected Shizuku API integration pattern.
- `QUERY_ALL_PACKAGES` is justified for package-based cleanup correctness.
- `KILL_BACKGROUND_PROCESSES` is justified for the honest RAM trim feature.

Verdict:

- Manifest permissions are narrowly scoped and purpose-specific.
- No excessive permission was identified.

---

# Phase 2 — Shizuku Integration and Trust Boundary

## 5.1 Shizuku architecture

CleanForge separates:

1. The ordinary Android app process.
2. The privileged Shizuku-started service process.

The privileged service is the only place where privileged filesystem work happens.

Key classes:

```text
ShizukuManager
ShizukuFileServiceConnector
ShizukuFileService
FileServiceCore
PrivilegedExecutor
ShizukuFileBackend
```

---

## 5.2 Security model

The Shizuku integration follows a zero-trust model:

- The privileged service does not blindly trust the app process.
- Every path is revalidated.
- No shell execution is used.
- No command-line construction is used.
- No arbitrary path deletion is exposed.
- Deletion shapes are intentionally restricted.

---

## 5.3 Important security controls

### Path validation

`PathRules` enforces:

- canonical root containment,
- rejection of relative segments,
- rejection of `..`,
- rejection of empty path segments,
- rejection of trailing slash,
- rejection of over-long paths,
- rejection of control characters.

### Symlink safety

The privileged service verifies that every ancestor below the storage root is a real directory.

This prevents symlink-based escape from approved zones.

### Binder transaction safety

Listing results are capped:

```kotlin
const val MAX_NAMES = 20_000
const val MAX_LIST_CHARS = 120_000
```

This protects against `TransactionTooLargeException`.

### Deletion safety

The privileged service currently supports only two recursive deletion shapes:

1. `DeleteMode.TREE` for:

```text
Android/data/<pkg>
Android/obb/<pkg>
```

2. `DeleteMode.CONTENTS_ONLY` for:

```text
Android/data/<pkg>/cache
```

This is intentionally restrictive.

### Installed-apps guard

For tree deletions, the app must push an installed-apps guard.

A tree deletion is refused if:

- the guard is missing,
- the target package is known to Android,
- the target package is CleanForge itself.

This protects against accidental deletion of installed app data.

---

## 5.4 Shizuku lifecycle handling

Strong points:

- Fresh `ServiceConnection` per bind attempt.
- Mutex-guarded binding.
- Timeouts for bind operations.
- API version validation.
- Invalidation on service death.
- Explicit unavailable reasons.

This avoids several common Shizuku integration bugs:

- stale binder references,
- late callbacks from abandoned binds,
- silent service death,
- permission-state confusion.

---

## 5.5 Honest failure handling

`PrivilegedExecutor` does not silently treat uncertain deletions as successful.

If a deletion call dies or times out, the executor re-observes the target and returns one of:

- `Success` only when post-state confirms removal,
- `Unverified` when outcome cannot be confirmed,
- `Failure` when failure is known,
- `Unsupported` when capability is unavailable.

This satisfies the product rule:

> A failed cleanup must not report the requested space reclaimed.

---

# Phase 3 — Storage Forensics, Traversal, and Deletion Safety

## 6.1 Filesystem operations

Core filesystem access is based on `FsOps`.

The real implementation uses:

```kotlin
android.system.Os.lstat
android.system.Os.remove
```

Important properties:

- symlinks are not followed,
- `lstat` semantics are used,
- deletion uses unlink/remove semantics,
- no shell is used.

---

## 6.2 StorageWalker

`StorageWalker` is bounded and safe:

- uses explicit stack traversal,
- does not recurse,
- checks mount boundaries via `st_dev`,
- enforces `maxDepth`,
- enforces `maxEntries`,
- filters unsafe names using `isPlainName`,
- reports unreadable directories,
- reports truncation.

Important safety method:

```kotlin
fun isPlainName(name: String): Boolean =
    name.isNotEmpty() &&
    name != "." &&
    name != ".." &&
    name.none { it == '/' || it == '\u0000' }
```

This prevents path escape through malicious directory-entry names.

---

## 6.3 Directory size calculation

`StorageWalker.sizeOf` returns:

```kotlin
SizeResult(bytes, complete, unreadable)
```

Important honesty property:

- If traversal is truncated, `complete = false`.
- If unreadable directories exist, `unreadable > 0`.
- In either case, the size is a lower bound, not an exact figure.

This supports the rule:

> A failed or partial scan must not become a successful empty scan.

---

## 6.4 SafeDeleter

`SafeDeleter` is the single deletion implementation.

It enforces:

- symlink roots are refused,
- symlink children are unlinked as links only,
- recursion depth is bounded,
- entry count is bounded,
- mount boundaries are not crossed,
- identity is rechecked before deletion,
- cancellation is checked between entries,
- no shell is used.

It also documents a realistic residual TOCTOU risk:

- re-check and remove are separate path-based calls,
- Android/Kotlin does not currently expose `unlinkat`/`openat` conveniently,
- a hostile local writer with access to the same directory could theoretically race the deletion,
- but a symlink-following delete is still prevented.

This is an acceptable documented residual risk for the current threat model.

---

## 6.5 Scanner architecture

Scanners are evidence-based classifiers.

Important design rule:

> A discovered candidate must not automatically become a deletion target.

Scanners emit `ScanItem` proposals with:

- path,
- size,
- evidence,
- confidence,
- risk,
- category,
- fingerprint,
- deletion mode,
- reversibility.

The user must still confirm a plan before deletion.

---

## 6.6 Duplicate scanner

The duplicate scanner uses staged hashing:

1. group by exact size,
2. SHA-256 of first 64 KB,
3. full SHA-256 only for survivors.

Important correctness properties:

- hard links to the same inode are not treated as duplicates,
- cancellation checkpoints exist,
- batches are emitted to bound UI memory,
- the scanner does not choose which copy the user values.

Known resource concern:

- The intermediate size/hash maps can grow large on devices with many large files.
- This is a medium-severity resource issue, not a correctness bug.

Recommendation:

- consider explicit caps,
- consider pruning singleton groups earlier,
- consider reporting truncation if candidate sets become too large.

---

## 6.7 Corpse scanner

The corpse scanner uses strong evidence:

- valid package-name folder,
- package unknown to the OS,
- not merely “uninstalled with data kept”,
- real directory,
- minimum age threshold,
- size measurement,
- elevated risk,
- never preselected.

This is a strong forensic model.

---

# Phase 4 — RAM Diagnostics, Use Cases, Repository, and DI

## 7.1 RAM diagnostics model

CleanForge’s RAM diagnostics are honest by design.

`RamSnapshot` is based on available memory, not naive “used memory”.

Memory pressure classification:

```text
<= 5% available  -> CRITICAL
<= 12% available -> HIGH
<= 30% available -> NORMAL
otherwise        -> LOW
```

Important product rule:

> CleanForge must not falsely claim that clearing RAM improves performance.

The current core model respects this rule.

---

## 7.2 Memory measurement

`MemInfoProvider` reads `/proc/meminfo`.

Properties:

- no special permission required,
- no shell,
- no network,
- returns `null` when unavailable,
- never invents zero as a real measurement.

`MemInfoParser` is pure and testable.

---

## 7.3 Honest RAM clear

The RAM clear feature measures the delta in available memory.

It must not estimate freed memory.

Correct behavior:

- If available memory rises, report the measured delta.
- If available memory does not rise, report zero freed.
- If memory info is unavailable, say so.

---

# Phase 4 Findings

The following findings were identified during Phase 4.

| ID | Severity | Finding | Status |
|---|---:|---|---|
| P4-01 | High | `ClearMemoryUseCase` bypassed `BackgroundProcessKiller` | Fix defined |
| P4-02 | High | Forensic/media scanners missing from Hilt DI | Fix defined |
| P4-03 | High | `AppModule.kt` missing imports | Fix defined |
| P4-04 | Medium | `ScanRepository` lacked single-flight scan guard | Fix defined |
| P4-05 | Medium | Some Shizuku scanners omitted `privilegedOperations` | Partially addressed |
| P4-06 | Low | `ClearMemoryUseCase` lacked cancellation checkpoints | Fixed in rewrite |
| P4-07 | Low | `processesTouched` semantics were misleading | Fixed in rewrite |
| P4-08 | Low | `ObserveRamUseCase` silent provider failure | Documented, not release-blocking |
| P4-09 | Critical | `SafetyValidator` refused all forensic media inside `Android/media` | Fix defined |
| P4-10 | Critical | WhatsApp/Telegram scanners incorrectly targeted Shizuku backend for `Android/media` | Fix defined |
| P4-11 | High | `ClearMemoryUseCaseTest` used Mockito and old constructor | Fix defined |
| P4-12 | High | Instagram/TikTok/Twitter scanners cannot be safely wired under current deletion model | Mitigated by keeping them unwired |
| P4-13 | Medium | Instagram/TikTok/Twitter scanners lacked `privilegedOperations` | Metadata fix defined |
| P4-14 | Low/Medium | `ClearMemoryUseCase` excerpt appeared inside `BackgroundProcessKiller.kt` | File separation fix defined |

---

# Detailed Phase 4 Fixes

## 8.1 P4-01 — `ClearMemoryUseCase` must use `BackgroundProcessKiller`

### Problem

The original use case called:

```kotlin
activityManager.killBackgroundProcesses(pkg)
```

directly.

This bypassed the intended safety seam:

```kotlin
BackgroundProcessKiller
```

That class is responsible for skipping:

- CleanForge itself,
- system apps,
- unknown app types.

### Required fix

`ClearMemoryUseCase` must depend on `BackgroundProcessKiller`.

The corrected constructor is:

```kotlin
class ClearMemoryUseCase @Inject constructor(
    private val killer: BackgroundProcessKiller,
    private val inventory: PackageInventory,
    private val provider: MemoryInfoProvider
)
```

The corrected loop is:

```kotlin
for (pkg in installed) {
    if (count++ % 64 == 0) {
        currentCoroutineContext().ensureActive()
    }

    if (killer.kill(pkg)) {
        requested++
    }
}
```

### Result

- System apps are skipped.
- CleanForge itself is skipped.
- Unknown apps are skipped fail-closed.
- `processesTouched` now counts actual requests sent, not every package considered.

---

## 8.2 P4-02 — Forensic scanners missing from DI

### Problem

The project tree contained forensic scanners, but `AppModule.provideScanners()` did not include them.

That meant the scanners existed in source but would never run.

### Fix decision

Only scanners that can be safely executed with the current architecture should be wired.

Therefore:

- WhatsApp and Telegram scanners are wired.
- Instagram, TikTok, and Twitter/X scanners remain unwired for now.

Reason:

- WhatsApp/Telegram scan `Android/media/...`.
- `Android/media` is accessible via `MANAGE_EXTERNAL_STORAGE`.
- Instagram/TikTok/Twitter scan `Android/data/...`.
- They emit `DeleteMode.FILE` items.
- The privileged service currently does not support scoped `DeleteMode.FILE` deletion under `Android/data`.

Wiring them now would create scan items that cannot be safely cleaned.

---

## 8.3 P4-03 — Missing imports in `AppModule.kt`

### Problem

The provided `AppModule.kt` used:

```kotlin
ActivityManager
MemoryInfoProvider
ProcMemInfoProvider
BackgroundProcessKiller
AndroidBackgroundProcessKiller
PathRules
WhatsAppScanner
TelegramScanner
```

but some required imports were missing.

### Required fix

The rewritten `AppModule.kt` must include all required imports.

Most important additions:

```kotlin
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.memory.AndroidBackgroundProcessKiller
import com.cleanforge.core.memory.BackgroundProcessKiller
import com.cleanforge.core.memory.MemoryInfoProvider
import com.cleanforge.core.memory.ProcMemInfoProvider
import com.cleanforge.core.scanner.TelegramScanner
import com.cleanforge.core.scanner.WhatsAppScanner
```

---

## 8.4 P4-04 — `ScanRepository` single-flight guard

### Problem

`ScanRepository.scan()` mutated shared state without a concurrency guard.

If two scans were started concurrently, results could interleave.

### Required fix

Add a `Mutex`.

```kotlin
private val scanMutex = Mutex()

fun scan(mode: ScanMode): Flow<ScanProgress> = flow {
    scanMutex.withLock {
        // existing scan logic
    }
}
```

Required imports:

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

---

## 8.5 P4-09 — `SafetyValidator` refused all forensic media inside `Android/media`

### Problem

`PathRules.isProtectedZone` treats `Android/media` as protected.

The original SafetyValidator step 5 refused:

```kotlin
CleaningCategory.FORENSIC_MEDIA
```

inside protected zones.

Because all forensic media items live under `Android/media`, every forensic media item was refused.

### Required fix

SafetyValidator step 5 must allow approved forensic media paths inside `Android/media`, while still protecting top-level personal folders such as DCIM, Pictures, Movies, etc.

Corrected logic:

```kotlin
if (PathRules.isProtectedZone(c) && item.category != CleaningCategory.THUMBNAIL) {
    val isAndroidMedia = c.size >= 2 &&
        c[0].equals("Android", ignoreCase = true) &&
        c[1].equals("media", ignoreCase = true)

    if (!isAndroidMedia) {
        if (
            item.category == CleaningCategory.APP_CACHE ||
            item.category == CleaningCategory.CORPSE_LEFTOVER ||
            item.category == CleaningCategory.FORENSIC_MEDIA
        ) {
            refuse("Cache/leftover/forensic rules do not apply inside personal folders")
        }
    }
    if (item.isPreselected) refuse("Personal data can never be pre-selected")
}
```

---

## 8.6 P4-10 — WhatsApp/Telegram scanners used wrong backend model

### Problem

WhatsApp and Telegram scan:

```text
Android/media/...
```

But they originally declared:

```kotlin
override val requirement = Requirement.SHIZUKU
requiresShizuku = true
```

This is incorrect because:

- the Shizuku service read gate only allows `Android/data` and `Android/obb`,
- `Android/media` is accessible via `MANAGE_EXTERNAL_STORAGE`,
- the standard backend is the correct backend for these paths.

### Required fix

For WhatsApp and Telegram scanners:

```kotlin
override val requirement = Requirement.ALL_FILES_ACCESS
```

and all emitted `ScanItem` instances must use:

```kotlin
requiresShizuku = false
```

They must be wired in DI with the standard backend:

```kotlin
WhatsAppScanner(standard, inventory, PathRules.ROOT),
TelegramScanner(standard, inventory, PathRules.ROOT)
```

---

## 8.7 P4-11 — `ClearMemoryUseCaseTest` rewrite

### Problem

The old test:

- used Mockito,
- tested the old `ActivityManager`-based constructor,
- did not match the corrected `BackgroundProcessKiller` architecture,
- may not compile because Mockito is not declared in the visible test dependencies.

### Required fix

Rewrite the test using hand-written test doubles.

The new test should verify:

- measured freed memory,
- negative delta becomes zero,
- skipped packages are not counted,
- unknown or uninstalled packages are not killed,
- unavailable memory info is honestly reported.

The rewritten test avoids Mockito and uses deterministic fakes.

---

## 8.8 P4-12 — Instagram, TikTok, and Twitter/X scanners

### Problem

These scanners target:

```text
Android/data/<pkg>/cache
Android/data/<pkg>/files/.music
```

and emit:

```kotlin
deleteMode = DeleteMode.FILE
requiresShizuku = true
```

But the privileged service currently supports only:

```text
TREE for Android/data/<pkg>
TREE for Android/obb/<pkg>
CONTENTS_ONLY for Android/data/<pkg>/cache
```

It does not support scoped `FILE` deletion under `Android/data`.

### Release-safe decision

Do not wire these scanners yet.

They should remain present in source but inactive until one of the following is done:

1. Add audited privileged support for scoped forensic `FILE` deletion under known `Android/data` paths.
2. Rewrite the scanners to emit supported whole-cache `CONTENTS_ONLY` cleanup items.

Until then, they must not be advertised as active capabilities.

---

## 8.9 P4-13 — Missing `privilegedOperations` metadata

For future correctness, the inactive Instagram/TikTok/Twitter scanners should declare:

```kotlin
override val privilegedOperations = setOf(
    PrivilegedOperation.LIST_EXTERNAL_APP_DATA
)
```

Required import:

```kotlin
import com.cleanforge.core.privileged.PrivilegedOperation
```

This does not make them safe to wire by itself, but it makes future capability discovery correct.

---

## 8.10 P4-14 — File separation

`BackgroundProcessKiller.kt` must contain only:

```kotlin
BackgroundProcessKiller
AndroidBackgroundProcessKiller
```

`ClearMemoryUseCase.kt` must contain:

```kotlin
ClearMemoryUseCase
```

Do not place use-case code inside the killer file.

---

# Files Changed or Requiring Change

The following files have been changed or must be changed as part of this audit.

## Production code

```text
app/src/main/java/com/cleanforge/core/memory/BackgroundProcessKiller.kt
app/src/main/java/com/cleanforge/domain/usecase/ClearMemoryUseCase.kt
app/src/main/java/com/cleanforge/di/AppModule.kt
app/src/main/java/com/cleanforge/data/repository/ScanRepository.kt
app/src/main/java/com/cleanforge/core/safety/SafetyValidator.kt
app/src/main/java/com/cleanforge/core/scanner/WhatsAppScanner.kt
app/src/main/java/com/cleanforge/core/scanner/TelegramScanner.kt
app/src/main/java/com/cleanforge/core/scanner/InstagramScanner.kt
app/src/main/java/com/cleanforge/core/scanner/TikTokScanner.kt
app/src/main/java/com/cleanforge/core/scanner/TwitterXScanner.kt
```

## Test code

```text
app/src/test/java/com/cleanforge/core/ClearMemoryUseCaseTest.kt
```

## Documentation

```text
docs/PART5_AUDIT_UPDATE.md
```

---

# Security Audit Summary

## 9.1 Path traversal

Status: **Mitigated**

Controls:

- canonical root enforcement,
- rejection of relative segments,
- rejection of `..`,
- rejection of over-long paths,
- rejection of control characters,
- ancestor validation in privileged service.

---

## 9.2 Symlink handling

Status: **Mitigated**

Controls:

- `lstat` everywhere,
- symlink roots refused,
- symlink children unlinked as links only,
- ancestor symlink checks in privileged service.

---

## 9.3 Shell injection

Status: **Not applicable / mitigated by design**

No shell execution was found in the audited filesystem or privileged paths.

No command-line construction from paths was found.

---

## 9.4 Binder and service failure handling

Status: **Strong**

Controls:

- timeouts,
- transport exception classification,
- service invalidation,
- service restart on deadline exceeded,
- honest unverified deletion reporting.

---

## 9.5 Authorization checks

Status: **Strong by design**

Controls:

- Shizuku state tracking,
- permission request handling,
- permanent-denial handling,
- capability registry,
- operation gating before deletion.

Device-level verification is still pending.

---

## 9.6 Destructive operation safety

Status: **Strong by design**

Controls:

- scan items are proposals only,
- confirmed plan type is required,
- SafetyValidator runs before deletion,
- SafeDeleter rechecks identity,
- privileged service restricts deletion shapes,
- installed-apps guard protects tree deletions,
- post-deletion verification is used where possible.

---

## 9.7 Secrets and signing material

Status: **No secrets found in reviewed files**

- `keystore.properties` is optional and should remain git-ignored.
- No credentials were observed in the reviewed Gradle files.

---

## 9.8 Network and telemetry

Status: **Strong**

- No INTERNET permission.
- No telemetry dependencies observed.
- No analytics dependencies observed.

---

# Feature Verification Matrix

| Feature | Status | Notes |
|---|---|---|
| Shizuku connection | Verified by inspection | Needs device verification |
| Shizuku authorization states | Verified by inspection | Needs device verification |
| Capability discovery | Mostly verified | Some scanner metadata corrected |
| Storage scanning | Verified by inspection | Bounded walker |
| Directory-size calculation | Verified by inspection | Lower-bound honesty supported |
| Largest-file analysis | Not fully audited | `LargeFileScanner` review pending |
| File-type analysis | Not fully audited | Needs scanner review |
| Application storage statistics | Not fully audited | Depends on backend inventory |
| Duplicate detection | Mostly verified | Memory bounding needs monitoring |
| Orphaned-file candidates | Verified by inspection | Strong evidence model |
| Snapshot creation/comparison | Not verified | Not reviewed yet |
| RAM measurements | Verified by inspection | Honest model |
| RAM history | Not verified | UI/storage review pending |
| Cleanup previews | Not fully verified | Requires PlanBuilder/CleaningEngine review |
| Cleanup execution | Not fully verified | Requires CleaningEngine review |
| Deletion verification | Partially verified | Privileged executor strong; engine pending |
| Cancellation | Mostly verified | Present in walker/hash/privileged executor |
| Partial failure recovery | Partially verified | Privileged executor strong; engine pending |

---

# String Literal Verification

During Phase 3, a possible issue was identified where string literals appeared to contain trailing spaces, for example:

```kotlin
"Android "
"data "
"cache "
```

This was investigated on Windows using PowerShell.

Exact literal checks returned no matches for the dangerous path-critical literals.

The broader regex matches were false positives caused by normal spaces between comma-separated strings, for example:

```kotlin
"dcim", "pictures"
```

Conclusion:

- The actual source files do not appear to contain the dangerous trailing-space literals.
- The earlier appearance was an extraction/formatting artifact.
- This item is considered clean based on the user’s local PowerShell verification.

---

# Verification Commands

The following commands should be run on Windows from the repository root.

## Compile debug code

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

## Run unit tests

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## Run only the rewritten RAM test

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.cleanforge.core.ClearMemoryUseCaseTest"
```

## Run lint without failing by default

```powershell
.\gradlew.bat :app:lintDebug
```

## Run strict lint if configured

```powershell
.\gradlew.bat -PstrictLint :app:lintDebug
```

These commands have not been executed as part of this audit.

---

# Unresolved Issues and Unsupported Capabilities

The following items remain unresolved or unverified.

## U-01 — Instagram/TikTok/Twitter forensic FILE deletion unsupported

Severity: **High**

Affected files:

```text
InstagramScanner.kt
TikTokScanner.kt
TwitterXScanner.kt
FileServiceCore.kt
PathRules.kt
PrivilegedExecutor.kt
AppModule.kt
```

Evidence:

- Scanners emit `DeleteMode.FILE` items under `Android/data/...`.
- `PathRules.isServiceDeletable` does not allow `DeleteMode.FILE`.
- Therefore cleanup cannot currently be executed safely.

Reproduction:

1. Wire one of these scanners into DI.
2. Run a scan.
3. Attempt cleanup of emitted forensic file items.
4. Deletion will be refused or unsupported by the privileged service.

Recommended fix:

- Keep scanners unwired for now, or
- implement scoped privileged `FILE` deletion with strict allowlists, identity checks, and tests.

---

## U-02 — CleaningEngine not audited

Severity: **High**

Affected file:

```text
app/src/main/java/com/cleanforge/core/cleaning/CleaningEngine.kt
```

Evidence:

- Cleanup execution has not yet been fully reviewed.
- Deletion routing, post-verification, and report generation need inspection.

Recommended fix:

- Audit `CleaningEngine.kt` and `PlanBuilder.kt` in Phase 5.

---

## U-03 — PlanBuilder not audited

Severity: **High**

Affected file:

```text
app/src/main/java/com/cleanforge/core/cleaning/PlanBuilder.kt
```

Evidence:

- Plan construction, duplicate keeper logic, and revalidation rules have not been fully reviewed.

Recommended fix:

- Audit in Phase 5.

---

## U-04 — UI and ViewModel truthfulness not audited

Severity: **High**

Affected files:

```text
ui/CleanViewModel.kt
ui/RamViewModel.kt
ui/screens/CleanScreen.kt
ui/screens/RamScreen.kt
ui/screens/ReviewScreen.kt
ui/components/*
```

Evidence:

- UI wiring has not yet been reviewed.
- Success messages must reflect actual outcomes.

Recommended fix:

- Audit UI layer in Phase 5/6.
- Ensure failed cleanup does not display reclaimed space.
- Ensure unverified deletions are labeled unverified.

---

## U-05 — Build and tests not executed

Severity: **Medium**

Affected repository:

```text
entire project
```

Evidence:

- No Gradle build or test execution was performed during this audit.

Recommended fix:

- Run the verification commands.
- Record actual outcomes in a build report.

---

## U-06 — `PackageInventory` not reviewed

Severity: **Medium**

Affected file:

```text
app/src/main/java/com/cleanforge/core/backend/PackageInventory.kt
```

Evidence:

- Corpse scanner correctness depends on accurate package inventory behavior.
- The interface implementation has not been fully reviewed.

Recommended fix:

- Audit `PackageInventory.kt` and `AndroidPackageInventory.kt` in Phase 5.

---

## U-07 — Duplicate scanner memory pressure

Severity: **Medium**

Affected file:

```text
app/src/main/java/com/cleanforge/core/scanner/DuplicateScanner.kt
```

Evidence:

- Size-group and hash-group maps may grow large before pruning.

Recommended fix:

- Add explicit caps,
- prune singleton groups earlier,
- report truncation if candidate set becomes too large.

---

## U-08 — RAM observer silent failure

Severity: **Low**

Affected file:

```text
app/src/main/java/com/cleanforge/domain/usecase/ObserveRamUseCase.kt
```

Evidence:

- If `provider.snapshot()` repeatedly returns null, the flow emits nothing.

Recommended fix:

- Consider exposing an error or unavailable state in UI.

---

## U-09 — Application ID placeholder

Severity: **Low**

Affected file:

```text
app/build.gradle.kts
```

Evidence:

```kotlin
applicationId = "com.cleanforge"
```

Recommended fix:

- Choose a unique reverse-domain ID before public release.

---

## U-10 — Open-source metadata not fully reviewed

Severity: **Medium**

Missing or unreviewed items may include:

```text
LICENSE
CONTRIBUTING.md
CODE_OF_CONDUCT.md
ISSUE_TEMPLATE
PULL_REQUEST_TEMPLATE
```

Recommended fix:

- Review license consistency before release.
- Add contribution guidance if absent.
- Add issue templates.

---

# Current Release-Readiness Verdict

## Verdict: **NOT READY**

CleanForge has a strong architectural and security foundation, but it is not release-ready yet.

The most important remaining work is:

1. Audit and verify `CleaningEngine`.
2. Audit and verify `PlanBuilder`.
3. Audit and verify UI truthfulness.
4. Execute build and unit tests.
5. Reconcile documentation with actual capabilities.
6. Decide the final fate of Instagram/TikTok/Twitter forensic scanners.
7. Complete open-source metadata review.

Once those are complete, the likely verdict will be either:

```text
READY WITH KNOWN LIMITATIONS
```

or

```text
NOT READY
```

depending on the results of Phase 5 and Phase 6.

At this moment, the honest verdict is:

```text
NOT READY — integration and verification still in progress
```

---

# Next Audit Phase

The next phase should cover:

```text
Phase 5A — Cleaning Engine, Plan Builder, Backend Models, Capability Registry
Phase 5B — UI, ViewModels, Navigation, Confirmation Evidence
Phase 6  — Test Strategy, Documentation, Open-Source Quality
Phase 7  — Final Regression, Resource Check, Release Verdict
```

Required files for Phase 5A include:

```text
core/cleaning/CleaningEngine.kt
core/cleaning/PlanBuilder.kt
core/backend/FileOperationBackend.kt
core/backend/FsResult.kt
core/backend/StandardFileBackend.kt
core/backend/PackageInventory.kt
core/backend/Capabilities.kt
core/privileged/MaintenanceModel.kt
core/privileged/CapabilityModel.kt
core/privileged/CapabilityRegistry.kt
core/privileged/PrivilegedOperation.kt
core/privileged/PrivilegedFileApi.kt
core/shizuku/AidlPrivilegedFileApi.kt
```

---

# Statement of Audit Integrity

This document does not claim:

- successful builds,
- passed tests,
- device verification,
- completed release validation,
- universal Android compatibility,
- or completion of unfinished functionality.

All “verified” statements in this document refer to static inspection only unless explicitly stated otherwise.

No destructive changes were made to user data during this audit.

No test results were invented.
