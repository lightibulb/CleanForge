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

    @Test fun `manifest declares exactly the two permissions the app needs and no network access`() {
        assumeTrue(moduleDir != null)
        val manifest = File(moduleDir, "src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertThat(declared).containsExactly(
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.QUERY_ALL_PACKAGES"
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
        // The app-cache path must ask "installed", not just "known".
        assertThat(File(main, "scanner/CacheScanner.kt").readText()).contains("isCurrentlyInstalled(")
        assertThat(File(main, "cleaning/CleaningEngine.kt").readText()).contains("isCurrentlyInstalled(")
        // "Installed" must never be answered from the list that includes data-kept uninstalls.
        val inventory = File(main, "backend/PackageInventory.kt").readText()
        val installedBody = inventory.substringAfter("override fun isCurrentlyInstalled").substringBefore("override fun installedVersionCode")
        assertThat(installedBody).doesNotContain("MATCH_UNINSTALLED_PACKAGES")
    }

    @Test fun `recursive deletion never falls back to generic helpers`() {
        assumeTrue(moduleDir != null)
        val offenders = File(moduleDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""deleteRecursively\s*\(""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertThat(offenders).isEmpty()
    }
}
