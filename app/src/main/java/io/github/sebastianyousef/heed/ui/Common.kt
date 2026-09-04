package io.github.sebastianyousef.heed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape

fun relativeTime(timestamp: Long): String =
    io.github.sebastianyousef.heed.core.Time.relative(timestamp)

/** Colour-codes a score so the inbox is scannable without reading numbers. */
@Composable
fun ScoreChip(score: Float, forced: Boolean, modifier: Modifier = Modifier) {
    val (bg, label) = when {
        forced -> MaterialTheme.colorScheme.tertiaryContainer to "always"
        score >= 0.7f -> MaterialTheme.colorScheme.primaryContainer to "${(score * 100).toInt()}"
        score >= 0.45f -> MaterialTheme.colorScheme.secondaryContainer to "${(score * 100).toInt()}"
        else -> MaterialTheme.colorScheme.surfaceVariant to "${(score * 100).toInt()}"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = onContainerFor(bg),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Deliberately not Material3's own `contentColorFor`, which returns
 * [Color.Unspecified] for a colour it does not recognise — and did, silently, for the
 * surfaceVariant branch below. Named differently so the two cannot be confused.
 */
@Composable
private fun onContainerFor(background: Color): Color = when (background) {
    MaterialTheme.colorScheme.primaryContainer -> MaterialTheme.colorScheme.onPrimaryContainer
    MaterialTheme.colorScheme.secondaryContainer -> MaterialTheme.colorScheme.onSecondaryContainer
    MaterialTheme.colorScheme.tertiaryContainer -> MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Hour gridlines behind a bar chart.
 *
 * Bars scaled to their own peak show you the shape of a week and nothing else: a good
 * week and a terrible one draw the same picture, because the tallest bar is always full
 * height. That is the right default — the shape is the signal most days — but it means
 * the chart cannot answer "is that a lot", which is the question anyone actually has.
 *
 * Lines at whole hours put the answer back without giving up the scaling. The bars still
 * fill the space, and a glance at how many lines the tallest one crosses says four hours
 * where before it said "the most this week". The step widens past six hours so a heavy
 * week does not turn into a ruled page.
 *
 * Drawn behind the bars deliberately: a gridline over a bar reads as a division in the
 * data rather than a reference behind it.
 */
@Composable
fun HourGrid(peakMs: Long, height: Dp, color: Color, modifier: Modifier = Modifier) {
    if (peakMs < HOUR_MS) return
    val hours = (peakMs / HOUR_MS).toInt()
    val step = when {
        hours <= 6 -> 1
        hours <= 12 -> 2
        else -> 4
    }
    Box(modifier.fillMaxWidth().height(height)) {
        var hour = step
        while (hour <= hours) {
            val fraction = (hour * HOUR_MS).toFloat() / peakMs
            val y = height * fraction
            // The line starts after the gutter so it never runs underneath its own
            // label, and the label sits centred on it rather than above it — stacked
            // above, the topmost one drifts off the chart entirely on a heavy day.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = -y)
                    .padding(start = HOUR_GRID_GUTTER)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(color.copy(alpha = 0.18f))
            )
            Text(
                "${hour}h",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(y = -y + 7.dp),
            )
            hour += step
        }
    }
}

/**
 * The strip down the left of a chart that belongs to the hour labels.
 *
 * Callers inset their bars by exactly this, so a label can never be drawn on top of a
 * bar — which is what happened when the grid was first added and Sunday's bar sat
 * underneath "1h" and "2h".
 */
val HOUR_GRID_GUTTER = 26.dp

private const val HOUR_MS = 3_600_000L
