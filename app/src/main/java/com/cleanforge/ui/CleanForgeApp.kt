package com.cleanforge.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourpackage.cleanforge.data.Preferences
import com.yourpackage.cleanforge.data.SampleData
import com.yourpackage.cleanforge.data.ScanItem
import com.yourpackage.cleanforge.data.Verses
import com.yourpackage.cleanforge.ui.components.CounterHeading
import com.yourpackage.cleanforge.ui.components.DockItem
import com.yourpackage.cleanforge.ui.components.VerseHero
import com.yourpackage.cleanforge.ui.components.WarmDock
import com.yourpackage.cleanforge.ui.components.WarmFab
import com.yourpackage.cleanforge.ui.components.WarmToastHost
import com.yourpackage.cleanforge.ui.screens.AboutScreen
import com.yourpackage.cleanforge.ui.screens.CleanScreen
import com.yourpackage.cleanforge.ui.screens.ConfirmSheet
import com.yourpackage.cleanforge.ui.screens.EasterEggSheet
import com.yourpackage.cleanforge.ui.screens.MemoryScreen
import com.yourpackage.cleanforge.ui.screens.ProgressSheet
import com.yourpackage.cleanforge.ui.screens.ReportSheet
import com.yourpackage.cleanforge.ui.screens.ReviewScreen
import com.yourpackage.cleanforge.ui.theme.LocalWarmPalette
import com.yourpackage.cleanforge.ui.util.Haptic
import com.yourpackage.cleanforge.ui.util.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen(val key: String, val title: String, val subtitle: String) {
    Clean("clean", "CleanForge", "a quieter way to tidy up"),
    Review("review", "Review", "you decide what stays"),
    Memory("ram", "Memory", "honest, no boost claims"),
    About("about", "About", "open, private, honest"),
}

