# Security and safety model

CleanForge deletes files. The design goal is that it can never delete something you did not see, understand and confirm.

## Pipeline (no step may be skipped)

```
SCAN -> CLASSIFY -> VALIDATE -> PLAN -> USER CONFIRMATION -> REVALIDATE -> DELETE -> VERIFY -> REPORT
```

| Step | Where | What it guarantees |
|---|---|---|
| SCAN / CLASSIFY | `core/scanner/*` | A finding is a *proposal with evidence*, never a command. |
| VALIDATE / PLAN | `SafetyValidator`, `PlanBuilder` | Path policy, mode policy, overlap rules, and the duplicate rule: the copy that stays is one the user did NOT tick; a group whose every copy is ticked is refused. Refused items are kept with their reason. |
| USER CONFIRMATION | `ConfirmDialog` | Lists EVERY item with its full path, why it was flagged, confidence, risk, exactly what is deleted, "no undo", Shizuku need, and for duplicates `KEEP` / `DELETE CANDIDATE`; refusals too. `CleaningEngine` accepts only a `ConfirmedPlan`. |
| REVALIDATE | `CleaningEngine.processItem` | Policy again, live permissions, fresh package-state check (app cache: package **currently installed**; leftover: package not known and not installed), scan-time fingerprint, kept-copy check. |
| DELETE | `SafeDeleter` (app or Shizuku service) | No symlink following, no mount crossing, bounded depth/entries, directory identity re-checked, and every entry re-`lstat`ed and identity-compared immediately before it is removed. |
| VERIFY / REPORT | `CleaningEngine`, `ReportDialog` | Re-stat after deletion; freed bytes come from what was really removed. |

## Hard rules enforced in code

- Only the canonical root `/storage/emulated/0` is accepted. Aliases (`/sdcard`, `/storage/self/primary`) are refused.
- Paths with `..`, `.`, empty segments, trailing slash, NUL/control characters, or over 4096 chars are refused.
- Structural names are compared case-insensitively (the filesystem is case-insensitive).
- Recursive deletion only for: `Android/data/<pkg>/cache` (contents), `Android/{data,obb}/<pkg>` (uninstalled app, TREE), `DCIM/.thumbnails` (contents).
- `Android/data`, `Android/obb`, `Android/media` and every top-level storage folder can never be deleted.
- Inside DCIM, Pictures, Movies, Music, Documents, Download, Recordings, Audiobooks, Podcasts, Ringtones, Alarms, Notifications, WhatsApp, Telegram: single files only, only after you tick them, never pre-selected.
- Pre-selection is limited to well-evidenced app caches of non-messaging apps. Large, old, duplicate, or unknown never means junk.
- No shell is ever started and no command line is built from a file name. A test fails the build if `Runtime.exec`, `ProcessBuilder` or `Shizuku.newProcess` appear.
- The Shizuku service trusts nothing from the app process: it re-checks each path and allows only the two recursive shapes above.
- Fail closed: if the package list cannot be read or looks implausibly small (`MIN_PLAUSIBLE_PACKAGES` is only a fail-closed guard, not proof the query worked), leftover and cache findings are not produced and deletion is skipped.
- **Two different package questions.** `knownPackageNames()` = "Android still has an identity for this name" (includes apps uninstalled with their data kept); `isCurrentlyInstalled()` = "installed right now". An app cache is only ever offered, and only ever deleted, for a package that is currently installed. A data-kept uninstalled package is neither an app cache nor a leftover: it is left alone. Both checks cover the current Android user/profile only; other users and work profiles are not enumerated.
- **Duplicates.** CleanForge never decides which copy you want (age proves nothing). Every copy is listed, none is pre-ticked, you tick what to delete, one copy you did not tick is pinned as the keeper (and re-checked, unchanged, right before deleting), and it can never plan the deletion of every copy, also not via another list such as large files.

## Privacy

- No INTERNET permission, no analytics, ads, telemetry or accounts, no cloud processing. Enforced by `PolicyGuardTest`.
- Permissions: `MANAGE_EXTERNAL_STORAGE` (list and delete what you select) and `QUERY_ALL_PACKAGES` (know which apps are installed for the current user/profile).
- `allowBackup=false`.

## Shizuku

Shizuku is **not root**. By default it runs commands as the shell user. If you start it in root mode, CleanForge shows that and applies exactly the same path rules. It is optional: without it, only the Android/data cleaners are disabled.

## Known residual risks

- **Race between last check and removal.** Android exposes no `unlinkat`/`openat` to Kotlin, so a process with write access to the same folder could swap an entry between our last `lstat` and `remove`. Mitigations: identity (type, device, inode) of each folder is re-checked before its entries are removed, **and of every single entry again immediately before its own `remove`** (a swapped entry is left untouched and reported), `remove` never recurses, and symlinks are unlinked as links only, so a swap can never make deletion follow a link. The two calls are still separate, so the window is narrowed, not closed. Shared storage does not allow apps to create symlinks, which makes this hard to exploit. Covered by `SafeDeleterTest` (child swapped for another file, sub-folder swapped for a symlink, file swapped just before removal).
- **Inode numbers can be reused** by the filesystem after a delete, so an identity check cannot detect a replacement that happens to receive the same inode. File fingerprints therefore also compare size and modification time.
- **Leftover folders can hold game saves.** They are marked "Check first", never pre-selected, never reversible.
- **Not yet verified on a real Vivo device or Funtouch OS.** Behaviour of Android/data through Shizuku on Android 11 follows the documented model but must be confirmed on hardware.
- **This code has not been compiled or run** in the environment where it was audited and patched, and the Gradle wrapper jar is not in the repository. See `BUILD.md`. Treat every statement in this file as a design claim until CI has run the tests.

## Reporting a vulnerability

Report privately to the maintainer, Aman. Add a contact address or issue-tracker link here before publishing: `<contact: TODO by Aman>`.
