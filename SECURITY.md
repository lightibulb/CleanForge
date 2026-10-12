# Security and safety model

CleanForge deletes files. The design goal is that it can never delete something you did not see, understand and confirm.

## Pipeline (no step may be skipped)

`SCAN -> CLASSIFY -> VALIDATE -> PLAN -> USER CONFIRMATION -> REVALIDATE -> DELETE -> VERIFY -> REPORT`

| Step | Where | What it guarantees |
| --- | --- | --- |
| SCAN / CLASSIFY | `core/scanner/*` | A finding is a *proposal with evidence*, never a command. |
| VALIDATE / PLAN | `SafetyValidator`, `PlanBuilder` | Path policy, mode policy, overlap rules, and the duplicate rule: the copy that stays is one the user did NOT tick; a group whose every copy is ticked is refused. Refused items are *kept with their reason*. |
| USER CONFIRMATION | `ConfirmDialog` | Lists EVERY item with its full path, why it was flagged, confidence, risk, exactly what is deleted, *"no undo"*, Shizuku need, and for duplicates KEEP / DELETE CANDIDATE; refusals too. `CleaningEngine` accepts only a `ConfirmedPlan`. |
| REVALIDATE | `CleaningEngine.processItem` | Policy again, live permissions, fresh package-state check (app cache: package *currently installed*; leftover: package not known and not installed), scan-time fingerprint, kept-copy check. |
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
- The Shizuku service trusts nothing from the app process: it re-checks each path and allows only the two recursive shapes above. All of its logic is in `FileServiceCore`, which also refuses any path whose ancestors below the storage root are not real directories (a symlink in the middle of a path is not followed), refuses a tree delete for any package in the installed-apps guard (any letter case) or for CleanForge itself, and refuses every tree delete until the app has pushed that guard.
- The app never sends a path to be deleted. It sends a typed request (clear the cache of package P / remove the leftover of package P); the executor validates the package identifier and builds the path itself. Package identifiers containing `/`, `..`, spaces, control characters or shell metacharacters have no target.
- Failures are never read as success or as absence: a failed read is `Unavailable`/`Denied`/`Failed`, not "not found" and not "empty"; a failed package-manager query is `UNKNOWN`, not "not installed".
- Fail closed: if the package list cannot be read or looks implausibly small (`MIN_PLAUSIBLE_PACKAGES` is only a fail-closed guard, not proof the query worked), leftover and cache findings are not produced and deletion is skipped.
- Two different package questions. `knownPackageNames()` = "Android still has an identity for this name" (includes apps uninstalled with their data kept); `isCurrentlyInstalled()` = "installed right now". An app cache is only ever offered, and only ever deleted, for a package that is currently installed. A data-kept uninstalled package is neither an app cache nor a leftover: it is left alone. Both checks cover the current Android user/profile only; other users and work profiles are not enumerated.
- Duplicates. CleanForge never decides which copy you want (age proves nothing). Every copy is listed, none is pre-ticked, you tick what to delete, one copy you did not tick is pinned as the keeper (and re-checked, unchanged, right before deleting), and it can never plan the deletion of every copy, also not via another list such as large files.

## Storage Forensics (Part 3)

Forensic scanners target known app-specific cache/media patterns. They are strictly narrower than a general walk:

- Category `FORENSIC_MEDIA`, `isQuick = false` (on-demand only), `isPreselected = false` (derived, always false for this category), `DeleteMode.FILE` only.
- WhatsApp and Telegram live under `Android/media`, which All-files access can reach: their items use the app process (`requiresShizuku = false`). The Shizuku service has no single-file delete, so the TikTok / Twitter-X / Instagram scanners are not registered until it does.
- Paths are matched against a **per-package, per-subfolder allowlist** in `SafetyValidator.isKnownForensicPath()`: a single file that is a DIRECT child of one exact folder (not the folder, not nested deeper), and `owningPackage` must equal the package in the path. No wildcard `Android/data` or `Android/media` access.
- Allowed shapes include `Android/media/<pkg>/WhatsApp/Media/.Statuses/*`, `.../Sent/*`, `.../Databases/msgstore-*`, `Android/media/<pkg>/Telegram/Telegram {Video,Audio,Images,Documents}/*`, and `Android/data/<pkg>/cache/*` for TikTok / Twitter-X / Instagram.
- Messaging apps (WhatsApp, Telegram, Instagram, Signal, Messenger, etc. in `ProtectedApps.MESSAGING`) are never pre-selected and get an extra caution layer.
- Forensic rules never apply inside personal folders; the personal-zone protection blocks them.

