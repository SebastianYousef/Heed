package io.github.sebastianyousef.ply.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sebastianyousef.keel.ui.Keel
import io.github.sebastianyousef.keel.ui.KeelStepper
import io.github.sebastianyousef.ply.data.SetKind
import io.github.sebastianyousef.ply.data.SetWithExercise
import io.github.sebastianyousef.ply.train.Load
import io.github.sebastianyousef.ply.train.RecordKind

/**
 * The screen the whole app is for.
 *
 * Everything here is arranged around one number: how many times you touch the phone to
 * record a set while standing in a gym between two of them. The answer is **one**, and the
 * second and third things on this screen exist to keep it that way rather than to add to
 * what it can do.
 *
 * ### Where the taps went
 *
 * Repeating what you just did — which is the overwhelming majority of sets — is one tap on
 * Log. The weight and reps are already correct because they were prefilled from the last
 * set of this exercise in this session; see [SessionViewModel.select].
 *
 * Adding weight is one tap on `+` and then Log: two. Dropping a rep is one on `−` and then
 * Log: two. A large jump is a tap on the number itself, which opens a pad — three or four,
 * and rare, which is the right place to put the cost.
 *
 * ### What is deliberately not here
 *
 * **No keyboard.** Every app in this category makes the weight a text field, which means
 * summoning a keyboard that covers the screen, clearing the old value, typing, and
 * dismissing it — six or seven interactions for "the same as last time but five kilos
 * more". The stepper is the whole difference.
 *
 * **No confirmation.** Logging is immediate and undoable, rather than deferred and
 * confirmed. A dialog asking whether you meant it costs a tap on every set to save a tap
 * on the rare wrong one.
 *
 * **No RPE field before the set.** It is offered on the row afterwards, where leaving it
 * alone costs nothing. A field between you and the record is a field that stops you
 * recording.
 *
 * **No rest-timer prompt.** It starts itself, and lives in the notification shade. Asking
 * "start a timer?" after every set is a second tap on every set.
 */
@Composable
fun SessionScreen(
    model: SessionViewModel,
    onPickExercise: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onStartRest: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending by model.pending.collectAsStateWithLifecycle()
    val logged by model.setsForCurrent.collectAsStateWithLifecycle()
    val unit by model.unit.collectAsStateWithLifecycle()
    val flash by model.flash.collectAsStateWithLifecycle()
    val plan by model.plan.collectAsStateWithLifecycle()
    val allSets by model.sets.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (plan.isNotEmpty()) {
            PlanStrip(
                plan = plan,
                doneFor = { id -> allSets.count { it.exerciseId == id && it.kind == SetKind.WORKING } },
                currentId = pending.exercise?.id,
                onSelect = { model.selectPlanned(it) },
            )
        }

        if (pending.exercise == null) {
            EmptyExercise(onPickExercise)
            return@Column
        }

        ExerciseHeader(
            name = pending.exercise?.name.orEmpty(),
            onChange = onPickExercise,
            // The name itself opens what is known about the exercise: your bests, the
            // estimate trend, how to load the bar for it, and how to do it. Behind the name
            // rather than behind a button, because it is the thing you would tap anyway.
            onOpen = { pending.exercise?.let { onOpenExercise(it.id) } },
        )

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(logged, key = { _, set -> set.id }) { position, set ->
                LoggedRow(
                    set = set,
                    unit = unit,
                    // Working sets are numbered among themselves. Counting warm-ups would
                    // make the first real set of a session read as the third, and the
                    // number on the button below disagree with the number on the row.
                    index = logged.take(position + 1).count { it.kind == SetKind.WORKING },
                    records = flash?.takeIf { it.setId == set.id }?.records.orEmpty(),
                    onDelete = { model.delete(set) },
                    onRate = { model.rate(set, it) },
                )
            }
        }

        LogControls(
            pending = pending,
            unit = unit,
            setNumber = logged.count { it.kind == SetKind.WORKING } + 1,
            onStepWeight = model::stepWeight,
            onStepReps = model::stepReps,
            onToggleWarmUp = {
                model.setKind(if (pending.kind == SetKind.WARMUP) SetKind.WORKING else SetKind.WARMUP)
            },
            onLog = { model.log(onRest = onStartRest) },
        )
    }
}

