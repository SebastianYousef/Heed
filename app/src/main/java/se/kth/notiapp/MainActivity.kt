package se.kth.notiapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import se.kth.notiapp.ui.AppsScreen
import se.kth.notiapp.ui.DetailScreen
import se.kth.notiapp.ui.InboxScreen
import se.kth.notiapp.ui.InboxViewModel
import se.kth.notiapp.ui.NotiTheme
import se.kth.notiapp.ui.OnboardingScreen
import se.kth.notiapp.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotiTheme {
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
                    composable("apps") {
                        AppsScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
