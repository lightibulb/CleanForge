# Shizuku capability matrix

Scope: what CleanForge does through Shizuku, what it needs, what it can and cannot prove, and how each result is
decided. Written for Part 2. Nothing here has been run on a device; every "verified" below means *verified by a JVM unit
test over the real logic with a fake filesystem and a fake service*, and every device claim is listed as unverified in
section 8. See `docs/PART2_HANDOFF.md` for what was and was not executed.

## 1. What "privileged" means here

CleanForge starts one Shizuku *user service* (`ShizukuFileService`). It runs as whatever user Shizuku runs as:

| Shizuku mode | uid | Can read `Android/data`, `Android/obb` of the primary user | Can read another app's `/data/data` |
|---|---|---|---|
| Wireless debugging / adb | 2000 (shell) | expected yes (see device tests D-03, D-04) | **no** |
| Root start | 0 | yes | would be possible, **CleanForge never asks for it** |

* Shizuku is **not root** unless the user started it as root, and even then the service only accepts the four
  operations below. The UI shows the uid the service itself reported (`Service runs as uid 2000 (shell, not root)`).
* There is **no shell** in the path: no `Runtime.exec`, no `ProcessBuilder`, no `Shizuku.newProcess`, no command line
  built from a name. A unit test (`PolicyGuardTest`) fails the build if any of those appear in the privileged code.
* The service exposes a fixed set of methods (section 6). It has no "run this path" or "run this command" method.

## 2. The operations

`PrivilegedOperation` is a closed enum. Every privileged action is one of these.

| Operation | What it does | Target shape (enforced in the service) | Mutates |
|---|---|---|---|
| `LIST_EXTERNAL_APP_DATA` | Names of the folders directly inside `Android/data` / `Android/obb` (and of a cache folder, for verification) | any path at or below `Android/data` or `Android/obb`; every ancestor below the storage root must be a real directory | no |
| `MEASURE_EXTERNAL_APP_DIR` | Sum of regular-file sizes below a folder | same as above | no |
| `CLEAR_EXTERNAL_APP_CACHE` | Delete the **contents** of `Android/data/<pkg>/cache`; the folder stays | exactly `Android/data/<valid package>/cache` | yes |
| `REMOVE_UNINSTALLED_APP_DATA` | Delete `Android/data/<pkg>` or `Android/obb/<pkg>` including the folder | exactly `Android/{data,obb}/<valid package>`; refused if the package is in the installed-apps guard or is CleanForge itself | yes |

### Prerequisites and what a probe can prove

A connected binder proves none of these operations works. `CapabilityRegistry.probe()` is read-only: it binds the
service, asks `uid()` and `apiVersion()`, and lists `Android/data` and `Android/obb`. It never deletes anything to test
deletion.

| Operation | Needs | After a clean probe | Becomes `VERIFIED` when |
|---|---|---|---|
| `LIST_EXTERNAL_APP_DATA` | Shizuku running, authorized, service bound, protocol version ok, `Android/data` **or** `Android/obb` listable | `VERIFIED` (the probe did it) | – |
| `MEASURE_EXTERNAL_APP_DIR` | same | `PREREQUISITES_MET` | the first measurement succeeds |
| `CLEAR_EXTERNAL_APP_CACHE` | same, and `Android/data` listable | `PREREQUISITES_MET` | the first cache clear is confirmed |
| `REMOVE_UNINSTALLED_APP_DATA` | same, and `Android/data` or `Android/obb` listable | `PREREQUISITES_MET` | the first removal is confirmed |

A later probe does not downgrade an operation that really worked, unless Shizuku went away in between.

### Capability states

`CapabilityStatus`: `VERIFIED`, `PREREQUISITES_MET`, `NEEDS_AUTHORIZATION`, `UNAVAILABLE`, `UNSUPPORTED`, `UNKNOWN`.
Only the first two let an operation be attempted (`canAttempt`).

| Shizuku / service situation | Status of every operation | Message to the user |
|---|---|---|
| Shizuku not installed | `UNAVAILABLE` | install, start, come back |
| Shizuku installed, not running | `UNAVAILABLE` | start it (wireless debugging or adb) |
| Binder died (`Disconnected`) | `UNAVAILABLE` | start it again |
| Server older than v11 (`ServerTooOld`) | `UNSUPPORTED` | update the Shizuku app |
| Running, CleanForge not allowed yet | `NEEDS_AUTHORIZATION` | allow in the permission dialog |
| Denied with "don't ask again" | `NEEDS_AUTHORIZATION` | open the Shizuku app and allow CleanForge there (a dialog would not appear) |
| Service bind times out / refused / dies while starting | `UNAVAILABLE` | try again |
| Service speaks a different protocol version | `UNSUPPORTED` | restart Shizuku and the app |
| `Android/data` **and** `Android/obb` both unreadable for the shell | `UNSUPPORTED` | not possible on this device, as probed |
| Only `Android/data` unreadable | cache clear `UNSUPPORTED`, the rest per table above | |
| Probe call hangs | `UNAVAILABLE` (service is restarted) | try again |

