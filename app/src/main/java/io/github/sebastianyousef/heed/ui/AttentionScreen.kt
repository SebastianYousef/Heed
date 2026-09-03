package io.github.sebastianyousef.heed.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.sebastianyousef.heed.focus.FocusMode
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.ScrollWatcherService
import io.github.sebastianyousef.heed.usage.AttentionStat
import io.github.sebastianyousef.heed.usage.UsageTracker
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttentionScreen(vm: InboxViewModel, onSettings: () -> Unit) {
    val context = LocalContext.current
    val stats by vm.attention.collectAsState()
    val rules by vm.focusRules.collectAsState()
    val settings by vm.settings.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var usageGranted by remember { mutableStateOf(UsageTracker.hasPermission(context)) }
    var watcherEnabled by remember { mutableStateOf(ScrollWatcherService.isEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = UsageTracker.hasPermission(context)
                watcherEnabled = ScrollWatcherService.isEnabled(context)
                // Ingest on every return to this screen, including straight after the
                // user has just granted access in Settings.
                if (usageGranted) vm.refreshUsage()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Attention") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!usageGranted) {
                item {
                    PermissionCard(
                        title = "Let Heed see which apps you use",
                        body = "Without this it can tell you what interrupted you, but not " +
                            "what that interruption cost. Usage access reports only which " +
                            "app was in front and for how long.",
                        action = "Grant usage access",
                        onClick = { context.startActivity(UsageTracker.settingsIntent(context)) },
                    )
                }
            }
            if (!watcherEnabled) {
                item {
                    PermissionCard(
                        title = "Let Heed measure scrolling",
                        body = "Optional, and the most invasive thing here — so it is built " +
                            "to be unable to abuse it. The service is declared without " +
                            "screen-content access, so Android will not give it the text on " +
                            "your screen. It sees that a scroll happened and in which app, " +
                            "nothing else. Without it, Heed can still measure time, just not " +
                            "tell scrolling apart from reading.",
                        action = "Open accessibility settings",
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    )
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Interrupt me after", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (settings.scrollInterventionMinutes <= 0) {
                                "Off. Heed will measure but never interrupt."
                            } else {
                                "${settings.scrollInterventionMinutes} " +
                                    (if (settings.scrollInterventionMinutes == 1) "minute" else "minutes") +
                                    " of unbroken " +
                                    "scrolling. Stopping to actually read something resets " +
                                    "it, so this measures the trance rather than the time."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = settings.scrollInterventionMinutes.toFloat(),
                            onValueChange = { vm.setScrollIntervention(it.roundToInt()) },
                            valueRange = 0f..30f,
                            steps = 29,
                        )
                    }
                }
            }

            if (stats.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Give it a day of normal use.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(stats, key = { it.packageName }) { stat ->
                AttentionCard(
                    stat = stat,
                    rule = rules[stat.packageName]
                        ?: FocusRule(stat.packageName, stat.appLabel),
                    onRule = vm::setFocusRule,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun AttentionCard(
    stat: AttentionStat,
    rule: FocusRule,
    onRule: (FocusRule) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Text(
                    stat.appLabel,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatDuration(stat.totalMs),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))

            // The sentence that no screen-time app can write, because it needs both halves.
            Text(
                if (stat.alerts == 0) {
                    "You opened this yourself; it never interrupted you."
                } else {
                    "Interrupted you ${stat.alerts} times · you opened " +
                        "${stat.openedFromAlert} of them · that became " +
                        formatDuration(stat.msFromAlerts)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (stat.alerts > 0 && stat.minutesPerAlert >= 1) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "About ${stat.minutesPerAlert.roundToInt()} minutes of you per notification.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (stat.scrollingSessions > 0) {
                Text(
                    "${stat.scrollingSessions} of those sessions were mostly scrolling.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    when {
                        expanded -> "Hide rules"
                        rule.mode == FocusMode.BLOCK -> "Blocked when you scroll"
                        rule.mode == FocusMode.NUDGE -> "Nudges you"
                        rule.dailyUsageSeconds > 0 || rule.dailyScrollSeconds > 0 -> "Limited"
                        else -> "Set a rule"
                    }
                )
            }

            if (expanded) RuleEditor(rule, onRule)
        }
    }
}

@Composable
private fun RuleEditor(rule: FocusRule, onRule: (FocusRule) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FocusMode.entries.forEach { mode ->
                FilterChip(
                    selected = rule.mode == mode,
                    onClick = { onRule(rule.copy(mode = mode)) },
                    label = {
                        Text(
                            when (mode) {
                                FocusMode.OFF -> "Measure"
                                FocusMode.NUDGE -> "Nudge"
                                FocusMode.BLOCK -> "Block"
                            }
                        )
                    },
                )
            }
        }

        if (rule.mode == FocusMode.BLOCK) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Stops you after ${rule.scrollBudgetEvents} scrolls. Low numbers stop a " +
                    "feed within a flick or two while leaving normal use — opening a chat, " +
                    "reading a message — alone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = rule.scrollBudgetEvents.toFloat(),
                onValueChange = { onRule(rule.copy(scrollBudgetEvents = it.roundToInt())) },
                valueRange = 1f..30f,
                steps = 28,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            if (rule.dailyScrollSeconds > 0) {
                "${rule.dailyScrollSeconds / 60} minutes of scrolling a day. Everything " +
                    "else in the app stays open."
            } else {
                "No scrolling budget."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = (rule.dailyScrollSeconds / 60).toFloat(),
            onValueChange = { onRule(rule.copy(dailyScrollSeconds = it.roundToInt() * 60)) },
            valueRange = 0f..60f,
            steps = 59,
        )

        Text(
            if (rule.dailyUsageSeconds > 0) {
                "${rule.dailyUsageSeconds / 60} minutes in the app a day, all uses counted."
            } else {
                "No time limit."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = (rule.dailyUsageSeconds / 60).toFloat(),
            onValueChange = { onRule(rule.copy(dailyUsageSeconds = it.roundToInt() * 60)) },
            valueRange = 0f..180f,
            steps = 35,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
