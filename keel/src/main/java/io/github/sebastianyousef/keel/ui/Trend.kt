package io.github.sebastianyousef.keel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A line through a series, for the question a bar chart cannot answer.
 *
 * Bars are for comparing discrete buckets — this Tuesday against last Tuesday. A trend is
 * for direction over irregular intervals, which is what a strength estimate is: you did not
 * train on a schedule, the points are not evenly spaced in time, and the useful reading is
 * whether the line is going up.
 *
 * Deliberately unlabelled and unaxised. It sits under a figure that states the current
 * value and the change, so repeating either on the chart would be decoration — and a chart
 * small enough to sit inside a card has no room to draw an axis legibly anyway.
 *
 * @param points y-values in order. X is the index, not a timestamp: the points are drawn
 *        evenly spaced because the alternative — spacing by date — devotes most of the
 *        width to the months you did not train, which is the opposite of the intent.
 */
@Composable
fun KeelTrend(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 56.dp,
) {
    if (points.size < 2) return

    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.matchParentSize()) {
            val low = points.min()
            val high = points.max()
            // A flat series has no range to scale to. Drawing it down the middle says
            // "no change", where dividing by zero or stretching noise to full height would
            // both claim something happened.
            val span = (high - low).takeIf { it > 0f }
            val stepX = size.width / (points.size - 1)

            val path = Path()
            points.forEachIndexed { index, value ->
                val x = stepX * index
                val y = span?.let { size.height * (1f - (value - low) / it) } ?: (size.height / 2f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )

            // The most recent point marked, because "where am I now" is the question the
            // line is being read to answer and the right-hand end is easy to lose.
            val lastY = span?.let { size.height * (1f - (points.last() - low) / it) }
                ?: (size.height / 2f)
            drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(size.width, lastY))
        }
    }
}