## 3. Results

Reads return `FsResult`: `Ok`, `NotFound`, `Denied`, `Unavailable`, `Failed`. `NotFound` is an answer; the other three
mean "we do not know" and are never treated as "gone" or "empty".

Maintenance returns `MaintenanceResult`:

| Result | Meaning | Becomes (`ItemStatus`) |
|---|---|---|
| `Success` | the goal was reached **and the end state was checked** (or, after a lost reply, observed through a fresh binding) | `DELETED` |
| `Partial` | some entries were removed (some could not be, a safety bound was hit, files reappeared) | `PARTIAL` |
| `Unverified` | may or may not have happened, or the end state could not be read | `UNVERIFIED` |
| `Unsupported` | not attempted: the registry says the operation cannot run now | `SKIPPED` |
| `Failure` (precondition changed, refused) | nothing was deleted: target changed, vanished, wrong type, or policy said no | `SKIPPED` |
| `Failure` (unavailable, limit, cancelled, I/O) | the attempt failed | `FAILED` |

Classification table (`classifyDeletion`, unit-tested for every status):

| Service status | End state observed | Result |
|---|---|---|
| OK | folder gone (tree/file) / folder empty or gone (contents only) | Success |
| OK | folder still there, or files remain | Partial |
| OK | could not look | Unverified (bytes the service reported are kept, shown as unconfirmed) |
| PARTIAL | not consulted | Partial (real byte count) |
| LIMIT_EXCEEDED / CANCELLED | not consulted | Partial if anything was removed, else Failure |
| MISSING / IDENTITY_CHANGED / WRONG_TYPE | not consulted | Failure: precondition changed |
| REFUSED | not consulted | Failure: refused |
| FAILED | not consulted | Failure: I/O |
| call timed out | observed through a fresh binding | Success (gone/empty, bytes unknown) or Unverified |
| service died during the call | observed through a fresh binding | Success (gone/empty, bytes unknown) or Unverified |

Deletions are **never retried automatically**. Reads are retried once after a service death.

Byte counts are **logical file sizes** reported by the deleter, not disk blocks and not a statfs delta.

## 4. Sizes

| Quality | When | Shown as |
|---|---|---|
| `MEASURED` | complete walk, nothing unreadable | `12.3 MB` |
| `LOWER_BOUND` | walk stopped at 200,000 entries or 32 levels, or some sub-folders could not be listed | `≥ 12.3 MB`; the confirmation says "at least" |
| unavailable | the folder could not be measured at all | no item is shown; the scan says how many were skipped |

No code path **estimates** a size. `StorageStatsManager` / usage-access figures are deliberately not used in this phase
(they need a different permission and belong to Part 5). A cache of 0 readable bytes whose sub-folders could not be
read is reported as "could not be examined", never as empty.

## 5. Package inventory (installed or not)

`PackageInventory.installState(pkg)` is one package-manager lookup with `MATCH_UNINSTALLED_PACKAGES`:

| Answer | Source | Cache clear | Leftover removal |
|---|---|---|---|
| `INSTALLED` | found, `FLAG_INSTALLED` set | allowed | refused |
| `UNINSTALLED_DATA_KEPT` | found, flag not set | refused | refused (Android still knows it) |
| `NOT_KNOWN` | `NameNotFoundException` | refused | allowed (plus every other leftover rule) |
| `UNKNOWN` | any other failure | refused | refused |

A leftover directory is never evidence of anything: installation is decided only by the package manager. A failed query
used to read as "not installed", which is the unsafe answer for a leftover; it is now `UNKNOWN` and skips the item. Folder
names are also compared case-insensitively to known packages because shared storage is case-insensitive. Only the
current user/profile is covered.

## 6. The service surface (AIDL)

| id | Method | Status |
|---|---|---|
| 16777114 | `destroy()` | unchanged |
| 1 | `uid()` | unchanged |
| 2–5 | `lstat`, `list`, `directorySize`, `delete` | unchanged and still answered; the app no longer calls them because they cannot say *why* there is no value |
| 6 | `apiVersion()` | new |
| 7 | `statEx` | new: status + stat |
| 8 | `listEx` | new: status, truncation flag, names (total length bounded far below the 1 MB binder limit) |
| 9 | `sizeEx` | new: status, bytes, complete flag, unreadable count |
| 10 | `deleteEx` | new: status, entries, bytes, detail text |
| 11 | `cancelCurrent()` | new: stops a running delete before its next entry |
| 12 | `setProtectedPackages(String[])` | new: installed-apps guard for tree deletions |

