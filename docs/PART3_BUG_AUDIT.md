# Part 3 audit: bug list

Scope: files changed on 2026-10-12 (Part 3 scanners, validator, Part 4 RAM code, DI, nav, related tests/docs) plus the few
dependencies needed to check them (`Scanner`, `FileReadBackend`, `FsResult`, `PathRules`, `CleaningEngine`, `ScanRepository`).

**Verification status: NOT compiled, NOT run.** This sandbox has no Kotlin compiler, Gradle or network. Checked by reading
the real APIs, brace-balance checks, and tracing each path by hand. Run `./gradlew testDebugUnitTest` before trusting it.

## Bugs found and fixed

| # | Sev | Where | Bug | Fix |
|---|-----|-------|-----|-----|
| 1 | Blocker | 5 scanners | Passed `isPreselected = false` to `ScanItem`, where it is a derived property: compile error | Removed; new `ForensicFolderScanner` base builds items once |
| 2 | Blocker | 5 scanners | Used a nullable `list()`/`lstat()` API that no longer exists (`FsResult` now): compile error | Rewritten against `FileReadBackend` + `FsResult` |
| 3 | Blocker | `AppModule` | Missing imports for `ActivityManager`, `MemoryInfoProvider`, `ProcMemInfoProvider`: compile error | Imports added; `ActivityManager` binding replaced by `BackgroundProcessKiller` |
| 4 | Blocker | `ClearMemoryUseCaseTest` | Imported `org.mockito.kotlin` (not a dependency): breaks compilation of every unit test | Rewritten with fakes, no new dependency |
| 5 | High | `AppModule` | Part 3 claimed the scanners were registered; none was | `WhatsAppScanner`, `TelegramScanner` registered |
| 6 | High | `SafetyValidator` step 5 | `PathRules.isProtectedZone` treats `Android/media` as personal, so every WhatsApp/Telegram item was always refused | Exempt only allowlisted forensic files in `Android/media`; DCIM, Download etc. still refuse |
| 7 | High | scanners + engine | All items had `requiresShizuku = true`, but the Shizuku service reads only `Android/data`/`obb`, deletes only cache contents or uninstalled-app folders, and `toMaintenanceRequest` returns null for FORENSIC_MEDIA: nothing could be listed or deleted | WhatsApp/Telegram: `Requirement.ALL_FILES_ACCESS`, `requiresShizuku = false`, deleted by the app process |
| 8 | High | `ClearMemoryUseCaseTest` | Even with Mockito it would fail: use case called `isCurrentlyInstalled` (mock returns false), tests stubbed `installState`; "freed" assertion was case-sensitive | Use case uses `installState`; tests rewritten |
| 9 | High | nav | RAM screen had no route (CHANGELOG said it did) | "Memory" tab added |
| 10 | Medium | `SafetyValidator` | `owningPackage` only null-checked, never compared with the package in the path | Must equal `c[2]` |
| 11 | Medium | `SafetyValidator` | Allowlist accepted nested paths (e.g. `Databases/x/msgstore-*`, `.Statuses/a/b`) | Single direct-child file of one exact folder only |
| 12 | Medium | `WhatsAppScanner` | "Sent" media labelled "Already in Gallery", HIGH/SAFE with no check; may be the only copy of an edited/forwarded file. Chat backups labelled SAFE | Sent: MEDIUM/ELEVATED with honest text; backups ELEVATED |
| 13 | Medium | all scanners | `runCatching` swallowed every error, including `CancellationException`; unreadable folder = silently "nothing found" (breaks the `incompleteReasons` contract) | Explicit `FsResult` handling, `ensureActive()`, `incompleteReasons()` |
| 14 | Medium | `WhatsAppScanner` | Only the first installed variant scanned | Both WhatsApp and Business scanned |
| 15 | Medium | scanners, `ClearMemoryUseCase` | Install state via bare `isCurrentlyInstalled` (project policy: tri-state `installState`) | `installState`; UNKNOWN reported/skipped |
| 16 | Medium | `ClearMemoryUseCase` | `processesTouched` counted packages asked, not processes; note said "Freed 0 MB" when memory was unreadable; would trim launcher/keyboard/system apps | `packagesRequested`, `measured`, honest note; system apps and CleanForge skipped |
| 17 | Medium | `RamScreen` | Polled every 2 s while the app was in the background (composition-scoped); unreadable meminfo showed "Reading…" forever | Lifecycle-scoped polling; `readFailed` state |
| 18 | Low | `MemInfoParser` | `MemTotal: 0` produced a snapshot that reads as CRITICAL | Returns null |
| 19 | Low | `RamViewModel` | `isClearing` could stay true if the clear failed or was cancelled | `try/finally` |
| 20 | Low | `RamScreen` | Duplicate private `formatBytes`, `String.format` without Locale | Uses `ui.formatBytes` |
| 21 | Low | docs | README/SECURITY said Shizuku is required for forensics; CHANGELOG lists Part 5 as done and "RAM card on Home" (absent) | README, SECURITY, CHANGELOG corrected |

New tests: `ForensicScannersTest`, `ForensicValidatorTest`; extra cases in `MemInfoParserTest` and `PolicyGuardTest`
(scanners/RAM use `installState`, no `isPreselected =` in scanners, only deletable scanners registered).

## Not fixed (open)

1. **TikTok, Twitter/X, Instagram scanners are dormant.** They now compile and are tested, but are deliberately not
   registered: the Shizuku service has no single-file delete, so their items could only ever be skipped, and `CacheScanner`
   already offers `Android/data/<pkg>/cache`. Making them real needs a new privileged operation (`PrivilegedOperation`,
   AIDL id 13+, `FileServiceCore`, `WireCodec`, `CapabilityRegistry`, tests). Only TikTok `files/.music` is genuinely new.
2. **Device verification.** I believe `Android/media` is readable and deletable with All-files access (the manifest and
   `StandardFileBackend` docs say the same), but this has not been confirmed on the Vivo Y11 (Android 11).
3. **WhatsApp Business paths.** As far as I know it may use `WhatsApp Business` instead of `WhatsApp` in its folder names.
   The scanner uses the `WhatsApp` layout, so on such a device it finds nothing (fail-safe, but useless). Confirm on a device.
4. **Files only.** Sub-folders are reported, never proposed. Fine for WhatsApp/Telegram folders; most `Android/data/*/cache`
   entries are folders.
5. **No end-to-end engine test** for a WhatsApp item (scan, validate, plan, delete via `processStandard`). I traced it by
   reading (`SafeDeleter` has no path policy of its own) but did not add a test.
6. **Version numbers.** `versionName = "0.2.1"`, `versionCode = 3` against CHANGELOG 0.5.0/1.0.0. Left for the owner.
7. **Sequencing.** The Part 3 zip already contains Part 4 (RAM) code; audited as asked. Part 5 is not done, despite the CHANGELOG.
8. `PART_3.md` is unchanged. Its inaccuracies: it says Shizuku reads `Android/media`; it lists `WhatsApp Animated Gifs/*`
   (code allows only `.../Sent/*`); it says the scanners are provided through Hilt (they were not).
