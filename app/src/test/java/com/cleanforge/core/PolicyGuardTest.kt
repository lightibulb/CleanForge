package com.cleanforge.core

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Source/manifest-level guards: they fail the build if someone reintroduces a shell call or a network path. */
class PolicyGuardTest {

    private val moduleDir: File? = listOf(File("."), File("app")).firstOrNull { File(it, "src/main/AndroidManifest.xml").exists() }

    @Test fun `no source file builds or runs shell commands`() {
        assumeTrue(moduleDir != null)
        val forbidden = listOf(
            Regex("""Runtime\s*\.\s*getRuntime"""),
            Regex("""\bProcessBuilder\b"""),
            Regex("""\.exec\s*\("""),
            Regex("""Shizuku\s*\.\s*newProcess"""),
            Regex("""\bsh\s+-c\b""")
        )
        val offenders = File(moduleDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> forbidden.filter { it.containsMatchIn(f.readText()) }.map { "${f.name}: ${it.pattern}" } }
            .toList()
        assertThat(offenders).isEmpty()
    }

   @Test fun `manifest declares exactly the permissions the app needs and no network access`() {
    assumeTrue(moduleDir != null)
    val manifest = File(moduleDir, "src/main/AndroidManifest.xml").readText()
    val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
    // KILL_BACKGROUND_PROCESSES is Part 4's HONEST RAM clear. It only trims processes Android
    // already considers reclaimable. It is NOT root and NOT a "boost". Deliberate and documented,
    // not permission creep.
    assertThat(declared).containsExactly(
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.KILL_BACKGROUND_PROCESSES"
    )
    assertThat(manifest).doesNotContain("android.permission.INTERNET")
    assertThat(manifest).doesNotContain("ACCESS_NETWORK_STATE")
    assertThat(manifest).contains("android:allowBackup=\"false\"")
    }

    @Test fun `build files contain no analytics ads crash-reporting or unvetted repositories`() {
        assumeTrue(moduleDir != null)
        val files = listOf(
            File(moduleDir, "build.gradle.kts"),
            File(moduleDir, "../build.gradle.kts"),
            File(moduleDir, "../settings.gradle.kts"),
            File(moduleDir, "../gradle/libs.versions.toml")
        ).filter { it.exists() }
        val banned = listOf("firebase", "crashlytics", "admob", "play-services-ads", "sentry", "appcenter",
            "mixpanel", "amplitude", "analytics", "xposed", "jitpack")
        val hits = files.flatMap { f -> banned.filter { f.readText().lowercase().contains(it) }.map { "${f.name}: $it" } }
        assertThat(hits).isEmpty()
    }

    @Test fun `the confirmation dialog renders full evidence and never a bare file name`() {
        assumeTrue(moduleDir != null)
        val dialogs = File(moduleDir, "src/main/java/com/cleanforge/ui/components/Dialogs.kt").readText()
        assertThat(dialogs).contains("confirmationEvidence()")
        assertThat(dialogs).doesNotContain("displayName")
        assertThat(dialogs).doesNotContain("and \${plan.items.size - ")
    }

    @Test fun `installed and known packages stay two separate questions`() {
        assumeTrue(moduleDir != null)
        val main = File(moduleDir, "src/main/java/com/cleanforge/core")
        // Every decision about a package's install state goes through the tri-state answer, never a bare boolean:
        // a failed query is UNKNOWN, and "not installed" is only ever an authoritative answer.
        assertThat(File(main, "scanner/CacheScanner.kt").readText()).contains("installState(")
        assertThat(File(main, "scanner/CorpseScanner.kt").readText()).contains("installState(")
        assertThat(File(main, "cleaning/CleaningEngine.kt").readText()).contains("installState(")
        for (f in listOf("scanner/CacheScanner.kt", "scanner/CorpseScanner.kt", "cleaning/CleaningEngine.kt")) {
            assertThat(File(main, f).readText()).doesNotContain("isCurrentlyInstalled(")
        }
        val inventory = File(main, "backend/PackageInventory.kt").readText()
        // "Installed" must never be answered from the list that includes data-kept uninstalls.
        val installedBody = inventory.substringAfter("override fun isCurrentlyInstalled").substringBefore("override fun installedVersionCode")
        assertThat(installedBody).doesNotContain("MATCH_UNINSTALLED_PACKAGES")
        // The authoritative lookup: INSTALLED requires FLAG_INSTALLED, a missing package is told apart from a failed query.
        val stateBody = inventory.substringAfter("override fun installState").substringBefore("override fun archiveInfo")
        assertThat(stateBody).contains("FLAG_INSTALLED")
        assertThat(stateBody).contains("NameNotFoundException")
        assertThat(stateBody).contains("InstallState.UNKNOWN")
        assertThat(stateBody.indexOf("NameNotFoundException")).isLessThan(stateBody.indexOf("catch (e: Exception)"))
    }

    // ---------- Part 2: the privileged surface stays small and shell-free ----------

