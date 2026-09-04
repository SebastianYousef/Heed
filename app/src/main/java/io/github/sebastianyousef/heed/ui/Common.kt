package io.github.sebastianyousef.heed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
