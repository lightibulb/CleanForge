package com.cleanforge.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cleanforge.ui.components.ConfirmDialog
import com.cleanforge.ui.components.ProgressDialog
import com.cleanforge.ui.components.ReportDialog
import com.cleanforge.ui.screens.AboutScreen
import com.cleanforge.ui.screens.CleanScreen
import com.cleanforge.ui.screens.ReviewScreen

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val DESTINATIONS = listOf(
    Dest("clean", "Clean", Icons.Default.CleaningServices),
    Dest("review", "Review", Icons.Default.FactCheck),
    Dest("about", "About", Icons.Default.Info)
)

@Composable
fun CleanForgeNavHost(vm: CleanViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val ui by vm.ui.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    val onActionTab = current?.hierarchy?.any { it.route == "clean" || it.route == "review" } == true

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (onActionTab && ui.selected.isNotEmpty() && !ui.busy && ui.plan == null) {
                ExtendedFloatingActionButton(
                    onClick = vm::requestClean,
                    icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
                    text = { Text("Review ${ui.selected.size} · ${formatBytes(ui.selectedBytes)}") }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                DESTINATIONS.forEach { d ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == d.route } == true,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, contentDescription = null) },
                        label = { Text(d.label) }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = "clean",
            modifier = Modifier.padding(bottom = pad.calculateBottomPadding())
        ) {
            composable("clean") { CleanScreen(vm) }
            composable("review") { ReviewScreen(vm) }
            composable("about") { AboutScreen() }
        }
    }

    // USER CONFIRMATION lives here, once, for every tab.
    ui.plan?.let { plan ->
        ConfirmDialog(plan = plan, onConfirm = vm::confirmClean, onDismiss = vm::dismissPlan)
    }
    ui.cleaning?.let { c ->
        val report = c.report
        if (report == null) ProgressDialog(c) else ReportDialog(report, onDismiss = vm::dismissReport)
    }
}
