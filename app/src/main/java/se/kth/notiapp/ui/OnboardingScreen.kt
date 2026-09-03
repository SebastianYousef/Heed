package se.kth.notiapp.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import se.kth.notiapp.capture.NotiListenerService

/**
 * Setup, which is unusually load-bearing for this app.
 *
 * The whole "no flash, no buzz" promise depends on the source apps being silenced first.
 * If the user skips step 3, notiApp still works, but it is reduced to cancelling
 * notifications after they have already gone off — which is the experience everyone
 * complains about. So the flow explains why rather than just asking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(vm: InboxViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val policies by vm.policies.collectAsState()

    val postNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Scaffold(topBar = { TopAppBar(title = { Text("Set up notiApp") }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp)) {
            when (step) {
                0 -> {
                    Text("Let notiApp read your notifications", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Everything stays on this phone. notiApp needs notification access to see " +
                            "what arrives, decide what matters, and keep the rest in an inbox for you.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Open notification access") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            postNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        step = 1
                    }) {
                        Text(if (NotiListenerService.isEnabled(context)) "Granted — continue" else "I've done it")
                    }
                }

                1 -> {
                    Text("Hand notiApp the volume knob", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Set your noisy apps to Silent in Android's settings — they'll still arrive, " +
                            "they just won't interrupt. From then on notiApp is the only thing on your " +
                            "phone allowed to make a sound, and it only does so for what you'd want.\n\n" +
                            "This step is the whole app. Android gives no way for an app like this to " +
                            "stop a notification before it rings, so the only way to never hear the " +
                            "useless ones is to take the sound away from the apps and give it to " +
                            "notiApp instead. It's also what buys the time to think: nothing has " +
                            "alerted you, so a notification can be held for a second, weighed, and " +
                            "judged properly rather than cancelled in a panic.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (policies.isEmpty()) {
                        Text(
                            "No apps seen yet. Come back to this list in Settings once notiApp has " +
                                "been running for a bit and knows who your noisy apps are.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(Modifier.height(280.dp)) {
                            items(policies.take(15), key = { it.packageName }) { policy ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(
                                        Modifier.padding(12.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = policy.sourceSilenced,
                                            onCheckedChange = {
                                                vm.setSourceSilenced(policy.packageName, it)
                                            },
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(policy.appLabel, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${policy.alertedCount + policy.suppressedCount} notifications",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        OutlinedButton(onClick = {
                                            context.startActivity(
                                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                    .putExtra(Settings.EXTRA_APP_PACKAGE, policy.packageName)
                                            )
                                        }) { Text("Silence") }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Done")
                    }
                }
            }
        }
    }
}
