package io.github.sebastianyousef.ply.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sebastianyousef.keel.ui.ChartBar
import io.github.sebastianyousef.keel.ui.Explain
import io.github.sebastianyousef.keel.ui.GridLine
import io.github.sebastianyousef.keel.ui.GroupHeading
import io.github.sebastianyousef.keel.ui.Keel
import io.github.sebastianyousef.keel.ui.KeelBarChart
import io.github.sebastianyousef.keel.ui.KeelRing
import io.github.sebastianyousef.ply.move.StepSensor
import io.github.sebastianyousef.ply.move.StepWorker

/**
 * Steps: today as a ring, the week as a shape.
 *
 * Two figures and nothing else. Distance is not shown because it would be a stride length
 * multiplied by a step count, and the stride length would be a guess from a height the app
 * never asked for — a number computed from two assumptions is not a measurement. Calories
 * are not shown for the same reason, only more so.
 */
@Composable
fun MoveScreen(
    modifier: Modifier = Modifier,
    model: StepsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val days by model.week.collectAsStateWithLifecycle()
    val goal by model.goal.collectAsStateWithLifecycle()
    val today by model.today.collectAsStateWithLifecycle()
    val permitted by model.permitted.collectAsStateWithLifecycle()

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        model.refreshPermission()
        if (granted) StepWorker.readNow(context)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (!StepSensor.available(context)) {
            // Never collapsed behind a chevron. A warning whose explanation is one tap away
            // is one you have to opt into understanding, which is how it becomes decoration.
            Notice(
                "This phone has no step counter.",
                "Step counting needs a hardware pedometer, which this device does not " +
                    "report. Nothing here will ever fill in. The Training half is unaffected.",
            )
            return@Column
        }

        if (!permitted) {
            Notice(
                "Ply cannot see your steps yet.",
                "Android counts steps in hardware and will not hand the figure to an app " +
                    "without the activity recognition permission. Ply reads that counter " +
                    "roughly every fifteen minutes and writes the difference down. It is " +
                    "the only permission the Movement half needs, and refusing it costs " +
                    "nothing else in the app.",
            )
            Spacer(Modifier.height(12.dp))
            Button({ request.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) {
                Text("Allow step counting")
            }
            return@Column
        }

        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.6f),
            contentAlignment = Alignment.Center,
        ) {
            KeelRing(
                progress = if (goal > 0) today.toFloat() / goal else 0f,
                modifier = Modifier.size(180.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "%,d".format(today),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (goal > 0) "of ${"%,d".format(goal)}" else "steps",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        GroupHeading("This week")
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                KeelBarChart(
                    bars = days.map { ChartBar(label = it.label, initial = it.initial, value = it.steps.toFloat()) },
                    gridlines = if (goal > 0) listOf(GridLine(goal.toFloat(), "goal")) else emptyList(),
                    accent = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Explain(
                    short = "Why the shape and not the total",
                    detail = "The bars are scaled to the busiest day of the week rather " +
                        "than to a fixed ceiling, because an absolute axis makes a good " +
                        "week and a bad one look nearly identical. The goal line is what " +
                        "puts the absolute reading back, so the chart can show the shape " +
                        "and still answer whether that was a lot.",
                )
            }
        }
    }
}

@Composable
private fun Notice(headline: String, detail: String) {
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Keel.semantics.warning,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
