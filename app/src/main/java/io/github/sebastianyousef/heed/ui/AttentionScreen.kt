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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.sebastianyousef.heed.focus.DetectionMode
import io.github.sebastianyousef.heed.focus.FocusMode
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.KnownScrollers
import io.github.sebastianyousef.heed.focus.LearnedSurface
import io.github.sebastianyousef.heed.focus.ScrollWatcherService
import io.github.sebastianyousef.heed.usage.AttentionStat
import io.github.sebastianyousef.heed.usage.UsageTracker
import kotlin.math.roundToInt

/**
 * Time and rules, arranged around the fact that most apps are not the problem.
 *
 * The previous version listed every app you had ever opened with identical weight, which
 * buried Snapchat behind an authenticator and a PDF viewer. Now apps that carry a feed
 * come first and everything else collapses into a tail you can ignore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttentionScreen(vm: InboxViewModel, onSettings: () -> Unit) {
    val context = LocalContext.current
    val stats by vm.attention.collectAsState()
    val rules by vm.focusRules.collectAsState()
    val surfaces by vm.surfaces.collectAsState()
    val settings by vm.settings.collectAsState()
    val strict by vm.strict.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var usageGranted by remember { mutableStateOf(UsageTracker.hasPermission(context)) }
    var watcherEnabled by remember { mutableStateOf(ScrollWatcherService.isEnabled(context)) }
    var showAll by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = UsageTracker.hasPermission(context)
                watcherEnabled = ScrollWatcherService.isEnabled(context)
                if (usageGranted) vm.refreshUsage()
                vm.refreshStrict()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // An app belongs in the top section if it carries a feed or you have said something
    // about it. Everything else is noise in this context, however much you use it.
    val feeds = stats.filter {
        KnownScrollers.isKnown(it.packageName) || rules.containsKey(it.packageName)
    }
    val rest = stats.filter { it !in feeds && it.todayMs > 60_000 }

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
            item { TodayCard(stats) }

            if (!usageGranted) {
                item {
                    PermissionCard(
                        "Let Heed see which apps you use",
                        "Without this it knows what interrupted you but not what that cost.",
                        "Grant usage access",
                    ) { context.startActivity(UsageTracker.settingsIntent(context)) }
                }
            }
            if (!watcherEnabled) {
                item {
                    PermissionCard(
                        "Let Heed see scrolling",
                        "For most apps it only measures how fast and how long you scroll. " +
                            "For apps you set to Precise it reads the layout's structure so " +
                            "it can tell a discovery feed from your friends' stories — never " +
                            "the text on your screen.",
                        "Open accessibility settings",
                    ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }

            item { BedtimeCard(settings.bedtimeEnabled, settings.bedtimeStart, settings.bedtimeEnd, vm::setBedtime) }

            if (feeds.isNotEmpty()) {
                item { SectionHeader("Feeds") }
                items(feeds, key = { it.packageName }) { stat ->
                    AppCard(
                        stat = stat,
                        rule = rules[stat.packageName] ?: FocusRule(stat.packageName, stat.appLabel),
                        surfaces = surfaces[stat.packageName].orEmpty(),
                        strict = strict,
                        vm = vm,
                    )
                }
            }

            if (rest.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader("Everything else")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showAll = !showAll }) {
                            Text(if (showAll) "Hide" else "Show ${rest.size}")
                        }
                    }
                }
                if (showAll) {
                    items(rest, key = { it.packageName }) { stat ->
                        AppCard(
                            stat = stat,
                            rule = rules[stat.packageName] ?: FocusRule(stat.packageName, stat.appLabel),
                            surfaces = surfaces[stat.packageName].orEmpty(),
                            strict = strict,
                            vm = vm,
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
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun TodayCard(stats: List<AttentionStat>) {
    val total = stats.sumOf { it.todayMs }
    val opens = stats.sumOf { it.launchesToday }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                formatDuration(total),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "on your phone today · $opens app opens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun BedtimeCard(enabled: Boolean, start: Int, end: Int, onChange: (Boolean, Int, Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bedtime", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (enabled) {
                            "Every app with a rule is closed between $start:00 and $end:00. " +
                                "Calls and alarms are untouched."
                        } else {
                            "Off."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { onChange(it, start, end) })
            }
            if (enabled) {
                Text("Starts at $start:00", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = start.toFloat(),
                    onValueChange = { onChange(true, it.roundToInt(), end) },
                    valueRange = 18f..23f, steps = 4,
                )
                Text("Ends at $end:00", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = end.toFloat(),
                    onValueChange = { onChange(true, start, it.roundToInt()) },
                    valueRange = 4f..11f, steps = 6,
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
private fun AppCard(
    stat: AttentionStat,
    rule: FocusRule,
    surfaces: List<LearnedSurface>,
    strict: Boolean,
    vm: InboxViewModel,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stat.appLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${stat.launchesToday} opens today" +
                            if (stat.alerts > 0) " · ${stat.alerts} interruptions" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatDuration(stat.todayMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (stat.alerts > 0 && stat.minutesPerAlert >= 1) {
                Text(
                    "About ${stat.minutesPerAlert.roundToInt()} minutes of you per notification.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    when {
                        expanded -> "Hide"
                        rule.mode == FocusMode.BLOCK -> "Blocked"
                        rule.mode == FocusMode.NUDGE && rule.fromPreset -> "Nudges you (preset)"
                        rule.mode == FocusMode.NUDGE -> "Nudges you"
                        rule.dailyUsageSeconds > 0 || rule.dailyLaunchLimit > 0 -> "Limited"
                        else -> "Set a rule"
                    }
                )
            }
            if (expanded) RuleEditor(rule, surfaces, strict, vm)
        }
    }
}

@Composable
private fun RuleEditor(
    rule: FocusRule,
    surfaces: List<LearnedSurface>,
    strict: Boolean,
    vm: InboxViewModel,
) {
    val onRule = vm::setFocusRule
    Column {
        if (strict) {
            Text(
                "Strict mode is on. You can tighten these, but not loosen them.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FocusMode.entries.forEach { mode ->
                FilterChip(
                    selected = rule.mode == mode,
                    onClick = { onRule(rule.copy(mode = mode, fromPreset = false)) },
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

        Spacer(Modifier.height(10.dp))
        Text("How it decides you're in a feed", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DetectionMode.entries.forEach { mode ->
                FilterChip(
                    selected = rule.detection == mode,
                    onClick = { onRule(rule.copy(detection = mode)) },
                    label = {
                        Text(if (mode == DetectionMode.BEHAVIOURAL) "Automatic" else "Precise")
                    },
                )
            }
        }
        Text(
            if (rule.detection == DetectionMode.BEHAVIOURAL) {
                "Automatic watches how fast and how long you scroll. It never looks at your " +
                    "screen, but it cannot tell a discovery feed from a chat list — both are " +
                    "scrolling."
            } else {
                "Precise matches the screen against ones you've taught it, so it can block " +
                    "Discovery and leave your friends' stories alone. It reads the layout's " +
                    "structure — view ids and class names — never the text on it."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (rule.detection == DetectionMode.PRECISE) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.armSurfaceCapture() }) { Text("Teach a screen") }
            Text(
                "Tap this, then go and open the screen you mean. Heed records the next one " +
                    "it sees. Do it once for Discovery, and again for your friends' stories " +
                    "if you want that one left alone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            surfaces.forEach { surface ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        surface.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = surface.block,
                        onClick = { vm.setSurfaceBlock(surface, !surface.block) },
                        label = { Text(if (surface.block) "Blocked" else "Allowed") },
                    )
                    TextButton(onClick = { vm.deleteSurface(surface.id) }) { Text("Forget") }
                }
            }
        }

        if (rule.mode == FocusMode.BLOCK && rule.detection == DetectionMode.BEHAVIOURAL) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Stops you after ${rule.scrollBudgetEvents} scrolls in one go.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = rule.scrollBudgetEvents.toFloat(),
                onValueChange = { onRule(rule.copy(scrollBudgetEvents = it.roundToInt())) },
                valueRange = 1f..30f, steps = 28,
            )
        }

        Spacer(Modifier.height(8.dp))
        LimitSlider(
            label = if (rule.dailyScrollSeconds > 0) {
                "${rule.dailyScrollSeconds / 60} min of scrolling a day — the rest of the app stays open"
            } else "No scrolling budget",
            value = (rule.dailyScrollSeconds / 60).toFloat(),
            max = 60f,
        ) { onRule(rule.copy(dailyScrollSeconds = it * 60)) }

        LimitSlider(
            label = if (rule.dailyUsageSeconds > 0) {
                "${rule.dailyUsageSeconds / 60} min in the app a day"
            } else "No time limit",
            value = (rule.dailyUsageSeconds / 60).toFloat(),
            max = 180f,
        ) { onRule(rule.copy(dailyUsageSeconds = it * 60)) }

        LimitSlider(
            label = if (rule.dailyLaunchLimit > 0) {
                "${rule.dailyLaunchLimit} opens a day"
            } else "No limit on opens",
            value = rule.dailyLaunchLimit.toFloat(),
            max = 50f,
        ) { onRule(rule.copy(dailyLaunchLimit = it)) }
    }
}

@Composable
private fun LimitSlider(label: String, value: Float, max: Float, onChange: (Int) -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = value.coerceIn(0f, max),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = 0f..max,
    )
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