Service-side rules (`FileServiceCore`, unit-tested):

* path must be canonical and below `Android/data` or `Android/obb`; `..`, `.`, empty segments, trailing slash, control
  characters and over-long paths are refused;
* every ancestor below the storage root must be a real directory, not a symlink (`lstat` alone only protects the last
  component);
* a tree delete is refused until the app has pushed the installed-apps guard, and for any package in it (any letter case)
  and for CleanForge's own package;
* an oversized guard list is rejected whole and the previous guard stays (a truncated guard would silently weaken it);
* directory identity (device, inode) is required for every delete.

The guard protects against a **logic bug in the app**, not a hostile app: only the app Shizuku authorized can reach the
service, and a hostile caller could simply send an empty list.

## 7. Deliberately not supported (and not faked)

| Wanted | Status |
|---|---|
| Clear another app's **internal** cache (`/data/data/<pkg>/cache`) | not possible with the shell user and no root; **not attempted**, no weaker substitute |
| `pm clear`, `pm trim-caches`, `dumpsys`, `cmd package`, any shell command | not used; there is no shell in the service |
| The earlier hidden-API package-manager backend | removed in an earlier release; not reintroduced |
| Per-app cache sizes from `StorageStatsManager` | not used (Part 5) |
| Work profiles, secondary users, SD cards | not covered |
| `Android/media`, anything outside `Android/{data,obb}/<pkg>[/cache]` | refused by the service |
| Claiming a size is exact when part of the folder was unreadable | never; shown as a lower bound |

## 8. Shizuku API assumptions and device tests

The Shizuku calls below are used from the Shizuku API 13.1.5 as documented. **They could not be compiled or run in the
environment that produced this change** (no Android SDK, Gradle, Kotlin compiler or network). Treat each as unverified
until the build and the device checks pass.

| Call | Assumption | Check |
|---|---|---|
| `Shizuku.shouldShowRequestPermissionRationale()` | `true` means "deny and don't ask again" (as in the official demo) | D-02 |
| `Shizuku.bindUserService(args, connection)` | does not need to be idempotent: a **fresh `ServiceConnection` is used for every bind attempt**, so a lost or duplicated callback cannot confuse two attempts | D-06 |
| `Shizuku.unbindUserService(args, connection, remove = true)` | stops the service process, which releases a thread blocked in a hung call | D-08 |
| Binder thread pool in the user-service process | `cancelCurrent()` is served while `deleteEx` runs; if not, cancel is a harmless no-op and only the app-side wait ends early | D-09 |
| `Shizuku.getVersion()` / `isPreV11()` | usable to detect servers older than v11 | D-01 |

Device checklist (none done):

| # | Check | Expected |
|---|---|---|
| D-01 | Shizuku running, CleanForge allowed | card shows authorized + uid, probe shows `Android/data` listable or an honest `UNSUPPORTED` |
| D-02 | Deny with "don't ask again", return to the app | card says to open Shizuku; the button no longer pretends to ask |
| D-03 | Scan with Shizuku authorized | cache items appear with measured sizes; incomplete notes only if something was really skipped |
| D-04 | Clear one app's cache | only `Android/data/<pkg>/cache` contents removed; folder stays; report says Deleted with real bytes |
| D-05 | Remove a real leftover folder (test app, uninstalled with data kept first, then fully) | data-kept package refused; after full uninstall removable |
| D-06 | Stop Shizuku, start it again without closing the app | next scan rebinds; no stale service |
| D-07 | Kill the Shizuku service process during a scan | scan keeps what it found and says it stopped early |
| D-08 | Make a delete slow (large cache) and let it time out | service restarted; result Unverified or Success with unknown bytes; no hang |
| D-09 | Press **Stop** during a large cache clear | run ends after the current item; report lists done / in-flight / not attempted |
| D-10 | Revoke CleanForge in Shizuku while the app is open | cards update; privileged items skipped with the reason |
| D-11 | Compare reported bytes with the folder size from a file manager | same order; logical, not blocks |
| D-12 | Folder named with spaces / non-ASCII under a cache | cleared correctly (no shell involved) |
| D-13 | Android 11, 12, 13, 14, 15 and the owner's Vivo device | record per version whether `Android/data` is listable by the shell |
| D-14 | Update the app while Shizuku runs | service version check forces a clean rebind |