## RAM Monitoring (Part 4)

- Memory info is read from `/proc/meminfo` (world-readable, no permission needed). Nothing is estimated or faked; if the file can't be read the UI shows "unknown", never zero.
- The optional "Clear background apps" action uses `ActivityManager.killBackgroundProcesses` and requires the `KILL_BACKGROUND_PROCESSES` permission.
- Freed bytes are **measured** from the `MemAvailable` delta before/after and clamped to ≥ 0. If memory did not actually increase, the app reports 0 freed — it never invents a number.
- Packages whose install state is `UNKNOWN` (failed query) are never killed (fail-closed).
- The UI states plainly that Android manages RAM intentionally and that clearing is temporary and may use more battery. CleanForge makes **no "boost" or speed claim**.

## Privacy

- No INTERNET permission, no analytics, ads, telemetry or accounts, no cloud processing. Enforced by `PolicyGuardTest`.
- Permissions: `MANAGE_EXTERNAL_STORAGE` (list and delete what you select), `QUERY_ALL_PACKAGES` (know which apps are installed for the current user/profile), and `KILL_BACKGROUND_PROCESSES` (honest RAM clear, Part 4).
- `allowBackup=false`.

## Shizuku

Shizuku is not root. By default its user service runs as the shell user and it executes no commands: the service has a fixed set of file operations and no shell. If you start it in root mode, CleanForge shows that and applies exactly the same path rules. It is optional: without it, only the Android/data cleaners are disabled.

## Known residual risks

- **Race between last check and removal.** Android exposes no `unlinkat`/`openat` to Kotlin, so a process with write access to the same folder could swap an entry between our last `lstat` and `remove`. Mitigations: identity (type, device, inode) of each folder is re-checked before its entries are removed, and of every single entry again immediately before its own `remove` (a swapped entry is left untouched and reported), `remove` never recurses, and symlinks are unlinked as links only, so a swap can never make deletion follow a link. The two calls are still separate, so the window is narrowed, not closed. Shared storage does not allow apps to create symlinks, which makes this hard to exploit. Covered by `SafeDeleterTest`.
- **Inode reuse.** Inode numbers can be reused by the filesystem after a delete, so an identity check cannot detect a replacement that happens to receive the same inode. File fingerprints therefore also compare size and modification time.
- **Leftover folders can hold game saves.** They are marked "Check first", never pre-selected, never reversible.
- The installed-apps guard in the service protects against a logic bug in the app, not against a hostile caller (only the app Shizuku authorized can reach the service at all).
- A cancel request that arrives before a delete has started in the service is not remembered; the app-side wait ends at once, but a delete already dispatched may still finish. The result is then reported as confirmed or unconfirmed from what is observed afterwards.
- **RAM clear is limited.** `killBackgroundProcesses` only trims processes Android already considers reclaimable; it cannot free memory held by foreground or persistent apps, and Android may reload the apps afterwards.
- Not yet verified on a real Vivo device or Funtouch OS. Behaviour of Android/data through Shizuku on Android 11 follows the documented model but must be confirmed on hardware.
- This code has not been compiled or run in the environment where it was audited and patched, and the Gradle wrapper jar is not in the repository. See `BUILD.md`. Treat every statement in this file as a design claim until CI has run the tests.

## Reporting a vulnerability

Report privately to the maintainer, Aman. Add a contact address or issue-tracker link here before publishing: `<contact: TODO by Aman>`.