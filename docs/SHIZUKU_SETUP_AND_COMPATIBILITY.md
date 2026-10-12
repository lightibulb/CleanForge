# Shizuku: setup, states and compatibility

Shizuku is **optional**. Without it CleanForge still scans and cleans everything outside `Android/data` and
`Android/obb`. Shizuku is needed only because Android 11+ hides other apps' `Android/data` from normal apps, even with
All-files access.

Nothing on this page has been confirmed on a real device yet (see `SHIZUKU_CAPABILITY_MATRIX.md`, section 8).

## Setup

1. Install Shizuku from its official page (F-Droid, GitHub or Play). CleanForge needs a Shizuku **server of version 11 or
   newer** and was written against the Shizuku API 13.1.5.
2. Start it. On Android 11+ use *Wireless debugging* inside the Shizuku app (no computer after the first pairing), or run
   the adb command it shows. Shizuku has to be started again after every reboot unless you use its root start.
3. Open CleanForge. The Shizuku card says what is wrong, if anything, and what to do. Tap **Grant permission** and allow.
4. The card then lists what Shizuku can do **on this phone**: one line per operation, with its status, when it was
   checked, and why. Tap **Check again** after changing anything in Shizuku.

If you choose "deny and don't ask again", Android will not show the dialog again. Open the Shizuku app, find CleanForge in
its list of authorized apps and allow it there. CleanForge detects this state and shows an *Open Shizuku* button instead
of a button that would do nothing.

## What the Shizuku card can say

| Card | Meaning | What to do |
|---|---|---|
| Shizuku is optional | not installed | install and start it, or ignore it |
| Shizuku is not running | installed, stopped | start it, then **Check again** |
| Shizuku permission needed | running, not allowed | **Grant permission** |
| Shizuku permission was blocked | "don't ask again" | allow CleanForge inside the Shizuku app |
| Shizuku disconnected | the service stopped | start it again |
| Shizuku is too old | server older than v11 | update the Shizuku app |
| Shizuku problem | Shizuku reported an error | read the message; **Check again** |
| Running and CleanForge may use it | binder alive and allowed | read the per-operation lines below it |

"Running and allowed" is **not** "everything works". The lines below it are the real answer:

| Line | Meaning |
|---|---|
| Verified | it worked in this session |
| Ready, not proven yet | every prerequisite that can be checked without deleting is in place; deletions are never tried just to test them |
| Needs permission | see above |
| Unavailable right now | Shizuku or its service is not usable at the moment |
| Not supported on this device | for example the system refuses the Shizuku shell access to `Android/data` |

## Messages during a scan or a clean

| Message | Meaning |
|---|---|
| "<scanner>: <reason>" (skipped) | the scanner needs an operation that is not usable; the reason is that operation's own |
| "<scanner> is incomplete: …" | it ran but left something out (list cut off, apps it could not classify, folders it could not measure, service stopped early). **An empty result next to this message means "could not check", not "nothing to clean".** |
| "Already gone" | the folder was looked up successfully and does not exist |
| "The Shizuku service could not be reached" | the item was **not** touched and **not** assumed gone |
| "Not available right now: …" | the item was skipped because its operation is not usable |
| Deleted / partly / unconfirmed / skipped / failed | the five outcomes; "unconfirmed" means it may have happened but could not be checked afterwards; scan again |
| Stop button | ends the run after the item in progress; the report lists finished items, the one that was running (unconfirmed) and the ones not attempted |

## Compatibility

| Item | Support |
|---|---|
| Android | the app's minimum is Android 11 (API 30) |
| Shizuku server | 11 or newer |
| Shizuku API in the app | 13.1.5 |
| Modes | adb/shell (uid 2000) and root (uid 0); the service only accepts the four operations either way |
| Storage | primary user's shared storage `/storage/emulated/0` only |
| Other users, work profiles, SD cards | not covered |
| App updates | the service is versioned with the app; a stale service is replaced and the protocol version is checked on every bind |
| Manufacturer builds (including Vivo Funtouch OS) | **unverified**: whether the shell may list `Android/data` differs between Android versions and vendors; the probe reports it per device |

## Timeouts and limits

| What | Limit |
|---|---|
| bind the service | 6 s |
| stat / list / measure | 10 s / 20 s / 60 s per call |
| delete one item | 300 s |
| listing | at most 20,000 names and about 120,000 characters per reply |
| measure / delete walk | 200,000 entries, 32 levels (measure) and 48 levels (delete) |
| parallel binder reads | 2; deletions run one at a time |

A call that outlives its limit makes CleanForge restart the Shizuku service (which also frees the stuck call) and report
the item as confirmed or unconfirmed from what can be seen afterwards. Deletions are never repeated automatically.
