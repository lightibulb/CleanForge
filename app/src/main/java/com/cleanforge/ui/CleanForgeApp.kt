package com.cleanforge.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanforge.core.scanner.ScanMode
import com.cleanforge.data.Preferences
import com.cleanforge.data.Verses
import com.cleanforge.ui.components.ConfirmDialog
import com.cleanforge.ui.components.CounterHeading
import com.cleanforge.ui.components.DockItem
import com.cleanforge.ui.components.ProgressDialog
import com.cleanforge.ui.components.ReportDialog
import com.cleanforge.ui.components.VerseHero
import com.cleanforge.ui.components.WarmDock
import com.cleanforge.ui.components.WarmFab
import com.cleanforge.ui.components.WarmToastHost
import com.cleanforge.ui.components.openAllFilesSettings
import com.cleanforge.ui.components.openShizukuApp
import com.cleanforge.ui.screens.AboutScreen
import com.cleanforge.ui.screens.CleanScreen
import com.cleanforge.ui.screens.EasterEggSheet
import com.cleanforge.ui.screens.MemoryScreen
import com.cleanforge.ui.screens.ReviewScreen
import com.cleanforge.ui.theme.LocalWarmPalette
import com.cleanforge.ui.util.Haptic
import com.cleanforge.ui.util.rememberHaptics
import kotlinx.coroutines.launch

enum class Screen(val key: String, val title: String, val subtitle: String) {
    Clean("clean", "CleanForge", "a quieter way to tidy up"),
    Review("review", "Review", "you decide what stays"),
    Memory("ram", "Memory", "honest, no boost claims"),
    About("about", "About", "open, private, honest"),
}

/**
 * The Warm shell, driven by the real engine: [CleanViewModel] (scan, selection, plan, cleaning) and [RamViewModel].
 * Deletion always goes through the real confirmation dialog that lists every path.
 */
@Composable
fun CleanForgeApp(
    prefs: Preferences,
    vm: CleanViewModel = hiltViewModel(),
    ramVm: RamViewModel = hiltViewModel(),
) {
    val palette = LocalWarmPalette.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val context = LocalContext.current

    val dark by prefs.dark.collectAsStateWithLifecycle(initialValue = false)
    val versesEnabled by prefs.verses.collectAsStateWithLifecycle(initialValue = true)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ram by ramVm.state.collectAsStateWithLifecycle()

    var currentScreen by remember { mutableStateOf(Screen.Clean) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showEgg by remember { mutableStateOf(false) }

    // 7-tap easter egg state
    var forgeTaps by remember { mutableStateOf(0) }
    var forgeLastTap by remember { mutableStateOf(0L) }

    val scrollState = rememberScrollState()
    val scrolled by remember { derivedStateOf { scrollState.value > 20 } }
    val verseAlpha by remember { derivedStateOf { (1f - scrollState.value / 140f).coerceIn(0f, 1f) } }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Permission and Shizuku state can change while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    // Read /proc/meminfo only while the Memory tab is on screen and the app is in the foreground.
    LifecycleStartEffect(currentScreen) {
        if (currentScreen == Screen.Memory) ramVm.startObserving()
        onStopOrDispose { ramVm.stopObserving() }
    }

    // Reset scroll on tab change
    LaunchedEffect(currentScreen) { scrollState.scrollTo(0) }

    // A finished cleaning report arrives from the engine: give it the success tap.
    val reportReady = ui.cleaning?.report != null
    LaunchedEffect(reportReady) { if (reportReady) haptics(Haptic.Success) }

    val quickItems = remember(ui.items) { ui.items.filter { it.category.isQuick } }
    val reviewItems = remember(ui.items) { ui.items.filterNot { it.category.isQuick } }

    fun toggleItem(path: String) {
        haptics(if (path in ui.selected) Haptic.Tick else Haptic.Light)
        vm.toggle(path)
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
                    .padding(top = 106.dp + topInset),
                delayMs = 350,
            )
        }

        // Layer 2: Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = (if (versesEnabled) 250.dp else 108.dp) + topInset)
                .padding(bottom = 160.dp + bottomInset),
        ) {
            when (currentScreen) {
                Screen.Clean -> CleanScreen(
                    ui = ui,
                    items = quickItems,
                    onToggle = ::toggleItem,
                    onScan = { haptics(Haptic.Medium); vm.scan(ScanMode.QUICK) },
                    onCancelScan = vm::cancelScan,
                    onOpenFilesSettings = { openAllFilesSettings(context) },
                    onRequestShizukuPermission = vm::requestShizukuPermission,
                    onOpenShizuku = { openShizukuApp(context) },
                    onRecheckShizuku = { vm.forceRecheckShizuku(); toast = "Checking Shizuku…" },
                    emptyStateVerse = if (versesEnabled) emptyStateVerse else null,
                )
                Screen.Review -> ReviewScreen(
                    ui = ui,
                    items = reviewItems,
                    onToggle = ::toggleItem,
                    onScan = { haptics(Haptic.Medium); vm.scan(ScanMode.ON_DEMAND) },
                    onCancelScan = vm::cancelScan,
                )
                Screen.Memory -> {
                    val snap = ram.snapshot
                    MemoryScreen(
                        usedBytes = snap?.usedBytes ?: 0L,
                        totalBytes = snap?.totalBytes ?: 0L,
                        availableBytes = snap?.availableBytes ?: 0L,
                        lastFreedLabel = ram.lastClearResult?.note,
                        clearing = ram.isClearing,
                        onClear = { haptics(Haptic.Medium); ramVm.clear() },
                    )
                }
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

        // Layer 4: FAB (hidden while a plan, a run or a report is on screen)
        WarmFab(
            visible = ui.selected.isNotEmpty() && !ui.busy && ui.plan == null &&
                (currentScreen == Screen.Clean || currentScreen == Screen.Review),
            count = ui.selected.size,
            sizeLabel = formatBytes(ui.selectedBytes),
            onClick = { haptics(Haptic.Medium); vm.requestClean() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp + bottomInset),
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
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )

        // Layer 6: Toast
        WarmToastHost(message = toast, onDismiss = { toast = null })
    }

    // The real confirmation, progress and report: every path listed, refused items explained.
    ui.plan?.let { plan ->
        ConfirmDialog(plan = plan, onConfirm = vm::confirmClean, onDismiss = vm::dismissPlan)
    }
    ui.cleaning?.let { c ->
        val report = c.report
        if (report == null) ProgressDialog(c, onStop = vm::cancelClean) else ReportDialog(report, onDismiss = vm::dismissReport)
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
                .statusBarsPadding() // edge-to-edge: keep the title clear of the clock and icons
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
