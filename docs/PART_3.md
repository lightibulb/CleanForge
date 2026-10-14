# Part 3: Storage Forensics — Full Summary

**Status:** Complete.
**Scope:** Deep, app-specific scanning of known cache and media patterns for WhatsApp, Telegram, TikTok, Twitter/X, and Instagram.
**Rule of thumb:** forensics is *narrower* than a general walk, never wider. Every finding is allowlisted per-package and per-subfolder, never pre-selected, and always requires Shizuku + explicit user confirmation.

---

## 1. Goal and philosophy

On Android 11+, an app's media and cache live under `Android/media/<pkg>` and `Android/data/<pkg>`, which are invisible to normal apps and only reachable through the Shizuku user-service (Part 2). Part 3 adds **targeted forensic scanners** that understand the *specific* junk patterns these apps create, instead of blindly walking those trees.

Three non-negotiable principles carried over from the safety model:

- **Evidence over name.** A folder being called "cache" or "Sent" is not enough on its own — the scanner must match an exact, known app-specific path shape.
- **Never pre-selected.** All forensic items have `isPreselected = false`. The user always ticks what to delete.
- **Fail closed.** If the app isn't installed, the folder can't be listed, or the path isn't on the allowlist, nothing is flagged and nothing is deleted.

---

## 2. What was added

| Item | Type | Path |
|------|------|------|
| `WhatsAppScanner` | NEW | `app/src/main/java/com/cleanforge/core/scanner/WhatsAppScanner.kt` |
| `TelegramScanner` | NEW | `app/src/main/java/com/cleanforge/core/scanner/TelegramScanner.kt` |
| `TikTokScanner` | NEW | `app/src/main/java/com/cleanforge/core/scanner/TikTokScanner.kt` |
| `TwitterXScanner` | NEW | `app/src/main/java/com/cleanforge/core/scanner/TwitterXScanner.kt` |
| `InstagramScanner` | NEW | `app/src/main/java/com/cleanforge/core/scanner/InstagramScanner.kt` |
| `CleaningCategory.FORENSIC_MEDIA` | CHANGED | `app/src/main/java/com/cleanforge/core/model/Enums.kt` |
| `SafetyValidator.isKnownForensicPath()` | CHANGED | `app/src/main/java/com/cleanforge/core/safety/SafetyValidator.kt` |
| `ScanItem.reasonForFlagging` | CHANGED | `app/src/main/java/com/cleanforge/core/model/ScanItem.kt` |
| `ProtectedApps.MESSAGING` | NEW | `app/src/main/java/com/cleanforge/core/model/ScanItem.kt` |
| This document | NEW | `docs/PART3_STORAGE_FORENSICS.md` |

---

## 3. The five forensic scanners

Every scanner implements the existing `Scanner` interface and sets:
`requirement = Requirement.SHIZUKU`, `mode = ScanMode.ON_DEMAND`, `deleteMode = DeleteMode.FILE`, `requiresShizuku = true`, `isPreselected = false`.

| Scanner | Package(s) | Targets | Age threshold | Confidence | Risk |
|---------|-----------|---------|---------------|------------|------|
| `WhatsAppScanner` | `com.whatsapp`, `com.whatsapp.w4b` | `.Statuses`, `Sent` folders, old DB backups | 24h / 0 / 7d | HIGH | SAFE |
| `TelegramScanner` | `org.telegram.messenger` | Video, Audio, Images, Documents cache | 14 days | MEDIUM | ELEVATED |
| `TikTokScanner` | `com.zhiliaoapp.musically`, `.go` | `cache/`, `files/.music` | 7 days | HIGH | SAFE |
| `TwitterXScanner` | `com.twitter.android`, `.lite` | `cache/` | 7 days | HIGH | SAFE |
| `InstagramScanner` | `com.instagram.android`, `.lite` | `cache/` | 14 days | MEDIUM | SAFE |

### Per-scanner detail

**WhatsApp / WhatsApp Business**
- `.Statuses/*` older than 24 hours (ephemeral status media that often lingers).
- `WhatsApp Video/Sent/*`, `WhatsApp Images/Sent/*`, `WhatsApp Animated Gifs/Sent/*` — copies of things you already sent, which also exist in your gallery. Flagged regardless of age (threshold 0).
- `Databases/msgstore-*` backups older than 7 days, **keeping the newest one** (sorted by mtime, `drop(1)`).

**Telegram**
- `Telegram Video/*`, `Telegram Audio/*`, `Telegram Images/*`, `Telegram Documents/*` older than 14 days. Marked `ELEVATED` because a user may have saved things here intentionally.

**TikTok / TikTok Lite**
- `cache/*` and `files/.music/*` older than 7 days.

**Twitter/X / X Lite**
- `cache/*` older than 7 days.

**Instagram / Instagram Lite**
- `cache/*` older than 14 days. Marked `MEDIUM` confidence because Instagram's cache layout can vary by version.

---

## 4. The path allowlist (the safety core)

`SafetyValidator.isKnownForensicPath()` is the single gate that decides whether a `FORENSIC_MEDIA` item is even allowed. It is a **strict allowlist**, not a pattern match. Anything not listed here is refused.

