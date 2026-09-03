package io.github.sebastianyousef.heed

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import io.github.sebastianyousef.heed.ui.AppsScreen
import io.github.sebastianyousef.heed.ui.AttentionScreen
import io.github.sebastianyousef.heed.ui.DetailScreen
import io.github.sebastianyousef.heed.ui.InboxScreen
import io.github.sebastianyousef.heed.ui.InboxViewModel
import io.github.sebastianyousef.heed.ui.HeedTheme
import io.github.sebastianyousef.heed.ui.OnboardingScreen
import io.github.sebastianyousef.heed.ui.SettingsScreen

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

                NavHost(
                    navController = nav,
                    startDestination = if (settings.onboardingComplete) "inbox" else "onboarding",
                ) {
                    composable("onboarding") {
                        OnboardingScreen(
                            vm = vm,
                            onDone = {
                                vm.completeOnboarding()
                                nav.navigate("inbox") { popUpTo("onboarding") { inclusive = true } }
                            },
                        )
                    }
                    composable("inbox") {
                        InboxScreen(
                            vm = vm,
                            onOpen = { id -> nav.navigate("detail/$id") },
                            onSettings = { nav.navigate("settings") },
                            onApps = { nav.navigate("apps") },
                            onAttention = { nav.navigate("attention") },
                        )
                    }
                    composable(
                        "detail/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        DetailScreen(
                            vm = vm,
                            id = entry.arguments?.getLong("id") ?: 0L,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("attention") {
                        AttentionScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("apps") {
                        AppsScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
