# CleanForge Implementation Roadmap (proposal, Part 1)

Nothing below is authorized or started. Part 2 begins only when the owner says so. Sizes: S (under a day), M (a few days), L (a week or more). F-xx and E-xx refer to `docs/CAPABILITY_AUDIT.md`; D-x to `docs/ARCHITECTURE_PLAN.md`.

Ordering rule: first make the project provable, then make its status honest, then protect against interruption, then add features on a shared foundation.

## Phase 0. Make it provable (P0 gate)

| # | Task | Size | Exit criterion |
| --- | --- | --- | --- |
| 0.1 | Run `./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` in CI or locally; fix compile errors only | M | CI green; test failures triaged one by one (each encodes a safety rule, so fix the code unless the test is wrong) |
| 0.2 | Repo hygiene (F-15): set exec bit on `gradlew` (`git update-index --chmod=+x`), remove the duplicate scripts under `gradle/wrapper/`, update `BUILD.md`/README/CI comments about the wrapper, drop the stray `#Aman` line, add `permissions: contents: read` and SHA-pin actions, optionally add `distributionSha256Sum` | S | Fresh clone builds with `./gradlew` on Linux |
| 0.3 | Device smoke test: E-01, E-02, E-04 on the Android 11 target, then one Android 14/15 device | M | Written results appended to the audit; any contradiction with the docs is fixed in the docs first |
| 0.4 | Owner decisions D-6: LICENSE, applicationId, security contact | S | `LICENSE` committed; id final before any public APK |

Do not distribute an APK or tell anyone to use it on real data before 0.1 and 0.3 are done.

## Phase 1. Truthful status (P1)

| # | Task | Fixes | Size | Depends on |
| --- | --- | --- | --- | --- |
| 1.1 | `FsResult` type at `FileOperationBackend`; map Shizuku failures; AIDL status codes (append-only) | F-02 | M | 0.1 |
| 1.2 | Scanners report Unavailable/Denied as failed or incomplete; Clean tab distinguishes "scan finished, nothing found" from "could not scan" | F-02 | S | 1.1 |
| 1.3 | `CapabilityProbe` + `CapabilityReport`; `ShizukuCard` text from the report; permanent-denial guidance | F-03 | M | 1.1 |
| 1.4 | Carry `complete` and unreadable counts into `ScanItem`; "at least" labels | F-06 | S | 1.1 |
| 1.5 | Engine messages: unreachable service is FAILED not "Already gone"; verification treats Unavailable as not verified | F-02, F-13 | S | 1.1 |
| 1.6 | Interface over the AIDL stub; tests for disconnect, timeout, null, oversize list, rebind; `ScanRepository` tests | F-08, F-14 | M | 1.1 |
| 1.7 | Interim fix for the orphan gate: reword the reason to what mtime really measures; make the default stricter; leave the real fix for 4.3 | F-05 | S | E-09 result |

Exit: with a fake service in every failure mode, the UI never shows an empty result for a failed scan.

## Phase 2. Interruption safety (P1)

| # | Task | Fixes | Size | Depends on |
| --- | --- | --- | --- | --- |
| 2.1 | `DeletionJournal` and "last cleanup was interrupted" screen | F-04 | M | 0.1 |
| 2.2 | Cancellation inside `SafeDeleter.purge` through a callback | F-13 | S | none |
| 2.3 | Credit freed bytes for PARTIAL/LIMIT_EXCEEDED; keep partial report when the flow throws; call `forget` for what was deleted | F-13, F-04 | S | none |
| 2.4 | Decide D-2; if foreground service is chosen, add it with its permission and update `PolicyGuardTest` on purpose | F-04 | M | E-03 |
| 2.5 | Optional service-side deny-list for `TREE` (D-note in plan section 7) | F-07 | S | 1.1 |

Exit: E-03 repeated; the report after a kill is accurate.

## Phase 3. Inventory foundation (P2)