### Allowed under `Android/media/`
Android/media/com.whatsapp/WhatsApp/Media/.Statuses/*
Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent/*
Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/*
Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs/*
Android/media/com.whatsapp/WhatsApp/Databases/msgstore-*
Android/media/org.telegram.messenger/Telegram/Telegram Video/*
Android/media/org.telegram.messenger/Telegram/Telegram Audio/*
Android/media/org.telegram.messenger/Telegram/Telegram Images/*
Android/media/org.telegram.messenger/Telegram/Telegram Documents/*

### Allowed under `Android/data/`

Android/data/com.zhiliaoapp.musically/cache/*
Android/data/com.zhiliaoapp.musically/files/.music/*
Android/data/com.twitter.android/cache/*
Android/data/com.instagram.android/cache/*


(Lite variants `com.zhiliaoapp.musically.go`, `com.twitter.android.lite`, `com.instagram.lite` are included.)

### Structural rules in `isKnownForensicPath()`
- Path must have at least 5 components and must start with `Android`.
- The second component must be `media` or `data`; the third must be a known package.
- Matching is case-insensitive (shared storage is case-insensitive).
- A match requires the full prefix **plus at least one extra component** (you can flag a file *inside* an allowed folder, never the folder itself).
- `DeleteMode` for `FORENSIC_MEDIA` is forced to `FILE` — forensic items never trigger recursive deletion.

---

## 5. Code changes

### `Enums.kt`
Added the category:
```kotlin
FORENSIC_MEDIA("App-specific media & caches", false)   // isQuick = false (on-demand only)



Added reasonForFlagging: String — the evidence-based explanation shown to the user ("why is this removable").
Added ProtectedApps.MESSAGING, the set of packages that are never pre-selected and get an extra caution layer:


object ProtectedApps {
    val MESSAGING: Set<String> = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
        "org.telegram.plus", "org.thoughtcrime.securesms", "com.facebook.orca", "com.instagram.android",
        "com.snapchat.android", "com.viber.voip", "com.google.android.apps.messaging"
    )
}







FORENSIC_MEDIA is allowed in Android/media and Android/data only via isKnownForensicPath().
The personal-folder protection was extended so forensic rules can never apply inside personal zones.
A FORENSIC_MEDIA item without an owningPackage is refused.
6. Architecture integration
Scanners plug into the existing Scanner interface and are provided through Hilt (di/AppModule.kt), receiving the Shizuku-backed FileOperationBackend and PackageInventory.
They emit Flow<List<ScanItem>> in batches of BATCH_SIZE (50) to bound memory on low-end devices.
Filesystem reads go through FileOperationBackend (backed by Shizuku for Android/media and Android/data), never raw java.io.File.
Each item carries an lstat fingerprint so the CleaningEngine re-verifies identity before deletion.
7. Security invariants
No wildcard Android/data or Android/media access. Paths are allowlisted per-package, per-subfolder.
All forensic items: isPreselected = false, requiresShizuku = true, deleteMode = FILE.
Messaging apps in ProtectedApps.MESSAGING are never pre-selected and get an extra caution layer.


Forensic rules never apply inside personal folders (DCIM, Pictures, Download, etc.).
The pipeline is unchanged: SCAN → CLASSIFY → VALIDATE → PLAN → USER CONFIRMATION → REVALIDATE → DELETE → VERIFY → REPORT. Forensic items still require explicit confirmation.
8. What is NOT scanned
Android/data/<pkg>/files/ (app data, not cache) — except TikTok's .music.
Android/media/<pkg>/ outside the allowlisted subfolders.
Any path not matching the exact allowlist.
Internal app data (/data/data/) — impossible without root, and CleanForge never attempts it.
9. Testing
SafetyValidatorTest should be extended with allowlist cases:
Allowed forensic paths pass.
Non-allowlisted Android/media / Android/data paths are refused.
FORENSIC_MEDIA without owningPackage is refused.
Folder-itself paths (no extra component) are refused.
Each scanner should have a unit test over the fake/in-memory filesystem verifying it only flags its known subfolders and respects the age threshold.
Messaging-app items must assert isPreselected == false.
10. Known limitations / residual risks
The allowlist is static. If an app changes its folder layout, the scanner simply finds nothing (fail-safe, but silent).
Instagram/TikTok cache layouts vary by version and region; thresholds are conservative.
"Sent" folder detection assumes the original still exists elsewhere; CleanForge does not verify that, so it is the user's call (hence never pre-selected).
Not verified on a real device; behaviour of Android/media via Shizuku on Android 11 follows the documented model but must be confirmed on hardware.
11. Changelog entry (0.4.0)
Added: 5 forensic scanners, FORENSIC_MEDIA category, SafetyValidator.isKnownForensicPath(), ProtectedApps.MESSAGING, ScanItem.reasonForFlagging, this document.
Changed: SafetyValidator allowlist + personal-folder extension; Enums category; ScanItem field.
Security: per-package/per-subfolder allowlist, no wildcard access, all items non-preselected + Shizuku + FILE mode.


---

That's the full Part 3 record — philosophy, scanners, allowlist, code changes, security invariants, boundaries, testing, and limitations. 🥀

Want me to do the same **full summary** for Part 4 (RAM Monitoring) next, twin? 💔