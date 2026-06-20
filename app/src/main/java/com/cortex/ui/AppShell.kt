package com.cortex.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cortex.capture.CaptureScreen
import com.cortex.capture.SpeechManager
import com.cortex.ui.screens.AskScreen
import com.cortex.ui.screens.BrowseScreen
import com.cortex.ui.screens.NodeDetailScreen
import com.cortex.ui.screens.ReminderCalendarScreen
import com.cortex.ui.screens.SettingsScreen
import com.cortex.ui.theme.CanvasBackground
import com.cortex.ui.theme.InkMist
import kotlinx.coroutines.launch

object Routes {
    const val CAPTURE = "capture"
    const val ASK_PATTERN = "ask?q={q}"
    const val ASK = "ask"
    const val BROWSE = "browse"
    const val REMINDERS = "reminders"
    const val NODE = "node/{nodeId}"
    const val SETTINGS = "settings"
    fun node(id: String) = "node/$id"
    fun ask(question: String? = null): String =
        if (question.isNullOrBlank()) ASK else "ask?q=${Uri.encode(question)}"
}

private data class NavTarget(val route: String, val label: String, val icon: ImageVector)

private val NAV_TARGETS = listOf(
    NavTarget(Routes.CAPTURE, "Capture", Icons.Outlined.Mic),
    NavTarget(Routes.ASK, "Ask", Icons.Outlined.AutoAwesome),
    NavTarget(Routes.BROWSE, "Browse", Icons.Outlined.AccountTree),
    NavTarget(Routes.REMINDERS, "Reminders", Icons.Outlined.CalendarMonth),
    NavTarget(Routes.SETTINGS, "Settings", Icons.Outlined.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    speechManager: SpeechManager,
    onProcessCapture: (String) -> Unit
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color.Transparent,
                drawerContentColor = InkMist.PrimaryText,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(280.dp)
                    .background(InkMist.CanvasGradient)
            ) {
                Spacer(Modifier.height(32.dp))
                Text(
                    "Cortex",
                    style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                    color = InkMist.PrimaryText,
                    modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)
                )
                Text(
                    "a quiet second brain",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = InkMist.SecondaryText,
                    modifier = Modifier.padding(start = 24.dp, bottom = 24.dp)
                )
                NAV_TARGETS.forEach { target ->
                    val selected = currentRoute?.startsWith(target.route.substringBefore('?').substringBefore('/')) == true
                    NavigationDrawerItem(
                        icon = { Icon(target.icon, contentDescription = target.label) },
                        label = { Text(target.label) },
                        selected = selected,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(target.route) {
                                popUpTo(Routes.CAPTURE) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = InkMist.Moonstone.copy(alpha = 0.14f),
                            unselectedContainerColor = Color.Transparent,
                            selectedTextColor = InkMist.PrimaryText,
                            unselectedTextColor = InkMist.SecondaryText,
                            selectedIconColor = InkMist.Moonstone,
                            unselectedIconColor = InkMist.SecondaryText
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = titleFor(currentRoute),
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            color = InkMist.PrimaryText
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = InkMist.PrimaryText)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = InkMist.PrimaryText
                    )
                )
            }
        ) { padding ->
            CanvasBackground {
                NavHost(
                    navController = navController,
                    startDestination = Routes.CAPTURE,
                    enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280)) },
                    exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)) },
                    popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280)) },
                    popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    composable(Routes.CAPTURE) {
                        CaptureScreen(
                            speechManager = speechManager,
                            onProcessCapture = onProcessCapture,
                            onAskFromHome = { q ->
                                navController.navigate(Routes.ask(q)) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    composable(
                        route = Routes.ASK_PATTERN,
                        arguments = listOf(
                            navArgument("q") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { entry ->
                        val initialQuestion = entry.arguments?.getString("q")
                        AskScreen(
                            initialQuestion = initialQuestion,
                            onConsumedInitialQuestion = {
                                entry.arguments?.putString("q", null)
                            },
                            onOpenNode = { id -> navController.navigate(Routes.node(id)) }
                        )
                    }
                    composable(Routes.BROWSE) {
                        BrowseScreen(
                            onOpenNode = { id -> navController.navigate(Routes.node(id)) }
                        )
                    }
                    composable(Routes.REMINDERS) {
                        ReminderCalendarScreen(
                            onOpenNode = { id -> navController.navigate(Routes.node(id)) }
                        )
                    }
                    composable(Routes.NODE) { entry ->
                        val nodeId = entry.arguments?.getString("nodeId") ?: return@composable
                        NodeDetailScreen(
                            nodeId = nodeId,
                            onOpenNode = { id -> navController.navigate(Routes.node(id)) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen()
                    }
                }
            }
        }
    }
}

private fun titleFor(route: String?): String = when {
    route == null -> "Cortex"
    route.startsWith(Routes.CAPTURE) -> ""
    route.startsWith(Routes.ASK) || route.startsWith("ask") -> "Ask"
    route.startsWith(Routes.BROWSE) -> "Browse"
    route.startsWith(Routes.REMINDERS) -> "Reminders"
    route.startsWith("node") -> ""
    route.startsWith(Routes.SETTINGS) -> "Settings"
    else -> "Cortex"
}
