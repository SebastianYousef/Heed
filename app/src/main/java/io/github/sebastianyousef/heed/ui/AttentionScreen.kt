package io.github.sebastianyousef.heed.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.sebastianyousef.heed.data.AppUsageRow
import io.github.sebastianyousef.heed.focus.FocusMode
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.Grayscale
import io.github.sebastianyousef.heed.focus.ScrollWatcherService
import io.github.sebastianyousef.heed.usage.UsageTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Where your time went, and what to do about it.
 *
 * The previous version put every control for every app on this one screen: three sliders,
 * two sets of chips and a surface list per app, all inline, all expanded in place. It
 * technically worked and it was unusable — you could not answer "how long was I on my
 * phone today" without scrolling past four rule editors.
 *
 * So this screen now answers exactly one question, and the rules moved to
 * [AppDetailScreen] behind a tap. Overview first, controls second.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttentionScreen(
    vm: InboxViewModel,
    onSettings: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val context = LocalContext.current
    val stats by vm.attention.collectAsState()
    val rules by vm.focusRules.collectAsState()
    val settings by vm.settings.collectAsState()
    val days by vm.usageDays.collectAsState()
    val week by vm.weekByApp.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var usageGranted by remember { mutableStateOf(UsageTracker.hasPermission(context)) }
    var watcherEnabled by remember { mutableStateOf(ScrollWatcherService.isEnabled(context)) }
    var greyAvailable by remember { mutableStateOf(Grayscale.isAvailable(context)) }
    var range by remember { mutableStateOf(Range.TODAY) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = UsageTracker.hasPermission(context)
                watcherEnabled = ScrollWatcherService.isEnabled(context)
                greyAvailable = Grayscale.isAvailable(context)
                if (usageGranted) vm.refreshUsage()
                vm.refreshStrict()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val today: List<AppUsageRow> = stats
        .filter { it.todayMs > 0 }
        .map { AppUsageRow(it.packageName, it.appLabel, it.todayMs, it.launchesToday) }
        .sortedByDescending { it.totalMs }

    val rows = if (range == Range.TODAY) today else week
    val total = rows.sumOf { it.totalMs }

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
            item {
                ScreenTimeCard(
                    totalMs = total,
                    opens = rows.sumOf { it.launches },
                    range = range,
                    days = days,
                    onRange = { range = it },
                )
            }

            if (!usageGranted) {
                item {
                    SetupCard(
                        "Let Heed see which apps you use",
                        "This is the one permission the whole Attention half needs. " +
                            "Without it there is no screen time to show.",
                        "Grant usage access",
                    ) { context.startActivity(UsageTracker.settingsIntent(context)) }
                }
            }

            item {
                BedtimeCard(
                    settings.bedtimeEnabled,
                    settings.bedtimeStart,
                    settings.bedtimeEnd,
                    settings.grayscaleAtBedtime,
                    greyAvailable,
                    vm::setBedtime,
                    vm::setGrayscaleAtBedtime,
                )
            }

            item { ScreenAccessCard(watcherEnabled, vm, context) }

            if (rows.isNotEmpty()) {
                item {
                    Text(
                        if (range == Range.TODAY) "Today, app by app" else "Last 7 days, app by app",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(rows, key = { it.packageName }) { row ->
                    AppRow(
                        row = row,
                        shareOfTotal = if (total > 0) row.totalMs.toFloat() / total else 0f,
                        rule = rules[row.packageName],
                        onClick = { onOpenApp(row.packageName) },
                    )
                }
            } else {
                item {
                    Text(
                        if (usageGranted) {
                            "Nothing recorded yet. Give it a few hours of normal use."
                        } else {
                            "Grant usage access above and this fills in."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private enum class Range(val label: String) { TODAY("Today"), WEEK("7 days") }

/** Headline number, range switch, and the week at a glance. */
@Composable
private fun ScreenTimeCard(
    totalMs: Long,
    opens: Int,
    range: Range,
    days: List<DayTotal>,
    onRange: (Range) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        formatDuration(totalMs),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "on your phone · $opens app opens",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Range.entries.forEach { option ->
                        FilterChip(
                            selected = range == option,
                            onClick = { onRange(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            if (days.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                WeekChart(days)
            }
        }
    }
}

/**
 * Seven bars, scaled to the worst day.
 *
 * Scaled to the peak rather than to a fixed ceiling on purpose: an absolute axis makes a
 * good week and a bad week look nearly identical, and the useful signal here is the
 * shape, not the absolute height.
 */
@Composable
private fun WeekChart(days: List<DayTotal>) {
    val peak = (days.maxOfOrNull { it.totalMs } ?: 0L).coerceAtLeast(1L)
    val formatter = remember { SimpleDateFormat("EEE", Locale.getDefault()) }

    Row(
        Modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                val fraction = (day.totalMs.toFloat() / peak).coerceIn(0.02f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.onPrimaryContainer)
                    )
                }
                Text(
                    formatter.format(Date(day.startOfDay)).take(1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** One app: icon, name, time, and a bar showing its share of the period. */
@Composable
private fun AppRow(
    row: AppUsageRow,
    shareOfTotal: Float,
    rule: FocusRule?,
    onClick: () -> Unit,
) {
    val label = rememberAppLabel(row.packageName, row.appLabel)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(row.packageName, label)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        buildString {
                            append("${row.launches} opens")
                            ruleSummary(rule)?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rule != null && rule.mode != FocusMode.OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    formatDuration(row.totalMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(shareOfTotal.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/** The one-line version of a rule, for the list. Null when there is nothing to say. */
private fun ruleSummary(rule: FocusRule?): String? {
    rule ?: return null
    val parts = buildList {
        when (rule.mode) {
            FocusMode.BLOCK -> add("blocked")
            FocusMode.NUDGE -> add("nudges")
            FocusMode.OFF -> Unit
        }
        if (rule.dailyUsageSeconds > 0) add("${rule.dailyUsageSeconds / 60} min limit")
        if (rule.dailyLaunchLimit > 0) add("${rule.dailyLaunchLimit} opens")
        if (rule.grayscale) add("grey")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun BedtimeCard(
    enabled: Boolean,
    start: Int,
    end: Int,
    grey: Boolean,
    greyAvailable: Boolean,
    onChange: (Boolean, Int, Int) -> Unit,
    onGrey: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bedtime", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (enabled) {
                            "Apps with a rule are closed between $start:00 and $end:00. " +
                                "Calls, alarms and authenticators are untouched."
                        } else {
                            "Off."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { onChange(it, start, end) })
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Grey screen at night", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (greyAvailable) {
                            "Drains the colour out of the whole screen during those hours. " +
                                "Nothing is blocked — the phone just stops being interesting."
                        } else {
                            "Needs a one-time setup over USB. Open Settings to see the command."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = grey && greyAvailable,
                    enabled = greyAvailable,
                    onCheckedChange = onGrey,
                )
            }

            if (enabled) {
                Spacer(Modifier.height(4.dp))
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

/**
 * The accessibility service, and the banking-app problem it causes.
 *
 * This card is blunt about the trade-off because the alternative is worse: someone
 * discovers at a checkout that their bank will not open, has no idea Heed is why, and
 * uninstalls it. Saying so up front, with the off switch right there, costs a few lines
 * and keeps the app installed.
 */
@Composable
private fun ScreenAccessCard(
    enabled: Boolean,
    vm: InboxViewModel,
    context: android.content.Context,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Screen access", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                if (enabled) {
                    "On. Heed can measure scrolling and tell one feed from another — the " +
                        "only way to block Snapchat's Spotlight without also blocking your " +
                        "chats.\n\nBanking apps refuse to run while any accessibility " +
                        "service is enabled. If your bank stops opening, this is why."
                } else {
                    "Off. Time limits, opens, bedtime and grey screen all still work — " +
                        "those run on usage statistics and need nothing from your screen.\n\n" +
                        "Turning it on adds scrolling measurement and per-feed blocking, " +
                        "and will stop banking apps from starting."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.pauseScreenAccess() }) {
                        Text("Turn off for banking")
                    }
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("System settings") }
                }
            } else {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Turn on screen access") }
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, body: String, action: String, onClick: () -> Unit) {
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

internal fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
