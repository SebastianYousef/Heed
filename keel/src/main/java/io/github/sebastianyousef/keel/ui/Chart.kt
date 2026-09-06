package io.github.sebastianyousef.keel.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One coloured piece of a bar, and one row of the legend under it.
 *
 * Carries a resolved colour rather than a category, because what colours a slice differs
 * per app — a muscle group here, an app group there — and the chart should not have to
 * know which mechanism produced the piece it is drawing.
 *
 * A slice with no colour is the uncoloured bulk of the bar, which is deliberately the
 * default: a chart that shades every row says nothing, because the eye needs somewhere to
 * rest before a coloured segment means anything.
 */
@Immutable
data class ChartSlice(
    val label: String,
    val value: Float,
    val color: Color? = null,
)

/** One column. [initial] is the one or two characters under it; [label] names it in full. */
@Immutable
data class ChartBar(
    val label: String,
    val initial: String,
    val slices: List<ChartSlice> = emptyList(),
    val value: Float = slices.sumOf { it.value.toDouble() }.toFloat(),
)

/** A horizontal rule across the plot, with the figure it stands for written at the left. */
@Immutable
data class GridLine(val value: Float, val label: String)

/**
 * Bar order: named things on top, the uncoloured bulk at the bottom.
 *
 * A Column lays out in order, so this is literally top to bottom on screen. The neutral
 * remainder goes last because the eye reads it as the base the coloured parts sit on —
 * and because it is the piece that changes size most, so anything above it would move.
 *
 * A pure function rather than a sort at the call site, and the reason is the one an audit
 * of the previous app turned up: a bar that reshuffles its own colours between
 * recompositions is unreadable, and worse, looks like the data changing.
 */
fun orderSlices(slices: List<ChartSlice>): List<ChartSlice> =
    slices.filter { it.value > 0f }
        .sortedWith(compareBy({ if (it.color == null) 1 else 0 }, { -it.value }))

/**
 * The chart, in one place, at whatever scope the caller has.
 *
 * The previous app reached two of these — a whole-phone view and a per-app view answering
 * the same question at different scopes, written months apart into different files. What
 * that produced was two charts that behaved differently for no reason anybody chose: one
 * had chips and the other did not, one named the figure on the selected bar and the other
 * put it in a footer, one could be switched between a day and the week and the other could
 * not. None of those were decisions. A person cannot learn a control that works one way on
 * one screen and another way on the next, so there is one of these and the scope differs.
 *
 * Scaled to the peak rather than to a fixed ceiling: an absolute axis makes a good week
 * and a bad week look nearly identical, and the useful signal is the shape. The gridlines
 * are what put the absolute reading back, so it can show the shape and still answer "is
 * that a lot".
 *
 * @param selected index of the highlighted bar, or null for none. Hoisted, because what
 *        follows from a selection — a list below re-querying, a headline changing — is the
 *        caller's business and not the chart's.
 */
@Composable
fun KeelBarChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    gridlines: List<GridLine> = emptyList(),
    accent: Color = MaterialTheme.colorScheme.onSurface,
    height: androidx.compose.ui.unit.Dp = CHART_HEIGHT,
) {
    val haptics = LocalHapticFeedback.current
    val peak = (bars.maxOfOrNull { it.value } ?: 0f).coerceAtLeast(1f)

    Box(modifier.fillMaxWidth().height(height)) {
        if (gridlines.isNotEmpty()) GridLines(gridlines, peak, height, accent)
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(start = if (gridlines.isNotEmpty()) GRID_GUTTER else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEachIndexed { index, bar ->
                val isSelected = selected == index
                // Never quite zero, so an empty day is a visible nub rather than a gap the
                // eye reads as missing data.
                val fraction by animateFloatAsState(
                    targetValue = (bar.value / peak).coerceIn(0.03f, 1f),
                    label = "bar",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (onSelect == null) {
                                Modifier
                            } else {
                                Modifier.clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onSelect(index)
                                }
                            }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
                        Column(
                            Modifier
                                .fillMaxWidth(if (isSelected) 1f else 0.72f)
                                .fillMaxHeight(fraction)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 6.dp,
                                        topEnd = 6.dp,
                                        bottomStart = 2.dp,
                                        bottomEnd = 2.dp,
                                    )
                                ),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            val split = orderSlices(bar.slices)
                            if (split.isEmpty()) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight()
                                        .background(accent.copy(alpha = if (isSelected) 1f else 0.35f))
                                )
                            } else {
                                split.forEach { slice ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(slice.value)
                                            .background(sliceColor(slice, accent, isSelected))
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        bar.initial,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = accent.copy(alpha = if (isSelected) 1f else 0.6f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GridLines(
    lines: List<GridLine>,
    peak: Float,
    height: androidx.compose.ui.unit.Dp,
    accent: Color,
) {
    // The label row under the bars is not part of the plot, so the lines are placed
    // against the plot's own height rather than the box's — otherwise every line sits a
    // little low and the topmost one drifts into the bars.
    val plot = height - LABEL_STRIP
    Box(Modifier.fillMaxWidth().height(plot)) {
        lines.filter { it.value in 0f..peak }.forEach { line ->
            val offset = plot * (1f - line.value / peak)
            Row(
                Modifier.fillMaxWidth().padding(top = offset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    line.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.45f),
                    modifier = Modifier.width(GRID_GUTTER),
                )
                Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.12f)))
            }
        }
    }
}

private fun sliceColor(slice: ChartSlice, accent: Color, selected: Boolean): Color =
    (slice.color ?: accent).copy(alpha = if (selected) 1f else if (slice.color != null) 0.65f else 0.35f)

/**
 * Tall enough for four gridlines to be told apart, short enough that whatever is
 * underneath the chart — the list the selection filters — is still on screen.
 */
private val CHART_HEIGHT = 104.dp

/** Room for the initials under the bars, excluded from the plot the gridlines scale to. */
private val LABEL_STRIP = 22.dp

/** Somewhere for the gridline labels to stand, so they are not printed over the first bar. */
private val GRID_GUTTER = 26.dp
