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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.sebastianyousef.heed.core.Time
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
    val rules by vm.focusRules.collectAsState()
    val settings by vm.settings.collectAsState()
    val days by vm.usageDays.collectAsState()
    val rows by vm.rangeApps.collectAsState()
    val range by vm.range.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var usageGranted by remember { mutableStateOf(UsageTracker.hasPermission(context)) }
    var watcherEnabled by remember { mutableStateOf(ScrollWatcherService.isEnabled(context)) }
    var greyAvailable by remember { mutableStateOf(Grayscale.isAvailable(context)) }

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
                    days = days,
                    range = range,
                    onSelect = vm::selectRange,
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

            item { ScreenAccessCard(watcherEnabled, settings.pauseForBanking, vm, context) }

            if (rows.isNotEmpty()) {
                item {
                    Text(
                        rangeHeading(range, days),
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
                        when {
                            !usageGranted -> "Grant usage access above and this fills in."
                            // Naming the day matters: an empty list under a bar you just
                            // tapped otherwise reads as the screen being broken rather
                            // than as "you did not use your phone then".
                            !range.isWeek -> "No apps recorded on " +
                                rangeHeading(range, days).removePrefix("Apps used ") + "."
                            else -> "Nothing recorded yet. Give it a few hours of normal use."
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

/**
 * The headline card: one number, and seven bars you can actually touch.
 *
 * The bars are the control, not decoration. Every screen-time app draws a week chart and
 * almost none let you tap it, which is odd — "what happened on Tuesday" is the obvious
 * next question and the data is already on screen. Tapping a bar re-queries that day and
 * the list below follows, so the chart and the list are never showing different things.
 */
@Composable
private fun ScreenTimeCard(
    totalMs: Long,
    opens: Int,
    days: List<DayTotal>,
    range: UsageRange,
    onSelect: (UsageRange) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val formatter = remember { SimpleDateFormat("EEEE", Locale.getDefault()) }

    // The comparison is the part that means something. Four hours is not a number anyone
    // can judge; four hours against your own average is.
    val average = days.filter { it.totalMs > 0 }.map { it.totalMs }.average()
        .let { if (it.isNaN()) 0.0 else it }
    val delta = if (range.isWeek || average <= 0) null else (totalMs - average).toLong()

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                when {
                    range.isWeek -> "Last 7 days"
                    range.dayIndex == 6 -> "Today"
                    range.dayIndex == 5 -> "Yesterday"
                    else -> days.getOrNull(range.dayIndex ?: 6)
                        ?.let { formatter.format(Date(it.startOfDay)) } ?: ""
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
            Text(
                Time.duration(totalMs),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                buildString {
                    append("$opens app opens")
                    delta?.let {
                        val minutes = kotlin.math.abs(it) / 60_000
                        if (minutes >= 5) {
                            append(if (it < 0) " · ${minutes}m below" else " · ${minutes}m above")
                            append(" your average")
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )

            Spacer(Modifier.height(16.dp))
            WeekChart(days, range) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect(it)
            }

            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(UsageRange(if (range.isWeek) 6 else null))
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(
                    if (range.isWeek) "Show a single day" else "Show the whole week",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Seven bars, scaled to the worst day.
 *
 * Scaled to the peak rather than to a fixed ceiling on purpose: an absolute axis makes a
 * good week and a bad week look nearly identical, and the useful signal here is the
 * shape. Heights animate so that a change after a rule takes effect is something you
 * watch happen rather than something you have to remember.
 */
@Composable
private fun WeekChart(
    days: List<DayTotal>,
    range: UsageRange,
    onSelect: (UsageRange) -> Unit,
) {
    val peak = (days.maxOfOrNull { it.totalMs } ?: 0L).coerceAtLeast(1L)
    val initials = remember { SimpleDateFormat("EEEEE", Locale.getDefault()) }
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer

    Box(Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        HourGrid(peak, CHART_HEIGHT, onContainer)
        Row(
            Modifier.fillMaxWidth().height(CHART_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
        days.forEachIndexed { index, day ->
            val selected = !range.isWeek && range.dayIndex == index
            val fraction by animateFloatAsState(
                targetValue = (day.totalMs.toFloat() / peak).coerceIn(0.03f, 1f),
                label = "bar",
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(UsageRange(index)) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (selected) 1f else 0.72f)
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                            .background(onContainer.copy(alpha = if (selected) 1f else 0.35f))
                    )
                }
                Text(
                    if (selected) Time.duration(day.totalMs)
                    else initials.format(Date(day.startOfDay)),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = onContainer.copy(alpha = if (selected) 1f else 0.6f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        }
    }
}

/**
 * Tall enough for four gridlines to be distinguishable, short enough that the app list —
 * which is what you came to read — is still on screen underneath.
 */
private val CHART_HEIGHT = 104.dp

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
                    Time.duration(row.totalMs),
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
    pauseForBanking: Boolean,
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
                        "chats."
                } else {
                    "Off. Time limits, opens, bedtime and grey screen all still work — " +
                        "those run on usage statistics and need nothing from your screen.\n\n" +
                        "Turning it on adds scrolling measurement and blocking a single " +
                        "feed without touching the rest of the app."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Turn off automatically for banking",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Off by default, and deliberately. Android will not let Heed " +
                            "switch its own screen access back on, so doing this " +
                            "automatically is a one-way door — one wrong guess and " +
                            "blocking stops working until you notice. Left off, Heed " +
                            "still spots a banking app and offers you the switch.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = pauseForBanking,
                    onCheckedChange = { vm.setPauseForBanking(it) },
                )
            }

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

/** Names the selected period, so the list below is never ambiguous about what it shows. */
private fun rangeHeading(range: UsageRange, days: List<DayTotal>): String {
    if (range.isWeek) return "Apps used this week"
    return when (range.dayIndex) {
        6 -> "Apps used today"
        5 -> "Apps used yesterday"
        else -> days.getOrNull(range.dayIndex ?: 6)?.let {
            "Apps used on " + SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(it.startOfDay))
        } ?: "Apps used"
    }
}