    @Test fun `the privileged service has no shell, no process spawning and no generic delete`() {
        assumeTrue(moduleDir != null)
        val core = File(moduleDir, "src/main/java/com/cleanforge/core")
        val privilegedSources = listOf(
            "privileged/FileServiceCore.kt", "privileged/WireCodec.kt", "shizuku/ShizukuFileService.kt",
            "shizuku/AidlPrivilegedFileApi.kt", "privileged/PrivilegedExecutor.kt"
        ).map { File(core, it).readText() }
        for (text in privilegedSources) {
            assertThat(text).doesNotContain("Runtime.getRuntime")
            assertThat(text).doesNotContain("ProcessBuilder")
            assertThat(text).doesNotContain(".exec(")
            assertThat(text).doesNotContain("Shizuku.newProcess")
            assertThat(text).doesNotContain("deleteRecursively")
        }
    }

    @Test fun `the Shizuku read backend has no delete and the executor accepts requests, not paths`() {
        assumeTrue(moduleDir != null)
        val core = File(moduleDir, "src/main/java/com/cleanforge/core")
        assertThat(File(core, "backend/ShizukuFileBackend.kt").readText()).doesNotContain("fun delete")
        val executor = File(core, "privileged/PrivilegedExecutor.kt").readText()
        val iface = executor.substringAfter("interface PrivilegedExecutor").substringBefore("data class ExecutionPolicy")
        assertThat(iface).contains("suspend fun execute(request: MaintenanceRequest)")
        assertThat(iface).doesNotContain("fun delete")
        assertThat(iface).doesNotContain("mode: DeleteMode")
    }

    @Test fun `the AIDL contract only grows, and existing transaction ids keep their meaning`() {
        assumeTrue(moduleDir != null)
        val aidl = File(moduleDir, "src/main/aidl/com/cleanforge/shizuku/IFileService.aidl").readText()
        val ids = Regex("""(\w+)\s*\([^)]*\)\s*=\s*(\d+)\s*;""").findAll(aidl).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertThat(ids).containsEntry("destroy", 16777114)
        assertThat(ids).containsEntry("uid", 1)
        assertThat(ids).containsEntry("lstat", 2)
        assertThat(ids).containsEntry("list", 3)
        assertThat(ids).containsEntry("directorySize", 4)
        assertThat(ids).containsEntry("delete", 5)
        assertThat(ids).containsEntry("apiVersion", 6)
        assertThat(ids).containsEntry("statEx", 7)
        assertThat(ids).containsEntry("listEx", 8)
        assertThat(ids).containsEntry("sizeEx", 9)
        assertThat(ids).containsEntry("deleteEx", 10)
        assertThat(ids).containsEntry("cancelCurrent", 11)
        assertThat(ids).containsEntry("setProtectedPackages", 12)
        // No two methods share an id.
        assertThat(ids.values.toSet()).hasSize(ids.size)
        // Array arguments need an explicit direction.
        assertThat(aidl).contains("in String[] packages")
    }

    @Test fun `no UI or ViewModel code calls the Shizuku API directly or blocks the main thread on it`() {
        assumeTrue(moduleDir != null)
        val ui = File(moduleDir, "src/main/java/com/cleanforge/ui")
        val offenders = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("rikka.shizuku.Shizuku") }
            .map { it.name }.toList()
        // The only UI file allowed to touch the Shizuku package is the card that opens the Shizuku app by package name.
        assertThat(offenders).isEmpty()
        val vm = File(ui, "CleanViewModel.kt").readText()
        // refresh() makes binder calls, so it may only run inside an IO coroutine.
        assertThat(Regex("""viewModelScope\.launch\(Dispatchers\.IO\)\s*\{\s*shizuku\.refresh\(\)""").containsMatchIn(vm)).isTrue()
        // ...and the view model must never call it from its init block or a main-thread callback.
        assertThat(Regex("""fun onResume\(\)\s*\{[^}]*shizuku\.refresh""").containsMatchIn(vm)).isFalse()
    }

    @Test fun `recursive deletion never falls back to generic helpers`() {
        assumeTrue(moduleDir != null)
        val offenders = File(moduleDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""deleteRecursively\s*\(""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertThat(offenders).isEmpty()
    }

    // ---------- Parts 3/4: audit regressions ----------

    @Test fun `forensic scanners and the RAM clear decide install state through the tri-state answer`() {
        assumeTrue(moduleDir != null)
        val main = File(moduleDir, "src/main/java/com/cleanforge")
        for (f in listOf("core/scanner/ForensicFolderScanner.kt", "domain/usecase/ClearMemoryUseCase.kt")) {
            val text = File(main, f).readText()
            assertThat(text).contains("installState(")
            assertThat(text).doesNotContain("isCurrentlyInstalled(")
        }
    }

    @Test fun `scanners never pass isPreselected, which is derived and never a constructor argument`() {
        assumeTrue(moduleDir != null)
        val scanners = File(moduleDir, "src/main/java/com/cleanforge/core/scanner")
        val offenders = scanners.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""isPreselected\s*=""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertThat(offenders).isEmpty()
    }

    @Test fun `only scanners whose items the engine can delete are registered`() {
        assumeTrue(moduleDir != null)
        val module = File(moduleDir, "src/main/java/com/cleanforge/di/AppModule.kt").readText()
        // The Shizuku service has no single-file delete: these would only ever be skipped at deletion.
        for (dormant in listOf("TikTokScanner(", "TwitterXScanner(", "InstagramScanner(")) {
            assertThat(module).doesNotContain(dormant)
        }
        assertThat(module).contains("WhatsAppScanner(")
        assertThat(module).contains("TelegramScanner(")
    }
}
