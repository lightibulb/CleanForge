# CleanForge Architecture Plan (proposal, Part 1)

Status: proposal only. Nothing here has been implemented, compiled or tested. Finding IDs (F-xx) and experiment IDs (E-xx) refer to `docs/CAPABILITY_AUDIT.md`.

## 1. Principles

1. **Additive, not a rewrite.** The deletion core, safety policy and confirmation gate are the best part of the repo. They stay as they are (list in audit section 5).
2. **Say only what was proven.** Every capability shown to the user comes from a probe or an operation result, never from an inference such as "the binder is alive".
3. **Shizuku stays the privileged channel and stays narrow.** The privileged process exposes a small, fixed AIDL surface. It never receives a command line. Policy is enforced on both sides of the binder.
4. **Read-only features must not weaken destructive invariants.** New analyzers reuse `FsOps`/`StorageWalker` but cannot reach `SafeDeleter` or `CleaningEngine` except through `ConfirmedPlan`.
5. **Bounded and cheap on a low-end phone.** One traversal, aggregates instead of per-file rows, hashing only on demand.
6. **Keep a single Gradle module** until a second module has a concrete reason (for example a separate service APK). Package boundaries are enough today.

## 2. Target shape (additions marked NEW)

```
ui/                      screens, dialogs               (Clean, Review, About; NEW: Analyze, Diagnostics)
domain/usecase/          Scan, Execute                  (NEW: Probe, BuildInventory, CompareSnapshots)
data/repository/         ScanRepository                 (NEW: InventoryRepository, SnapshotStore)
core/
  capability/   NEW      CapabilityProbe, CapabilityReport
  backend/               FileOperationBackend           (CHANGED: typed results, section 4)
                         PackageInventory, Capabilities
  fs/                    FsOps, SafeDeleter, PathRules, StorageWalker   (unchanged core)
  inventory/    NEW      StorageIndexer, DirNode, TypeClassifier
  scanner/               existing scanners              (CHANGED: consume inventory + typed results)
  safety/, cleaning/     SafetyValidator, PlanBuilder, CleaningEngine   (message and journal changes only)
  shizuku/               Manager, Connector, Service    (CHANGED: probe hook, new read-only AIDL methods)
  diagnostics/  NEW      MemoryDiagnostics (read-only), DiagnosticsReport
  journal/      NEW      DeletionJournal
```

## 3. Capability model (fixes F-03)

`CapabilityProbe` runs read-only checks and returns a `CapabilityReport`. Each capability has a tri-state and a human reason.

| Capability | Check | States |
| --- | --- | --- |
| All-files access | `Environment.isExternalStorageManager()` | Granted / Missing |
| Shizuku binder | `pingBinder`, `isPreV11` | Running / NotRunning / NotInstalled / TooOld |
| Shizuku permission | `checkSelfPermission`, plus `shouldShowRequestPermissionRationale` | Granted / Denied / DeniedPermanently |
| Service bind | bind, `uid()`, round-trip under the timeout | Verified (uid 2000 or 0) / Failed(reason) |
| Android/data readable | service `list(Android/data)` returns non-null | Verified / Unavailable / Unknown |
| Usage access (optional) | `AppOpsManager` check, only if per-app stats are built | Granted / Missing |

Rules: the report carries a timestamp; the UI shows "Verified at hh:mm" or "Not verified"; `ShizukuCard` text is derived from the report, not from `ShizukuState` alone; the probe runs on resume and before every scan and every delete (cheap calls only). Export the report in diagnostics.

## 4. Typed results at the backend boundary (fixes F-02, F-13)

Replace "null means everything" with a result type, keeping `FsOps` unchanged:

```
sealed interface FsResult<out T> {
    data class Ok<T>(val value: T) : FsResult<T>
    data object NotFound : FsResult<Nothing>
    data class Denied(val why: String) : FsResult<Nothing>
    data class Unavailable(val why: String) : FsResult<Nothing>   // service dead, bind timeout, not authorized
    data class Failed(val why: String) : FsResult<Nothing>
}
```

- `ShizukuFileBackend.call` maps: no service -> `Unavailable`; `RemoteException` -> `Unavailable` and invalidate; service returned null -> `Denied` or `NotFound` (add a status code to the AIDL reply, see section 7).
- Scanners must turn `Unavailable`/`Denied` into `ScanProgress.ScannerFailed` or an `incomplete` flag, never an empty list.
- `CleaningEngine` reports `Unavailable` as FAILED ("Shizuku service unreachable"), not SKIPPED "Already gone", and verification treats `Unavailable` as "not verified", not as "gone".
- `ScanItem` gains `sizeComplete: Boolean` and `unreadableCount: Int`; the UI prefixes "at least" when incomplete (fixes F-06).

## 5. Inventory and analyzers (requirements 2 to 4, 8; fixes F-09)

**Single pass.** `StorageIndexer` walks the allowed roots once with the existing `StorageWalker` limits and produces:

- `DirNode(path, bytes, fileCount, newestMtime, complete, unreadable)` for directories down to a configurable depth, aggregated bottom-up while streaming (never holds all files in memory);
- top-K heaps for largest files and largest directories;
- per-category byte totals via `TypeClassifier` (extension first; magic-byte sniffing only for ambiguous or unknown extensions; no MIME database dependency required);
- a candidate list of regular files in the size-groups the duplicate scanner needs (size and fingerprint only, no hashing).

Existing scanners become consumers of this view. They keep their evidence rules. Heavy work (hashing) stays on demand.

**Roots.** Root list becomes data, not a constant: default is the six current roots plus an optional "whole shared storage" mode that still excludes `Android/` (separate path through the service) and still respects `PathRules`. Whether `Android/media` is readable-only or deletable is decision D-3.

