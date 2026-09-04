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
import io.github.sebastianyousef.heed.focus.AppCategory
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
    val openDays by vm.usageOpenDays.collectAsState()
    val rows by vm.rangeApps.collectAsState()
    val categories by vm.dayCategories.collectAsState()
    val strandedRules by vm.rulesNeedingScreenAccess.collectAsState()
    val range by vm.range.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var usageGranted by remember { mutableStateOf(UsageTracker.hasPermission(context)) }
    var watcherEnabled by remember { mutableStateOf(ScrollWatcherService.isEnabled(context)) }

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
            if (!watcherEnabled && strandedRules.isNotEmpty()) {
                item { ScreenAccessLostBanner(strandedRules, context) }
            }

            item {
                UsageChartCard(
                    timeDays = days,
                    openDays = openDays,
                    selectedDay = range.dayIndex,
                    onSelect = { vm.selectRange(UsageRange(it)) },
                    categories = categories,
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
                        .background(categoryColor(rule?.category ?: AppCategory.NEUTRAL))
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
        if (rule.excludedFromStats) add("not counted")
        if (rule.category != AppCategory.NEUTRAL) add(categoryLabel(rule.category).lowercase())
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * The other failure this app must never hide.
 *
 * The inbox already refuses to hide a dead notification listener, on the grounds that an
 * empty inbox and a broken one look identical. Screen access has exactly the same
 * property and had no such warning: switch it off — and Heed's own step-aside offers you
 * a button that does precisely that when you open your bank — and every scroll rule goes
 * quiet while continuing to display as set. The app then looks like it is working and is
 * doing nothing, which is the worst way for it to fail.
 *
 * Android will not let an app re-enable its own accessibility service, by design, so this
 * cannot offer a fix in one tap. What it can do is name what has stopped, list the rules
 * it has stopped, and put the system screen one tap away.
 *
 * Shown only when something actually depends on it. A warning that fires for people who
 * never turned screen access on is one everybody learns to scroll past.
 */
@Composable
private fun ScreenAccessLostBanner(
    stranded: List<FocusRule>,
    context: android.content.Context,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Screen access is off",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Nothing about scrolling is running: not blocking a feed, not the " +
                    "scrolling budget, not breaking the feed. " +
                    stranded.take(3).joinToString { it.appLabel } +
                    (if (stranded.size > 3) " and ${stranded.size - 3} more" else "") +
                    " still show their rules and are not being enforced.\n\n" +
                    "Time limits, opens, bedtime and the grey screen are unaffected — " +
                    "those never needed it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Heed cannot switch this back on itself; Android does not allow it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Open accessibility settings") }
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

