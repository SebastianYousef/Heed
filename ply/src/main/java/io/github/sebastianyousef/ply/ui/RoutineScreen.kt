package io.github.sebastianyousef.ply.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebastianyousef.keel.ui.Explain
import io.github.sebastianyousef.keel.ui.GroupHeading
import io.github.sebastianyousef.keel.ui.KeelStepper
import io.github.sebastianyousef.ply.data.Routine
import io.github.sebastianyousef.ply.train.Load

/**
 * Writing down a plan, without the plan becoming a cage.
 *
 * A routine here is an ordered list of exercises with *optional* targets. Every field can
 * be left empty, because "bench press, three sets" is a legitimate and common thing to
 * write down and an app that demands a weight for it forces you to invent one. Whatever is
 * missing is filled from what you did last time when the session starts.
 *
 * There is no progression scheme and no notion of compliance. Every automatic rule — add
 * 2.5 kg, double progression, autoregulate by RPE — is a claim about what you *should* lift
 * next, and it is wrong the first week you sleep badly. What the logging screen does instead
 * is show what you did last time, which is the same information without the instruction.
 */
@Composable
fun RoutineScreen(
    model: RoutineViewModel,
    onAddExercise: () -> Unit,
    onStart: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val routines by model.routines.collectAsStateWithLifecycle()
    val editingId by model.editingId.collectAsStateWithLifecycle()

    if (editingId != null) {
        RoutineEditor(model, onAddExercise, routines.firstOrNull { it.id == editingId })
        return
    }

    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (naming) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Routine name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    model.create(name) { }
                    name = ""
                    naming = false
                }) { Text("Create") }
                TextButton({ naming = false; name = "" }) { Text("Cancel") }
            }
        } else {
            Button(
                onClick = { naming = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New routine")
            }
        }

        if (routines.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "No routines yet. You do not need one — a session can be started empty and " +
                    "exercises added as you go. A routine only saves you choosing them again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn {
            item { GroupHeading("Your routines") }
            items(routines, key = { it.id }) { routine ->
                RoutineCard(routine, onEdit = { model.edit(routine.id) }, onStart = { onStart(routine.id) })
            }
        }
    }
}

@Composable
private fun RoutineCard(routine: Routine, onEdit: () -> Unit, onStart: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).clickable { onEdit() }) {
                Text(
                    routine.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    routine.lastUsedAt?.let {
                        "Last used ${io.github.sebastianyousef.keel.core.Time.relative(it)}"
                    } ?: "Never used",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onStart) { Text("Start") }
        }
    }
}

@Composable
private fun RoutineEditor(
    model: RoutineViewModel,
    onAddExercise: () -> Unit,
    routine: Routine?,
) {
    val items by model.items.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                routine?.name ?: "Routine",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            routine?.let {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete this routine",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp).clickable { model.delete(it) },
                )
                Spacer(Modifier.width(12.dp))
            }
            TextButton({ model.edit(null) }) { Text("Done") }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.item.id }) { planned ->
                PlannedRow(
                    planned = planned,
                    onChange = model::update,
                    onRemove = { model.remove(planned.item) },
                    onMove = { up -> model.move(planned.item, up) },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(onAddExercise, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add an exercise")
                }
                Explain(
                    short = "Targets are optional, and never enforced",
                    detail = "Anything left blank is filled from what you did last time " +
                        "when the session starts. During a session you can add an exercise " +
                        "that is not here, skip one that is, and change any weight — none " +
                        "of that puts the session 'off plan', because there is no such " +
                        "state. A routine saves you choosing exercises again; it does not " +
                        "own what you do.",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun PlannedRow(
    planned: io.github.sebastianyousef.ply.data.PlannedRow,
    onChange: (io.github.sebastianyousef.ply.data.RoutineItem) -> Unit,
    onRemove: () -> Unit,
    onMove: (Boolean) -> Unit,
) {
    val item = planned.item
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    planned.exerciseName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = "Move up",
                    modifier = Modifier.size(20.dp).clickable { onMove(true) },
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = "Move down",
                    modifier = Modifier.size(20.dp).clickable { onMove(false) },
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove from routine",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clickable { onRemove() },
                )
            }
            Spacer(Modifier.height(6.dp))
            Row {
                KeelStepper(
                    value = item.targetSets.toString(),
                    unit = "sets",
                    onStep = { up ->
                        onChange(item.copy(targetSets = (item.targetSets + if (up) 1 else -1).coerceIn(1, 20)))
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                KeelStepper(
                    value = item.targetReps?.toString() ?: "—",
                    unit = "reps",
                    onStep = { up ->
                        val next = (item.targetReps ?: 7) + if (up) 1 else -1
                        onChange(item.copy(targetReps = next.takeIf { it in 1..100 }))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
