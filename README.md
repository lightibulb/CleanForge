# CleanForge

A free, open-source, offline-first storage cleaner for Android 11+. Made by **Aman**.

No ads. No subscription. No telemetry, analytics or accounts. No internet permission. No root.

> **Status: unverified build.** This version was audited and patched without access to an Android SDK, Gradle or a Kotlin compiler, so it has **not been compiled or tested yet**, and the Gradle wrapper still has to be generated and committed (see `BUILD.md`). No APK is shipped. Build it with `BUILD.md` (or the included GitHub Actions workflow) and run the tests before trusting it with real files.

## What it does

- **Clean tab** (light scan): app caches, legacy thumbnail cache, abandoned partial downloads, APK installers, leftovers of uninstalled apps.
- **Review tab** (on demand): large files, duplicate files, old logs, empty folders. These are only *suggestions for you to look at*. Nothing here is ticked for you. For duplicates, CleanForge lists every copy and never decides which one you want: you tick the copy to delete, and one copy you did not tick always stays.
- Every result says **why** it was flagged, **how much** space it uses, the **risk**, the **strength of the evidence**, whether it can be undone, and whether it needs **Shizuku**.
- Nothing is deleted until you confirm a plan. The confirmation lists every item with its full path, why it was flagged, confidence and risk. Each item is re-checked right before it is removed.

It makes no "boost", "RAM", "CPU" or "speed" claims. Freeing storage does not do that.

## Requirements

- Android 11 (API 30) or newer. Designed for a low-end phone such as the Vivo Y11 (1906), non-rooted.
- **All-files access** for CleanForge (Settings > Apps > CleanForge > Allow access to manage all files).
- **Shizuku** (optional) for app caches and uninstalled-app leftovers, because Android 11+ hides other apps' `Android/data` from normal apps.

## Shizuku setup (optional)

1. Install Shizuku from its official page (F-Droid, GitHub or Play).
2. Start it: on Android 11+ use *Wireless debugging* inside the Shizuku app (no PC needed after the first pairing), or run the adb command it shows.
3. Open CleanForge > tap **Grant permission** on the Shizuku card > allow.
4. Shizuku must be restarted after each reboot (unless you use the root start, which CleanForge supports but does not require).

Shizuku is **not root**. It runs as the shell user and CleanForge only uses it for two exact folder shapes (see `SECURITY.md`).

## Permissions

| Permission | Why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | List and delete things in shared storage that *you* select. |
| `QUERY_ALL_PACKAGES` | Know which apps are installed for the current user/profile, so "app cache" and "leftover of an uninstalled app" are never guesses from folder names. |

There is no internet permission, so file names cannot leave your phone.

## Safety model

Read `SECURITY.md`. In short: scan, explain, validate, plan, ask, re-check, delete, verify, report; personal folders are only touched for individual files you tick; there is no recycle bin, and the app says so before you confirm.

## Limitations

- Not compiled or tested yet (see top).
- Cleaning other apps' *internal* caches (`/data/data`) is not possible without root or a hidden API and is not attempted. The earlier hidden-API Shizuku package-manager backend was removed because it could not compile against the public SDK and could not be verified.
- No per-app storage screen (it needs the special "Usage access" permission); removed rather than half-built.
- Deleted files cannot be restored.
- Behaviour on Vivo Funtouch OS has not been tested.

## Credits and licence

Created by **Aman**. Copyright (c) 2026 Aman.

Licence: **not chosen yet.** Pick one before publishing (GPL-3.0-or-later is the usual FOSS/F-Droid choice; Apache-2.0 is a permissive one) and add a `LICENSE` file. Shizuku's API is Apache-2.0 and is compatible with either.
