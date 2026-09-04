package io.github.sebastianyousef.heed.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sebastianyousef.heed.core.Time
import io.github.sebastianyousef.heed.focus.AppCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The usage chart, in one place, because there were two of them and they had drifted.
 *
 * The whole-phone view and the per-app view answer the same question at different scopes,
 * and they were written months apart into different files. What that produced was two
 * charts that behaved differently for no reason anybody chose: one had Time/Opens chips
 * and the other did not; one named the exact figure on the selected bar and the other put
 * it in a footer; one could be switched between a day and the week and the other could
 * not. None of those were decisions — they were just what each file happened to grow.
 *
 * A user cannot learn a control that works one way on one screen and another way on the
 * next, so both now render this. The scope differs and nothing else does.
 *
 * @param onSelect a day index, or null for the whole week. The caller decides what else
 *        follows from that — on the Attention screen the app list underneath re-queries,
 *        which is why selection is hoisted here rather than kept inside.
 */
@Composable
fun UsageChartCard(
    timeDays: List<DayTotal>,
    openDays: List<DayTotal>,
    selectedDay: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    categories: Map<Int, Map<AppCategory, Long>> = emptyMap(),
) {
    val haptics = LocalHapticFeedback.current
    val weekday = remember { SimpleDateFormat("EEEE", Locale.getDefault()) }
    val initials = remember { SimpleDateFormat("EEEEE", Locale.getDefault()) }
    var showOpens by rememberSaveable { mutableStateOf(false) }

    val series = if (showOpens) openDays else timeDays
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer

    val periodMs = selectedDay?.let { timeDays.getOrNull(it)?.totalMs } ?: timeDays.sumOf { it.totalMs }
    val periodOpens = selectedDay?.let { openDays.getOrNull(it)?.totalMs } ?: openDays.sumOf { it.totalMs }

    // The comparison is the part that means something. Four hours is not a number anyone
    // can judge; four hours against your own average is. Averaged over days that actually
    // have data, so a fresh install does not divide by a week of zeroes.
    val average = timeDays.filter { it.totalMs > 0 }.map { it.totalMs }.average()
        .let { if (it.isNaN()) 0.0 else it }
    val delta = if (selectedDay == null || average <= 0) null else (periodMs - average).toLong()

    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading?.let {
                    it()
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        periodName(selectedDay, timeDays, weekday),
                        style = MaterialTheme.typography.labelLarge,
                        color = onContainer.copy(alpha = 0.75f),
                    )
                    Text(
                        if (showOpens) "$periodOpens opens" else Time.duration(periodMs),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = onContainer,
                    )
                    Text(
                        buildString {
                            // Always the metric you are *not* looking at, so both numbers
                            // are on screen and switching the chips never hides one.
                            append(if (showOpens) Time.duration(periodMs) else "$periodOpens opens")
                            delta?.let {
                                val magnitude = kotlin.math.abs(it)
                                if (magnitude >= 5 * 60_000L) {
                                    append(" · ")
                                    append(Time.duration(magnitude))
                                    append(if (it < 0) " below" else " above")
                                    append(" your average")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.75f),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(false to "Time", true to "Opens").forEach { (opens, label) ->
                    FilterChip(
                        selected = showOpens == opens,
                        onClick = { showOpens = opens },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = onContainer.copy(alpha = 0.75f),
                            selectedLabelColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedContainerColor = onContainer,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            UsageBars(
                series = series,
                selectedDay = selectedDay,
                showGrid = !showOpens,
                initials = initials,
                onContainer = onContainer,
                categories = if (showOpens) emptyMap() else categories,
            ) { index ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                // Tapping the selected bar returns to the week, so there is one control
                // rather than a second one that exists only to undo the first.
                onSelect(if (selectedDay == index) null else index)
            }

            // The split, in words, for the period on screen. A colour on its own tells
            // you there is a distinction and not which way round it goes.
            val split = categories.entries
                .filter { selectedDay == null || it.key == selectedDay }
                .flatMap { it.value.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { it.value.sum() }
            val sorted = listOf(AppCategory.PRODUCTIVE, AppCategory.DISTRACTING)
                .mapNotNull { c -> split[c]?.takeIf { it > 0 }?.let { c to it } }

            if (sorted.isNotEmpty() && !showOpens) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    sorted.forEach { (category, ms) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .padding(end = 5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(categoryColor(category))
                                    .height(9.dp)
                                    .width(9.dp)
                            )
                            Text(
                                "${Time.duration(ms)} ${categoryLabel(category).lowercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = onContainer.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                if (selectedDay == null) {
                    "Tap a day for that day alone"
                } else {
                    "Tap it again for the whole week"
                },
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Seven bars, scaled to the worst day.
 *
 * Scaled to the peak rather than to a fixed ceiling on purpose: an absolute axis makes a
 * good week and a bad week look nearly identical, and the useful signal here is the
 * shape. The gridlines are what put the absolute reading back, so the chart can show the
 * shape and still answer "is that a lot".
 */
@Composable
private fun UsageBars(
    series: List<DayTotal>,
    selectedDay: Int?,
    showGrid: Boolean,
    initials: SimpleDateFormat,
    onContainer: androidx.compose.ui.graphics.Color,
    categories: Map<Int, Map<AppCategory, Long>>,
    onSelect: (Int) -> Unit,
) {
    val peak = (series.maxOfOrNull { it.totalMs } ?: 0L).coerceAtLeast(1L)
    Box(Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        if (showGrid) HourGrid(peak, CHART_HEIGHT, onContainer)
        Row(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .padding(start = if (showGrid) HOUR_GRID_GUTTER else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            series.forEachIndexed { index, day ->
                val selected = selectedDay == index
                val fraction by animateFloatAsState(
                    targetValue = (day.totalMs.toFloat() / peak).coerceIn(0.03f, 1f),
                    label = "bar",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
                        // Split into what kind of time it was, where you have said. The
                        // segments are drawn bottom-up in a fixed order so a day never
                        // reshuffles its own colours between recompositions.
                        val split = categories[index].orEmpty()
                        Column(
                            Modifier
                                .fillMaxWidth(if (selected) 1f else 0.72f)
                                .fillMaxHeight(fraction)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 6.dp, topEnd = 6.dp,
                                        bottomStart = 2.dp, bottomEnd = 2.dp,
                                    )
                                ),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            val total = split.values.sum()
                            if (total <= 0L) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight()
                                        .background(onContainer.copy(alpha = if (selected) 1f else 0.35f))
                                )
                            } else {
                                // Top-down, because a Column lays out in order and the
                                // eye reads the neutral bulk as the base.
                                listOf(
                                    AppCategory.DISTRACTING,
                                    AppCategory.PRODUCTIVE,
                                    AppCategory.NEUTRAL,
                                ).forEach { category ->
                                    val ms = split[category] ?: 0L
                                    if (ms <= 0L) return@forEach
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(ms.toFloat())
                                            .background(
                                                if (category == AppCategory.NEUTRAL) {
                                                    onContainer.copy(alpha = if (selected) 1f else 0.35f)
                                                } else {
                                                    categoryColor(category)
                                                        .copy(alpha = if (selected) 1f else 0.65f)
                                                }
                                            )
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        initials.format(Date(day.startOfDay)),
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

/** Names the selected period, so nothing on either screen is ever ambiguous about it. */
fun periodName(selectedDay: Int?, days: List<DayTotal>, weekday: SimpleDateFormat): String =
    when (selectedDay) {
        null -> "Last 7 days"
        6 -> "Today"
        5 -> "Yesterday"
        else -> days.getOrNull(selectedDay)?.let { weekday.format(Date(it.startOfDay)) } ?: ""
    }

/**
 * Tall enough for four gridlines to be distinguishable, short enough that whatever is
 * underneath — the app list, or an app's rules — is still on screen.
 */
private val CHART_HEIGHT = 104.dp