@Composable
fun CleanForgeApp(prefs: Preferences) {
    val palette = LocalWarmPalette.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val dark by prefs.dark.collectAsState(initial = false)
    val versesEnabled by prefs.verses.collectAsState(initial = true)

    var currentScreen by remember { mutableStateOf(Screen.Clean) }
    var toast by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    val scrolled by remember { derivedStateOf { scrollState.value > 20 } }
    val verseAlpha by remember { derivedStateOf { (1f - scrollState.value / 140f).coerceIn(0f, 1f) } }

    // Scan state
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(emptyList<ScanItem>()) }
    var notices by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    // RAM state
    var ramUsed by remember { mutableStateOf(3_200_000_000L) }
    val ramTotal = 6_000_000_000L
    var ramLastFreed by remember { mutableStateOf<String?>(null) }
    var ramClearing by remember { mutableStateOf(false) }

    // Sheets
    var showConfirm by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var showEgg by remember { mutableStateOf(false) }
    var progressDone by remember { mutableStateOf(0) }
    var progressFreed by remember { mutableStateOf(0L) }
    var reportFreed by remember { mutableStateOf(0L) }

    // 7-tap easter egg state
    var forgeTaps by remember { mutableStateOf(0) }
    var forgeLastTap by remember { mutableStateOf(0L) }

    // Reset scroll on tab change
    LaunchedEffect(currentScreen) {
        scrollState.scrollTo(0)
    }

    fun startScan(quick: Boolean) {
        if (scanning) return
        scanning = true
        scanned = false
        notices = emptyList()
        items = emptyList()
        selected = emptySet()
        haptics(Haptic.Medium)
        scope.launch {
            delay(1600)
            scanning = false
            scanned = true
            items = if (quick) SampleData.QUICK else SampleData.ON_DEMAND
            notices = if (quick) listOf("Shizuku is not running — Android/data and Android/obb were skipped.") else emptyList()
            haptics(Haptic.Success)
        }
    }

    fun toggleItem(path: String) {
        selected = if (selected.contains(path)) {
            haptics(Haptic.Tick); selected - path
        } else {
            haptics(Haptic.Light); selected + path
        }
    }

    fun requestClean() {
        if (selected.isEmpty()) return
        haptics(Haptic.Medium)
        showConfirm = true
    }

    fun confirmDelete() {
        showConfirm = false
        haptics(Haptic.Heavy)
        showProgress = true
        progressDone = 0
        progressFreed = 0L
        val total = items.filter { selected.contains(it.path) }.sumOf { it.sizeBytes }
        val count = selected.size
        scope.launch {
            for (i in 1..count) {
                delay(620)
                progressDone = i
                progressFreed = total * i / count
                haptics(Haptic.Tick)
            }
            delay(420)
            showProgress = false
            reportFreed = total
            showReport = true
            haptics(Haptic.Success)
            selected = emptySet()
            items = emptyList()
            scanned = false
        }
    }

    val quickItems = remember(items) { items.filter { it.category != "Large files" && it.category != "Duplicates" } }
    val onDemandItems = remember(items) { items.filter { it.category == "Large files" || it.category == "Duplicates" } }

    val selectedBytes = remember(selected, items) {
        items.filter { selected.contains(it.path) }.sumOf { it.sizeBytes }
    }

    val emptyStateVerse = remember {
        val seed = (System.currentTimeMillis() / 86_400_000).toInt()
        Verses.supportingFor(seed)
    }

    Box(Modifier.fillMaxSize().background(palette.bg)) {

        // Layer 1: Fixed verse behind everything
        if (versesEnabled) {
            VerseHero(
                verse = Verses.ANCHOR,
                scrollAlpha = verseAlpha,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 106.dp),
                delayMs = 350,
            )
        }

        // Layer 2: Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = if (versesEnabled) 250.dp else 108.dp)
                .padding(bottom = 160.dp),
        ) {
            when (currentScreen) {
                Screen.Clean -> CleanScreen(
                    freeBytes = 12_400_000_000L,
                    totalBytes = 64_000_000_000L,
                    scanning = scanning,
                    scanned = scanned,
                    items = quickItems,
                    notices = notices,
                    selected = selected,
                    onToggle = ::toggleItem,
                    onScan = { startScan(true) },
                    onOpenSettings = { toast = "Opening All-files access settings…" },
                    onCheckShizuku = { toast = "Checking Shizuku status…" },
                    emptyStateVerse = if (versesEnabled) emptyStateVerse else null,
                )
                Screen.Review -> ReviewScreen(
                    scanning = scanning,
                    scanned = scanned,
                    items = onDemandItems,
                    notices = emptyList(),
                    selected = selected,
                    onToggle = ::toggleItem,
                    onScan = { startScan(false) },
                )
                Screen.Memory -> MemoryScreen(
                    usedBytes = ramUsed,
                    totalBytes = ramTotal,
                    availableBytes = ramTotal - ramUsed,
                    lastFreedLabel = ramLastFreed,
                    clearing = ramClearing,
                    onClear = {
                        ramClearing = true
                        haptics(Haptic.Medium)
                        scope.launch {
                            delay(1500)
                            val freed = 400_000_000L + (Math.random() * 320_000_000).toLong()
                            ramUsed = (ramUsed - freed).coerceAtLeast(1_000_000_000L)
                            ramLastFreed = "Freed ${formatBytesShort(freed)}. Android may reload apps, using more battery."
                            ramClearing = false
                            haptics(Haptic.Success)
                        }
                    },
                )
                Screen.About -> AboutScreen(
                    dark = dark,
                    onToggleDark = {
                        scope.launch {
                            prefs.setDark(!dark)
                            toast = if (!dark) "Dark mode on" else "Light mode on"
                        }
                    },
                    onForgeTap = {
                        val now = System.currentTimeMillis()
                        if (now - forgeLastTap > 1400) forgeTaps = 0
                        forgeTaps += 1
                        forgeLastTap = now
                        if (forgeTaps >= 7) {
                            forgeTaps = 0
                            haptics(Haptic.Medium)
                            showEgg = true
                        }
                    },
                )
            }
        }

        // Layer 3: Frosted topbar
        FrostedTopbar(
            scrolled = scrolled,
            title = currentScreen.title,
            subtitle = currentScreen.subtitle,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Layer 4: FAB
        WarmFab(
            visible = selected.isNotEmpty() && (currentScreen == Screen.Clean || currentScreen == Screen.Review),
            count = selected.size,
            sizeLabel = formatBytesShort(selectedBytes),
            onClick = ::requestClean,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp),
        )

        // Layer 5: Dock
        WarmDock(
            items = Screen.values().toList().map { s ->
                DockItem(
                    key = s.key,
                    label = when (s) {
                        Screen.Clean -> "Clean"
                        Screen.Review -> "Review"
                        Screen.Memory -> "Memory"
                        Screen.About -> "About"
                    },
                    icon = when (s) {
                        Screen.Clean -> Icons.Rounded.AutoAwesome
                        Screen.Review -> Icons.Rounded.CheckCircle
                        Screen.Memory -> Icons.Rounded.Memory
                        Screen.About -> Icons.Rounded.Info
                    },
                )
            },
            selectedKey = currentScreen.key,
            onSelect = { key -> currentScreen = Screen.values().first { it.key == key } },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Layer 6: Toast
        WarmToastHost(message = toast, onDismiss = { toast = null })
    }

    // Sheets
    if (showConfirm) {
        ConfirmSheet(
            items = items.filter { selected.contains(it.path) },
            onCancel = { showConfirm = false },
            onConfirm = ::confirmDelete,
        )
    }
    if (showProgress) {
        val total = items.filter { selected.contains(it.path) }.size.coerceAtLeast(1)
        ProgressSheet(
            progress = progressDone.toFloat() / total,
            done = progressDone,
            total = total,
            freedBytes = progressFreed,
            onStop = {
                showProgress = false
                reportFreed = 0L
                showReport = true
                haptics(Haptic.Warn)
            },
        )
    }
    if (showReport) {
        ReportSheet(freedBytes = reportFreed, onDismiss = { showReport = false })
    }
    if (showEgg) {
        EasterEggSheet(
            onCancel = { showEgg = false },
            onConfirm = {
                showEgg = false
                scope.launch {
                    prefs.setVerses(!versesEnabled)
                    toast = if (!versesEnabled) "Verses on" else "Verses off"
                    haptics(if (!versesEnabled) Haptic.Success else Haptic.Warn)
                }
            },
        )
    }
}

@Composable
private fun FrostedTopbar(
    scrolled: Boolean,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalWarmPalette.current
    val topPad by animateDpAsState(if (scrolled) 14.dp else 24.dp, label = "topPad")
    val botPad by animateDpAsState(if (scrolled) 12.dp else 16.dp, label = "botPad")
    val titleSize by animateFloatAsState(if (scrolled) 24f else 30f, tween(300), label = "titleSize")

    Column(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.bg)
                .padding(horizontal = 24.dp)
                .padding(top = topPad, bottom = botPad),
        ) {
            CounterHeading(
                text = title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(titleSize, androidx.compose.ui.unit.TextUnitType.Sp),
                ),
                color = palette.ink,
            )
            if (!scrolled) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink2,
                )
            }
        }
        // Gradient fade strip below topbar — cards fade into this
        Box(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(
                    Brush.verticalGradient(
                        0f to palette.bg,
                        1f to palette.bg.copy(alpha = 0f),
                    ),
                ),
        )
    }
}

private fun formatBytesShort(bytes: Long): String = com.yourpackage.cleanforge.data.formatBytes(bytes)