/**
 * The routine, as a row you can move along — and skip about in.
 *
 * It shows how many working sets each planned exercise has, against how many were asked
 * for, and it never blocks anything: an exercise not in the plan can still be logged, one
 * in it can be left at zero, and the strip simply reports what happened. There is no
 * "off plan" state because there is no state a plan can put you in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanStrip(
    plan: List<io.github.sebastianyousef.ply.data.PlannedRow>,
    doneFor: (String) -> Int,
    currentId: String?,
    onSelect: (io.github.sebastianyousef.ply.data.PlannedRow) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        plan.forEach { planned ->
            val done = doneFor(planned.item.exerciseId)
            val complete = done >= planned.item.targetSets
            FilterChip(
                selected = planned.item.exerciseId == currentId,
                onClick = { onSelect(planned) },
                label = { Text("${planned.exerciseName}  $done/${planned.item.targetSets}") },
                leadingIcon = if (complete) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun EmptyExercise(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing on the bar yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Pick an exercise and the weight and reps will already be what you did last time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Button(onPick) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose an exercise")
        }
    }
}

@Composable
private fun ExerciseHeader(name: String, onChange: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).clickable { onOpen() },
        )
        TextButton(onChange) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Change")
        }
    }
}

/**
 * A set already recorded: what it was, and — only once it exists — how hard it felt.
 *
 * The RPE chips appear on the row rather than in the entry controls, which is the whole
 * reason they can exist at all. Asked before the set they would be a field standing
 * between a person and the log button; offered after it they are a thing you can ignore
 * forever at no cost.
 */
@Composable
private fun LoggedRow(
    set: SetWithExercise,
    unit: Load.Unit,
    index: Int,
    records: Set<RecordKind>,
    onDelete: () -> Unit,
    onRate: (Float?) -> Unit,
) {
    val warmUp = set.kind == SetKind.WARMUP
    var rating by rememberSaveable(set.id) { mutableStateOf(false) }
    val onToggleRating = { rating = !rating }

    Column {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    if (warmUp) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (warmUp) "W" else index.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${Load.format(set.weightGrams, unit)} ${unit.label} × ${set.reps}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (warmUp) FontWeight.Normal else FontWeight.Medium,
                color = if (warmUp) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (records.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Keel.semantics.success,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        records.joinToString(", ") { it.phrase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = Keel.semantics.success,
                    )
                }
            }
        }
        Text(
            set.rpe?.let { "RPE ${rpeLabel(it)}" } ?: "RPE",
            style = MaterialTheme.typography.labelMedium,
            color = if (set.rpe == null) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggleRating() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.Close,
            contentDescription = "Delete this set",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).clickable { onDelete() },
        )
    }

    // Only once the set exists, and only when asked for. This is the whole reason RPE can
    // be in the app at all: asked before the set it would be a field standing between a
    // person and the log button, and offered here it is something you can ignore forever
    // at no cost.
    AnimatedVisibility(rating) {
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            RPE_VALUES.forEach { value ->
                FilterChip(
                    selected = set.rpe == value,
                    onClick = { onRate(if (set.rpe == value) null else value) },
                    label = { Text(rpeLabel(value)) },
                )
            }
        }
    }
    }
}

/** "8" or "8.5" — a rating is in halves, and a trailing .0 is noise in a row of them. */
private fun rpeLabel(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()

/**
 * Six to ten, in halves.
 *
 * Below six is not a rating anybody makes a decision from — it means the set was easy —
 * and above ten does not exist. Halves because the distinction people actually draw is
 * between "could have done two more" and "maybe one and a half", and whole numbers lose it.
 */
private val RPE_VALUES = listOf(6f, 6.5f, 7f, 7.5f, 8f, 8.5f, 9f, 9.5f, 10f)

/** Which record, in words. A trophy that does not say what it means is one you ignore. */
private fun RecordKind.phrase(): String = when (this) {
    RecordKind.HEAVIEST -> "heaviest ever"
    RecordKind.ESTIMATED -> "best estimated max"
    RecordKind.AT_REPS -> "best at these reps"
}

@Composable
private fun LogControls(
    pending: Pending,
    unit: Load.Unit,
    setNumber: Int,
    onStepWeight: (Boolean) -> Unit,
    onStepReps: (Boolean) -> Unit,
    onToggleWarmUp: () -> Unit,
    onLog: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeelStepper(
                    value = Load.format(pending.weightGrams, unit),
                    unit = unit.label,
                    onStep = onStepWeight,
                    modifier = Modifier.weight(1.25f),
                )
                Spacer(Modifier.width(6.dp))
                KeelStepper(
                    value = pending.reps.toString(),
                    unit = "reps",
                    onStep = onStepReps,
                    modifier = Modifier.weight(1f),
                )
            }

            AnimatedVisibility(pending.target != null) {
                Text(
                    pending.target?.let { target ->
                        buildString {
                            append("Planned: ")
                            append(target.targetSets)
                            append(" × ")
                            append(target.targetReps?.toString() ?: "—")
                            target.targetRepsMax?.let { append("–$it") }
                        }
                    }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }

            AnimatedVisibility(pending.previous != null) {
                Text(
                    "Last time: ${pending.previous}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onLog,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (pending.kind == SetKind.WARMUP) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                ),
            ) {
                Text(
                    if (pending.kind == SetKind.WARMUP) "Log warm-up" else "Log set $setNumber",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = pending.kind == SetKind.WARMUP,
                    onClick = onToggleWarmUp,
                    label = { Text("Warm-up") },
                )
            }
        }
    }
}
