package io.github.sebastianyousef.ply.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sebastianyousef.keel.ui.Explain
import io.github.sebastianyousef.keel.ui.GroupHeading
import io.github.sebastianyousef.keel.ui.SettingRow
import io.github.sebastianyousef.keel.ui.ValueRow
import io.github.sebastianyousef.ply.data.PlyDatabase
import io.github.sebastianyousef.ply.train.Load
import io.github.sebastianyousef.ply.train.OneRepMax
import io.github.sebastianyousef.ply.train.Volume

/**
 * The handful of things worth choosing, and an account of what the numbers mean.
 *
 * The second half of that is not padding. Every figure in a training app is the output of
 * a convention somebody picked — what counts as a set, what an estimate is worth, when a
 * record is a record — and an app that prints those without saying which convention it
 * used is asking to be believed rather than checked. The conventions are stated here, once,
 * where they can be read and disagreed with.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    session: SessionViewModel,
    modifier: Modifier = Modifier,
    model: SettingsViewModel = viewModel(),
) {
    val unit by model.unit.collectAsStateWithLifecycle()
    val increment by model.increment.collectAsStateWithLifecycle()
    val rest by model.rest.collectAsStateWithLifecycle()
    val autoRest by model.autoRest.collectAsStateWithLifecycle()
    val goal by model.goal.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        GroupHeading("Weights")
        Section {
            SettingRow(
                title = "Unit",
                subtitle = "Everything is stored in grams either way",
                detail = "Storage is unit-agnostic and exact — a whole number of grams — " +
                    "so switching this changes only what is printed. Nothing is converted " +
                    "and nothing is rounded, which is why a weight loaded in one unit can " +
                    "read with two decimals in the other.",
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Load.Unit.entries.forEach { candidate ->
                        FilterChip(
                            selected = unit == candidate,
                            onClick = { model.setUnit(candidate) },
                            label = { Text(candidate.label) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Step size", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val options = if (unit == Load.Unit.KG) Load.KG_INCREMENTS else Load.LB_INCREMENTS
                options.forEach { grams ->
                    FilterChip(
                        selected = increment == grams,
                        onClick = { model.setIncrement(grams) },
                        label = { Text("${Load.format(grams, unit)} ${unit.label}") },
                    )
                }
            }
            Explain(
                short = "Why",
                detail = "How far one press of the plus or minus moves the weight on the " +
                    "logging screen. 2.5 kg is the smallest jump most gyms can actually " +
                    "make with a pair of plates; pick smaller only if you own microplates.",
            )
        }

        GroupHeading("Rest")
        Section {
            SettingRow(
                title = "Start the timer automatically",
                subtitle = if (autoRest) "When a set is logged" else "Never",
                detail = "The alternative is a prompt after every set asking whether to " +
                    "start one, which is a second tap on every set to save one tap on the " +
                    "few where you did not want it.",
            ) {
                Switch(checked = autoRest, onCheckedChange = model::setAutoRest)
            }
            Spacer(Modifier.height(10.dp))
            Text("Default rest", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(60, 90, 120, 150, 180, 240, 300).forEach { seconds ->
                    FilterChip(
                        selected = rest == seconds,
                        onClick = { model.setRest(seconds) },
                        label = { Text("${seconds / 60}:${"%02d".format(seconds % 60)}") },
                    )
                }
            }
            Explain(
                short = "Why an exercise can override this",
                detail = "The right rest differs by an order of magnitude — twenty seconds " +
                    "after curls, five minutes after a heavy squat — so a single global " +
                    "figure is wrong for one of them and gets ignored for both. An " +
                    "exercise with its own rest uses that instead.",
            )
        }

        GroupHeading("Steps")
        Section {
            Text("Daily goal", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5_000, 6_000, 8_000, 10_000, 12_000).forEach { steps ->
                    FilterChip(
                        selected = goal == steps,
                        onClick = { model.setGoal(steps) },
                        label = { Text("%,d".format(steps)) },
                    )
                }
            }
            Explain(
                short = "Changing this does not rewrite the past",
                detail = "The goal in force on a day is stored with that day, so raising " +
                    "it does not turn a month of days you met into days you missed.",
            )
        }

        GroupHeading("What the numbers mean")
        Section {
            ValueRow("Estimated max", "Epley, refused above ${OneRepMax.MAX_REPS} reps")
            Explain(
                short = "Why it refuses",
                detail = "Every one-rep-max formula agrees closely up to about five reps " +
                    "and diverges sharply above it, so the choice between them only " +
                    "matters where none of them is trustworthy. Above twelve reps the " +
                    "estimate is measuring how long you can tolerate discomfort rather " +
                    "than how strong you are, so Ply prints nothing instead of a number " +
                    "it cannot stand behind. A trend line drawn through a bad estimate " +
                    "looks exactly as authoritative as one drawn through a good one.",
            )
            Spacer(Modifier.height(8.dp))
            ValueRow("Hard sets", "Warm-ups excluded, secondaries at ${Volume.SECONDARY_SHARE}")
            Explain(
                short = "Why a half",
                detail = "A bench press is not zero triceps work and it is not a triceps " +
                    "set either, and both tidy answers are visibly wrong to anyone who " +
                    "trains. Half is a convention rather than a measurement, which is why " +
                    "it is written here rather than presented as a fact. Tonnage is shown " +
                    "beside it because the two disagree: hard sets ignore load entirely, " +
                    "and tonnage is dominated by whatever moves the most mass.",
            )
            Spacer(Modifier.height(8.dp))
            ValueRow("Records", "Three kinds, never merged")
            Explain(
                short = "Why three",
                detail = "Heaviest ever, best estimated max, and heaviest at a given rep " +
                    "count disagree constantly — a heavy single beats every set of ten on " +
                    "the first and loses on the second. Each is shown as what it is, and " +
                    "none of them is called the record. Records are judged against what " +
                    "was true before the set, so a set never beats itself.",
            )
        }

        GroupHeading("About")
        Section {
            ValueRow("Version", io.github.sebastianyousef.ply.BuildConfig.VERSION_NAME)
            ValueRow("Storage format", PlyDatabase.SCHEMA_VERSION.toString())
            ValueRow("Android", android.os.Build.VERSION.SDK_INT.toString())
            ValueRow("Network access", "None")
            Explain(
                short = "What that last line means",
                detail = "Ply has no INTERNET permission. That is not a promise about the " +
                    "code: Android puts a process in the inet group only when the " +
                    "permission is granted, so the kernel refuses the syscall. There is no " +
                    "code path, bug or dependency that can send any of this anywhere, and " +
                    "the build fails if the permission ever appears in the merged manifest.",
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}
