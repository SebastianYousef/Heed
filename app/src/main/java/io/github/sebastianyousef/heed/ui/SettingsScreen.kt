package io.github.sebastianyousef.heed.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: InboxViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val stats by vm.modelStats.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Section("How strict to be") {
                Text(
                    "Anything scoring above ${(settings.threshold * 100).roundToInt()} gets through. " +
                        "Lower lets more in; higher keeps more for the digest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.threshold,
                    onValueChange = { vm.setThreshold(it) },
                    valueRange = 0.2f..0.9f,
                )
            }

            Section("Thinking time") {
                Text(
                    "How long to hold a silent notification before deciding: " +
                        "${settings.holdWindowMs} ms. Longer means bursts get collapsed into one " +
                        "interruption. Only applies to apps you've silenced — for anything else " +
                        "the alert has already fired and waiting would only delay the cleanup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.holdWindowMs.toFloat(),
                    onValueChange = { vm.setHoldWindow(it.toLong()) },
                    valueRange = 0f..10_000f,
                    steps = 9,
                )
            }

            Section("Summaries") {
                Text(
                    "Digest every ${settings.digestIntervalHours} hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.digestIntervalHours.toFloat(),
                    onValueChange = { vm.setDigestInterval(it.roundToInt()) },
                    valueRange = 1f..12f,
                    steps = 10,
                )
            }

            Section("Quiet hours") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only emergencies at night", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Between ${settings.quietHoursStart}:00 and ${settings.quietHoursEnd}:00 " +
                                "only calls, alarms and one-time codes get through — the classifier " +
                                "doesn't get a vote.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.quietHoursStrict,
                        onCheckedChange = { vm.setQuietStrict(it) },
                    )
                }
            }

            Section("What it has learned") {
                val (examples, confidence) = stats
                Text(
                    if (examples == 0) {
                        "No training data yet. Every time you tap a notification, swipe one away, " +
                            "or mark something in the inbox, the model learns from it. Until then " +
                            "decisions come purely from the built-in rules."
                    } else {
                        "Trained on $examples of your reactions. The model now carries " +
                            "${(confidence * 100).roundToInt()}% of each decision, with the rules " +
                            "covering the rest."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.resetModel() }) { Text("Forget everything") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