**Android/data.** To avoid one binder call per entry, add read-only service methods (section 7) that return compact aggregates, for example `childSizes(path)` returning `[name, bytes, newestMtime, complete]` per child, paged to stay far under the binder limit.

**Snapshots (req. 8).** Persist aggregates, not per-file rows: root totals, `DirNode`s to depth N, top-K lists, category totals, scan timestamp and the capability report. Growth analysis is a diff of two snapshots by directory path. Retention: last N snapshots or 90 days. Storage engine is decision D-1. The same store holds "first seen as orphan" timestamps, which F-05 needs.

## 6. Memory diagnostics (requirement 9, read-only)

Rule: this module reads, it never "boosts", kills or clears anything, and the README stance (no boost claims) stays.

Candidate signals. **All are UNVERIFIED; do not build before experiment E-10 confirms each on the target device and Android versions.**

| Signal | Candidate source | Process needed | Status |
| --- | --- | --- | --- |
| Total/available RAM, low-memory threshold | `ActivityManager.getMemoryInfo` | app | expected to work; verify |
| Detailed kernel memory counters, swap totals | `/proc/meminfo` | app | commonly readable; verify per version |
| Own process memory | `Debug.MemoryInfo` | app | expected to work |
| Memory pressure | PSI files under `/proc/pressure/` | app or shell | kernel and SELinux dependent; unknown |
| zRAM size and use | `/proc/swaps`, `/sys/block/zram*/` | probably shell | unknown |
| Per-process memory of other apps | not available to ordinary apps (`/proc/<pid>` of other UIDs is hidden); shell-level tools exist | Shizuku | BLOCKED for the app process; unknown via Shizuku |

Design: `MemorySource` interface per signal with `Available / Unavailable(reason)` results (same typed approach as section 4), so the UI can show "not available on this device" instead of zeros. For anything needing the shell UID, add **fixed-purpose** service methods that read an allow-listed file by id (for example `readSystemFile(ID)`), never an arbitrary path. If the only way to get a signal is to run a tool such as `dumpsys`, that is a deliberate exception to the "no shell" invariant and is decision D-4; do not add it silently.

## 7. Privileged service evolution

- **Keep** `IFileService` method ids 1 to 5 and `destroy() = 16777114` unchanged. **Append** new methods with new ids. Add `int apiVersion()` so the app can detect an older running service (the connector already passes `version(BuildConfig.VERSION_CODE)`; verify in E-08 how a version change restarts the process).
- Every method re-validates its arguments (as today). Add a status code to replies so the app can tell "missing" from "refused" (F-02).
- New read-only candidates: `childSizes(path)`, `readSystemFile(id)`. New destructive methods: none planned.
- Optional defence in depth (F-07): per-session deny-list (installed package set plus own package) pushed by the app and enforced by the service for `TREE`. Label it as protection against app bugs, not a trust boundary.
- No arbitrary-path methods, no command strings, no `Shizuku.newProcess`. `PolicyGuardTest` keeps enforcing this; any exception from D-4 is added to its allow-list explicitly.
- Add a thin interface over the AIDL stub so tests can inject a fake (F-08).

## 8. Interruption safety (fixes F-04, F-13)

- `DeletionJournal`: before each item is deleted, append `{plan id, path, mode, fingerprint}`; after, append the outcome. On next start, if a plan has no completion record, show "Last cleanup was interrupted" with the per-item state. Small append-only file in app storage.
- Run-to-completion: D-2 chooses between (a) foreground service (adds `FOREGROUND_SERVICE` and a type permission, breaking the "exactly two permissions" guard, which would then be updated deliberately) and (b) keep running in-process plus the journal and a warning when the app goes to the background.
- Cancellation: check `ensureActive` inside `SafeDeleter.purge` through a cancel callback, so one large folder can be stopped between entries. Do not change deletion order or identity checks.
- Credit `bytesFreed` for PARTIAL and LIMIT_EXCEEDED; report logical size and say so.

## 9. Decisions for the owner

| ID | Question | My suggestion |
| --- | --- | --- |
| D-1 | Snapshot store: Room, framework SQLite, or flat files | Room if you accept the dependency (KSP is already in the build); otherwise flat JSON per snapshot. Both stay small because only aggregates are stored. |
| D-2 | Long cleanups: foreground service or in-process plus journal | In-process plus journal first (no new permissions); revisit if E-03 shows real interruptions. |
| D-3 | Should user-ticked single files under `Android/media` be deletable, and should it be scanned read-only | Read-only analysis first; deletion stays blocked until E-06 and a policy review. |
| D-4 | May the privileged service ever run a fixed tool (for example `dumpsys`) for diagnostics | Prefer file reads. If a tool is unavoidable, one fixed argv, no inputs, listed explicitly in `PolicyGuardTest`. |
| D-5 | "Unusable without Shizuku" vs the current optional design | Keep optional: seven scanners work without it, and the capability report makes the difference visible. If you want Shizuku-required, say so; it is a UI gate, not a code rewrite. |
| D-6 | License, applicationId, security contact | Decide before the first public release (cannot be changed later without breaking updates). |

## 10. Explicitly not planned

- Rewriting `SafeDeleter`, `SafetyValidator` or the plan/confirm types.
- Hidden or undocumented Android APIs (the repo already removed them once).
- Claiming access to `/data/data`, other apps' private files, or other apps' processes. Android and Shizuku do not grant that to a shell-UID helper in general; whatever can be read must be proven per device (E-01, E-10).
- Splitting into multiple Gradle modules before there is a need.
- Any network permission, analytics or crash reporter (`PolicyGuardTest` stays).
