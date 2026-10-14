# Response to CleanForge_FINAL_INDEPENDENT_SECURITY_AUDIT.md

Version 0.2.1. For the re-audit the report asks for. Each finding was checked against the source first, then patched.

## What was and was not verified

| | |
|---|---|
| Compiled (Kotlin, AIDL, KSP/Hilt, Compose, resources) | **No.** No Android SDK, Gradle, Kotlin compiler or network was available. |
| Unit tests executed | **No.** 91 tests exist (25 new); none has been run. |
| Lint, `assembleDebug`, `assembleRelease` | **No.** |
| On a real device or with Shizuku | **No.** |
| Done instead | Source review of every referenced file, bracket/parenthesis balance check of all `.kt` files, grep checks for stale references (old `PlanBuilder.build` signature, removed constants, other `PackageInventory` implementers). |

Nothing below may be read as "tests pass". The tests encode the required behaviour; CI has to execute them.

## Findings

| ID | Finding | Real? | Change | Regression tests (not yet run) |
|---|---|---|---|---|
| H1 | `MATCH_UNINSTALLED_PACKAGES` list used as proof of "installed" | Yes | `PackageInventory.isCurrentlyInstalled()` (no `MATCH_UNINSTALLED_PACKAGES`, plus `FLAG_INSTALLED`, fails closed). `CacheScanner` requires it. `CleaningEngine` requires it for `APP_CACHE`; for `CORPSE_LEFTOVER` it refuses a package that is known **or** installed. `CorpseScanner` skips an installed package. `knownPackageNames()` is unchanged, so data-kept uninstalls are still not called leftovers. | `ScannerEvidenceTest`: data-kept uninstall yields no cache and no corpse; installed-but-unknown is never a leftover. `CleaningEngineTest`: uninstalled-after-scan refused while still "known"; unknown package refused; installed package still cleaned; installed-but-unknown leftover refused. `PolicyGuardTest`: cache path and engine call `isCurrentlyInstalled`, and its body never uses `MATCH_UNINSTALLED_PACKAGES`. |
| H2 | No Gradle wrapper, build unverified | Yes | **Not fully fixable here**: `gradle-wrapper.jar` is a binary and there is no Gradle or network. CI now runs the audit's exact commands through `./gradlew`, validates a committed wrapper, otherwise generates one, uploads it as the `gradle-wrapper` artifact and **fails on purpose** until it is committed. `BUILD.md` has the one command. | n/a |
| M1 | Confirmation dialog hides evidence | Yes | `ConfirmDialog` lists every item (the old "and N more" cut-off is gone) with full path, category, why flagged, confidence, risk, exact action, owning app, Shizuku/no-undo flags, `KEEP` / `DELETE CANDIDATE` for duplicates. Refused items and the report show full paths. Review rows show the full path. Rendered from a pure `confirmationEvidence()`. | `ConfirmationEvidenceTest` (same file name, different path/owner; evidence fields; keeper line; per-mode action text), `PolicyGuardTest` (dialog uses it, never a bare `displayName`). This is a JVM test of what the dialog renders, **not** a Compose UI test. |
| M2 | Keeper chosen by age | Yes | Scanner lists every copy, no keeper. `PlanBuilder.build(selected, scanned)` pins a copy the user did not tick; refuses a group whose every copy is ticked, also through another category; prefers the duplicate form when a path is flagged twice. Engine also refuses keeper == file. Keeper fingerprint check kept. | `PlanBuilderTest` (7 cases), `ScannerEvidenceTest` (all copies listed, no keeper), `CleaningEngineTest` (oldest copy deleted, unticked copy survives, end to end). |
| M3 | Child-level TOCTOU in `purge()` | Yes | Every child and the root are `lstat`ed again right before `remove()` and left untouched on a type/device/inode change; same for single-file removal. **Narrowed, not closed**: still two path-based calls (no `unlinkat` from Kotlin). `SECURITY.md` says so, including inode reuse. `deleteRecursively` not used. | `SafeDeleterTest`: child swapped for another file, sub-folder swapped for a symlink, file swapped just before removal. `PolicyGuardTest`: no `deleteRecursively`. |
| M4 | Multi-user wording | Yes | Wording changed in code docs, manifest comment, README, SECURITY, scanner reason text: current user/profile only. | n/a |
| L1 | Lint hard gate | Conditional | `abortOnError` is on with `-PstrictLint`; CI uses it (non-blocking until a clean baseline exists, then remove `continue-on-error`). Not on by default, as the report says to wait for a baseline. | n/a |
| L2 | `MIN_PLAUSIBLE_PACKAGES` heuristic | Yes | Documented as a fail-closed guard. An explicit failure is still `null` from `knownPackageNames()`. Not replaced. | existing tests |

## Found while patching (not in the report)

The results list used the file path as its Compose `LazyColumn` key. A path flagged by two scanners (e.g. a large file that is also a duplicate) would repeat the key and crash the Review screen. Keys are now category + path. No automated test: it needs Compose.

## Mandatory invariants from section 18

1. *Reported by `MATCH_UNINSTALLED_PACKAGES` but not installed => never an installed app cache, never auto-selected.* Encoded in `ScannerEvidenceTest` (data-kept uninstall). Not executed.
2. *`APP_CACHE` item whose package is no longer installed => refused at deletion.* Encoded in `CleaningEngineTest`. Not executed.

## Still open before "safe to test"

- Generate and commit the Gradle wrapper; get one green CI run (tests, lint, both APKs). Expect the first build to need small fixes in code that touches APIs nobody compiled (see `BUILD.md`, "If the first build fails").
- Device checks on Android 11 with and without Shizuku, including `isCurrentlyInstalled()` and `FLAG_INSTALLED` behaviour on the Vivo target.
- Shizuku adversarial cases (section 12.3) and reboot-between-scan-and-delete have no automated test.
- Compose UI test for the dialog, if wanted.
