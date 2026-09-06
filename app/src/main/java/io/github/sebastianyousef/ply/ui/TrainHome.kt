package io.github.sebastianyousef.ply.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sebastianyousef.keel.core.Time
import io.github.sebastianyousef.keel.ui.GroupHeading
import io.github.sebastianyousef.keel.ui.ValueRow
import io.github.sebastianyousef.ply.data.SessionSummary
import io.github.sebastianyousef.ply.train.Load

/**
 * What you see when no session is running: one button, and what you did last.
 *
 * The button is deliberately the only thing above the fold. Everything else on this screen
 * is a record of the past, and the past is not what someone opening a training app in a
 * gym is there for — they are there to start, and every row between them and starting is a
 * row they scroll past every single time.
 */
@Composable
fun TrainHome(
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    model: HistoryViewModel = viewModel(),
) {
    val history by model.history.collectAsStateWithLifecycle()
    val unit by model.unit.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 8.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                "Start a session",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (history.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Nothing logged yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn {
            item { GroupHeading("Recent sessions") }
            items(history, key = { it.id }) { SessionCard(it, unit) }
        }
    }
}

@Composable
private fun SessionCard(summary: SessionSummary, unit: Load.Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Time.relative(summary.startedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            ValueRow("Working sets", summary.workingSets.toString())
            ValueRow("Exercises", summary.exerciseCount.toString())
            ValueRow(
                // Named for what it is rather than "volume", because volume is two
                // different numbers and this is only one of them.
                "Weight moved",
                "${Load.format((summary.tonnageGrams / 1000).toInt() * 1000, unit)} ${unit.label}",
            )
            summary.endedAt?.let {
                ValueRow("Length", Time.duration(it - summary.startedAt))
            }
        }
    }
}
