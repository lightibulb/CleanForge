# CleanForge

A free, open-source, offline-first storage cleaner for Android 11+.
Made by **Aman**.

No ads. No subscription. No telemetry, analytics or accounts.
No internet permission. No root.

---
[![Download APK](https://img.shields.io/badge/Download-Android_APK-2ea44f?logo=android&logoColor=white)](https://github.com/lightibulb/CleanForge/releases/download/v2.5.4/CleanForge-debug.apk)

## Features

### 🔍 Evidence-Based Cleaning
Every item CleanForge flags carries **proof** of why it's safe to delete.
Filename or extension alone is never accepted as evidence.

| Scanner | What it finds | Evidence required |
|---------|--------------|-------------------|
| App Caches | `Android/data/<pkg>/cache` contents | Package installed + exact path match |
| Leftovers | Folders of uninstalled apps | Package unknown to OS + age ≥ 14 days |
| Thumbnails | `DCIM/.thumbnails` | Only small files, no sub-folders |
| Incomplete Downloads | `.crdownload`, `.part`, `.partial` | Age ≥ 7 days + Download folder only |
| Log Files | `*.log` in Downloads | Text content verified + age ≥ 30 days |
| Large Files | Files over threshold | Top-N by size, never pre-selected |
| Duplicates | Same content, different paths | Staged hashing, user picks what goes |
| APK Installers | `.apk` files | Platform parser reads manifest |
| Empty Folders | Verifiably empty directories | `rmdir` semantics only |

### 🔬 Storage Forensics (Part 3)
Deep, app-specific scanning for known cache and media patterns.
All forensic items are **never pre-selected** — the user always decides.

| App | What's scanned | Safety rule |
|-----|---------------|-------------|
| WhatsApp / WhatsApp Business | `.Statuses` (>24h), `Sent` folders, old DB backups (>7d) | Shizuku required, FILE mode only |
| Telegram | Video/Audio/Images/Documents cache (>14d) | Shizuku required, FILE mode only |
| TikTok / TikTok Lite | Video cache, `.music` cache (>7d) | Shizuku required, FILE mode only |
| Twitter/X / X Lite | Media cache (>7d) | Shizuku required, FILE mode only |
| Instagram / Instagram Lite | Media cache (>14d) | Shizuku required, FILE mode only |

### 📱 Shizuku Integration (Part 2)
On Android 11+, `Android/data` and `Android/obb` are invisible to normal apps.
CleanForge uses [Shizuku](https://shizuku.rikka.app/) as a **user-service** (not root) to:

- **Read** `Android/data/` and `Android/obb/` for scanning
- **Clear** app caches (`CONTENTS_ONLY` on exact `cache/` path)
- **Remove** leftovers of uninstalled apps (`TREE` on exact package folder)

Without Shizuku, those scanners are skipped with a visible notice.
CleanForge **never** claims Shizuku grants root or unrestricted access.

### 🧠 RAM Monitoring (Part 4)
Honest, transparent memory information. No fake "boost" claims.

- Reads `/proc/meminfo` (no permission needed)
- Shows total, used, available, and cached memory
- Color-coded pressure indicator (LOW → CRITICAL)
- Optional "Clear background apps" action using `KILL_BACKGROUND_PROCESSES`
  - Measures the **actual** freed bytes, never estimates
  - Tells you the truth: Android may reload apps, using more battery

### 🛡️ Safety Model
- **Fingerprint verification**: Every file is `lstat`-ed at scan time and re-checked before deletion
- **Symlink protection**: Never follows symlinks; symlink roots are refused
- **Mount boundaries**: Never crosses filesystem boundaries
- **Depth/entry limits**: Bounded walks prevent runaway recursion
- **No shell**: Zero `Runtime.exec`, `ProcessBuilder`, or `sh -c` anywhere
- **No `deleteRecursively`**: All deletion goes through `SafeDeleter`
- **Personal data zones**: DCIM, Pictures, Movies, Music, Documents, Download, WhatsApp, Telegram — only individually reviewed files, never pre-selected
- **Messaging apps protected**: WhatsApp, Telegram, Signal, Messenger, etc. are never pre-selected

---

## Architecture

Enforced by types: scanners emit evidence-bearing `ScanItem`s, `PlanBuilder` yields a
`CleaningPlan` with refusals and reasons, `confirmedByUser()` is the only path to a
`ConfirmedPlan`, and `CleaningEngine` accepts nothing else.

| Layer | Package | Responsibility |
|-------|---------|---------------|
| Filesystem | `core.fs` | `FsOps`, `SafeDeleter`, `PathRules`, `StorageWalker` |
| Safety | `core.safety` | `SafetyValidator` — lexical + policy gate |
| Scanners | `core.scanner` | 9 base + 5 forensic scanners |
| Cleaning | `core.cleaning` | `PlanBuilder`, `CleaningEngine` |
| Backend | `core.backend` | `FileOperationBackend`, `PackageInventory`, `Capabilities` |
| Shizuku | `core.shizuku` | `ShizukuFileServiceConnector`, AIDL adapters |
| Privileged | `core.privileged` | `FileServiceCore`, `WireCodec`, `PrivilegedExecutor` |
| Memory | `core.memory` | `RamSnapshot`, `MemInfoParser`, `MemoryInfoProvider` |
| Domain | `domain.usecase` | `ScanDevice`, `ExecuteCleaningPlan`, `ObserveRam`, `ClearMemory` |
| DI | `di` | Hilt modules |
| UI | `ui` | Compose screens, ViewModels, Material 3 |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full diagram and
[ARCHITECTURE_PLAN.md](ARCHITECTURE_PLAN.md) for the forward-looking proposal (not yet built).

---

## Permissions

| Permission | Why |
|-----------|-----|
| `MANAGE_EXTERNAL_STORAGE` | Read/delete user-selected files in shared storage |
| `QUERY_ALL_PACKAGES` | Know which apps are installed (never guess from folder names) |
| `KILL_BACKGROUND_PROCESSES` | Honest RAM clear (Part 4). Only trims reclaimable processes |

**No `INTERNET`. No `ACCESS_NETWORK_STATE`. No analytics. No ads. Ever.**
Enforced by `PolicyGuardTest`.

---

## Requirements

- Android 11 (API 30) or later
- [Shizuku](https://shizuku.rikka.app/) (optional — needed for `Android/data` / `Android/obb`)
- `MANAGE_EXTERNAL_STORAGE` granted in Settings → Special access

## Build

See [BUILD.md](BUILD.md).

## Security

See [SECURITY.md](SECURITY.md).

## License

CleanForge is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License v3.0 or later** as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful, but **WITHOUT ANY WARRANTY**; without even the implied warranty of **MERCHANTABILITY** or **FITNESS FOR A PARTICULAR PURPOSE**. See the [GNU General Public License](https://www.gnu.org/licenses/gpl-3.0.html) for more details.

*Created and owned by **Aman**.*

---

## Credits

- **Created & owned by**: Aman
- **Shizuku**: [RikkaApps](https://github.com/RikkaApps/Shizuku)
