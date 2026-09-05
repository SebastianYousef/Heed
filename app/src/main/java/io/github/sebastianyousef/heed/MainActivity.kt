package io.github.sebastianyousef.heed

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.sebastianyousef.heed.ui.AppDetailScreen
import io.github.sebastianyousef.heed.ui.AppsScreen
import io.github.sebastianyousef.heed.ui.AttentionScreen
import io.github.sebastianyousef.heed.ui.DetailScreen
import io.github.sebastianyousef.heed.ui.FocusScreen
import io.github.sebastianyousef.heed.ui.GroupDetailScreen
import io.github.sebastianyousef.heed.ui.GroupsScreen
import io.github.sebastianyousef.heed.ui.HeedTheme
import io.github.sebastianyousef.heed.ui.InboxScreen
import io.github.sebastianyousef.heed.ui.InboxViewModel
import io.github.sebastianyousef.heed.ui.OnboardingScreen
import io.github.sebastianyousef.heed.ui.SettingsScreen

/**
 * Two halves, deliberately kept apart.
 *
 * Notifications is about what reaches you. Attention is about where your time goes. They
 * share a database and feed each other — a notification that leads to an hour of scrolling
 * teaches the filter something — but they are different questions and mixing them into one
 * list made both harder to think about.
 */
private enum class Section(val route: String, val label: String) {
    NOTIFICATIONS("notifications", "Notifications"),
    FOCUS("focus", "Focus"),
    ATTENTION("attention", "Attention"),
}

class MainActivity : ComponentActivity() {

    private val postNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Asked on every launch where it is still missing, not just during onboarding.
     * Without it Heed reads and files notifications perfectly and then cannot raise a
     * single one, which looks from the outside like the app doing nothing at all.
     */
    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        postNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPostNotificationsIfNeeded()
        setContent {
            HeedTheme {
                val vm: InboxViewModel = viewModel()
                val settings by vm.settings.collectAsState()
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route

                Scaffold(
                    bottomBar = {
                        if (route in Section.entries.map { it.route }) {
                            SectionBar(nav, route)
                        }
                    }
                ) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = if (settings.onboardingComplete) {
                            Section.NOTIFICATIONS.route
                        } else {
                            "onboarding"
                        },
                        modifier = Modifier.padding(
                            bottom = padding.calculateBottomPadding(),
                        ),
                    ) {
                        composable("onboarding") {
                            OnboardingScreen(
                                vm = vm,
                                onDone = {
                                    vm.completeOnboarding()
                                    nav.navigate(Section.NOTIFICATIONS.route) {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Section.NOTIFICATIONS.route) {
                            InboxScreen(
                                vm = vm,
                                onOpen = { id -> nav.navigate("detail/$id") },
                                onSettings = { nav.navigate("settings") },
                                onApps = { nav.navigate("apps") },
                            )
                        }
                        composable(Section.FOCUS.route) {
                            FocusScreen(vm = vm, onSettings = { nav.navigate("settings") })
                        }
                        composable(Section.ATTENTION.route) {
                            AttentionScreen(
                                vm = vm,
                                onSettings = { nav.navigate("settings") },
                                onOpenApp = { pkg -> nav.navigate("app/$pkg") },
                                onOpenGroups = { nav.navigate("groups") },
                            )
                        }
                        composable(
                            "app/{pkg}",
                            arguments = listOf(navArgument("pkg") { type = NavType.StringType }),
                        ) { backStack ->
                            AppDetailScreen(
                                vm = vm,
                                packageName = backStack.arguments?.getString("pkg").orEmpty(),
                                onBack = { nav.popBackStack() },
                                onOpenGroup = { id -> nav.navigate("group/$id") },
                            )
                        }
                        composable(
                            "detail/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.LongType }),
                        ) { backStack ->
                            DetailScreen(
                                vm = vm,
                                id = backStack.arguments?.getLong("id") ?: 0L,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("groups") {
                            GroupsScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onOpen = { id -> nav.navigate("group/$id") },
                            )
                        }
                        composable(
                            "group/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.LongType }),
                        ) { backStack ->
                            GroupDetailScreen(
                                vm = vm,
                                groupId = backStack.arguments?.getLong("id") ?: 0L,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                        composable("apps") {
                            AppsScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionBar(nav: NavHostController, route: String?) {
    NavigationBar {
        Section.entries.forEach { section ->
            NavigationBarItem(
                selected = route == section.route,
                onClick = {
                    if (route != section.route) {
                        nav.navigate(section.route) {
                            popUpTo(Section.NOTIFICATIONS.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    Icon(
                        when (section) {
                            Section.NOTIFICATIONS -> Icons.Default.Notifications
                            Section.FOCUS -> Icons.Default.SelfImprovement
                            Section.ATTENTION -> Icons.Default.Timelapse
                        },
                        contentDescription = section.label,
                    )
                },
                label = { Text(section.label) },
            )
        }
    }
}