| # | Task | Serves | Size | Depends on |
| --- | --- | --- | --- | --- |
| 3.1 | Measure first: scan time and I/O on the low-end device (E-11) | req. 10 | S | 0.3 |
| 3.2 | `StorageIndexer`: one bounded pass, `DirNode` aggregates, top-K files and directories | req. 2, 3 | L | 1.1 |
| 3.3 | `TypeClassifier` and category breakdown | req. 4 | M | 3.2 |
| 3.4 | Move Temp, APK, Log, Large, Duplicate, EmptyDir scanners onto the inventory without changing their evidence rules | F-09 | M | 3.2 |
| 3.5 | Analyze screen: directory-size tree, largest directories, type breakdown (read-only) | req. 2, 3, 4 | M | 3.2, 3.3 |
| 3.6 | Root list as data; decide D-3 for `Android/media` (read-only first) | F-10 | S | 3.2 |
| 3.7 | Service `childSizes(path)` for `Android/data` aggregates | req. 2, 5 | M | 1.1 |

Exit: a full scan visits each directory once; results match the old scanners on a fixture tree.

## Phase 4. Persistence and growth (P2)

| # | Task | Serves | Size | Depends on |
| --- | --- | --- | --- | --- |
| 4.1 | Decide D-1; snapshot store for aggregates only; retention rule | req. 8 | M | 3.2 |
| 4.2 | Snapshot diff and growth view (by directory, by category) | req. 8 | M | 4.1 |
| 4.3 | "First seen as orphan" timestamps; orphan gate becomes "at least N days since first seen" | F-05 | S | 4.1 |
| 4.4 | Migration tests; store size cap | req. 8, 10 | S | 4.1 |

## Phase 5. App storage and orphans (P2)

| # | Task | Serves | Size | Depends on |
| --- | --- | --- | --- | --- |
| 5.1 | Optional Usage-access flow and per-app size screen using the platform storage-stats API, shown as "not available" until granted; verify per-user handling (the removed code used the wrong user handle) | req. 5 | M | 1.3 |
| 5.2 | Per-app external data sizes via the service (`Android/data`, `Android/obb`), clearly labelled as external only | req. 5 | M | 3.7 |
| 5.3 | Orphan candidates outside `Android/` (for example empty or tiny top-level folders with no matching installed package name), evidence-based and review-only | req. 7 | M | 3.2 |
| 5.4 | Pre-selection policy for caches (F-11) after E-02 findings | F-11 | S | E-02 |

## Phase 6. Memory diagnostics (P2)

| # | Task | Serves | Size | Depends on |
| --- | --- | --- | --- | --- |
| 6.1 | Experiment E-10 on each target Android version; record which signals exist | req. 9 | M | 0.3 |
| 6.2 | `MemorySource` interface with Available/Unavailable results; app-process sources first | req. 9 | M | 1.1, 6.1 |
| 6.3 | Privileged read-only sources through allow-listed ids; decide D-4 first | req. 9 | M | 6.1, D-4 |
| 6.4 | Diagnostics screen: totals, pressure, swap/zRAM where available, honest "not available" otherwise; no boost, kill or clear actions | req. 9, 12 | M | 6.2 |

## Phase 7. Refinements (P3)

Exportable diagnostics report with path redaction; accessibility pass (content descriptions, TalkBack, font scale); localization scaffolding; dependency updates (AGP, Kotlin, Compose BOM) with a fresh test run; dependency verification metadata; Compose UI test for the confirmation dialog; service lifecycle cleanup (`unbind`, `unregister` callers, `pending` completion on disconnect, E-07/E-08 outcomes); `st_mtim` precision on API 31+ (F-12).

## Recommended order, in one line

0.1 -> 0.2 -> 0.3 -> 1.1 -> (1.2, 1.4, 1.5 in parallel) -> 1.3 -> 1.6 -> 2.1 -> 2.2/2.3 -> 3.1 -> 3.2 -> 3.4 -> 3.3/3.5 -> 4.x -> 5.x -> 6.x -> 7.

## Gates

- No phase starts before the previous phase's exit criterion is met, except phase 6.1 (experiments) which can run any time after 0.3.
- Every change that touches deletion, path policy or the confirmation flow ships with a regression test first.
- Every new capability appears in the UI only as a probed state, never as an assumption.
