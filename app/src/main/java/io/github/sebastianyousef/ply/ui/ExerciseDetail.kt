package io.github.sebastianyousef.ply.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.sebastianyousef.keel.ui.Explain
import io.github.sebastianyousef.keel.ui.GroupHeading
import io.github.sebastianyousef.keel.ui.KeelTrend
import io.github.sebastianyousef.keel.ui.ValueRow
import io.github.sebastianyousef.ply.data.Exercise
import io.github.sebastianyousef.ply.data.PlyRepository
import io.github.sebastianyousef.ply.data.WorkSet
import io.github.sebastianyousef.ply.train.Load
import io.github.sebastianyousef.ply.train.OneRepMax
import io.github.sebastianyousef.ply.train.Plates
import io.github.sebastianyousef.ply.train.PreviousBests
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One exercise: what it is, what you have done on it, and how to load the bar for it.
 *
 * The instructions are here, and they are the reason the vendored dataset is worth its
 * 800 KB — an exercise library without them is a list of names you already knew. They are
 * last on the screen rather than first, because someone opening this while training wants
 * the numbers and someone opening it to learn the movement will scroll.
 */
@Composable
fun ExerciseDetail(
    exerciseId: String,
    modifier: Modifier = Modifier,
    model: ExerciseDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val exercise by model.exercise.collectAsStateWithLifecycle()
    val bests by model.bests.collectAsStateWithLifecycle()
    val trend by model.trend.collectAsStateWithLifecycle()
    val unit by model.unit.collectAsStateWithLifecycle()
    val stock by model.stock.collectAsStateWithLifecycle()
    val bar by model.bar.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(exerciseId) { model.load(exerciseId) }

    val current = exercise ?: return

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Text(
            current.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            listOfNotNull(
                current.equipment?.replaceFirstChar { it.uppercase() },
                current.mechanic?.replaceFirstChar { it.uppercase() },
                current.primary.joinToString(", ").ifBlank { null },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GroupHeading("Your bests")
        Section {
            if (bests.heaviestGrams == 0) {
                Text(
                    "Nothing logged for this yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ValueRow(
                    "Heaviest ever",
                    "${Load.format(bests.heaviestGrams, unit)} ${unit.label}",
                    emphasis = true,
                )
                ValueRow(
                    "Best estimated max",
                    if (bests.estimatedGrams > 0) {
                        "${Load.format(bests.estimatedGrams, unit)} ${unit.label}"
                    } else {
                        "no estimate yet"
                    },
                )
                Spacer(Modifier.height(4.dp))
                bests.heaviestAtReps.entries.sortedBy { it.key }.forEach { (reps, grams) ->
                    ValueRow("Best at $reps", "${Load.format(grams, unit)} ${unit.label}")
                }
                Explain(
                    short = "Why these are separate numbers",
                    detail = "They disagree, and merging them would hide which one moved. " +
                        "A heavy single beats every set of ten on the first line and loses " +
                        "on the second. Estimates come from Epley and are refused above " +
                        "${OneRepMax.MAX_REPS} reps, so a set longer than that appears in " +
                        "the first line and never in the second.",
                )
            }
        }

        if (trend.size >= 2) {
            GroupHeading("Estimated max over time")
            Section {
                KeelTrend(points = trend.map { it.toFloat() })
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(
                        "${Load.format(trend.last(), unit)} ${unit.label}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    val change = trend.last() - trend.first()
                    Text(
                        (if (change >= 0) "+" else "") +
                            "${Load.format(change, unit)} ${unit.label} over ${trend.size} sets",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Explain(
                    short = "Why the points are evenly spaced",
                    detail = "The horizontal axis is the order of the sets, not the date. " +
                        "Spacing by date would give most of the width to the weeks you did " +
                        "not train, which is the opposite of what the line is for.",
                )
            }
        }

        bests.heaviestGrams.takeIf { it > 0 }?.let { heaviest ->
            Plates.plan(heaviest, bar, stock)?.let { plan ->
                GroupHeading("Loading your heaviest")
                Section {
                    ValueRow(
                        "Per side",
                        plan.perSide.joinToString(" + ") { Load.format(it, unit) }
                            .ifBlank { "just the bar" },
                    )
                    ValueRow("Bar", "${Load.format(bar, unit)} ${unit.label}")
                    if (!plan.exact) {
                        ValueRow(
                            "Short by",
                            "${Load.format(plan.shortfallGrams, unit)} ${unit.label}",
                        )
                        Explain(
                            short = "Why it does not just round",
                            detail = "Your plate inventory cannot make this weight exactly. " +
                                "Silently rounding to what it can make is how a log stops " +
                                "matching what was actually on the bar.",
                        )
                    }
                }
            }
        }

        if (current.steps.isNotEmpty()) {
            GroupHeading("How to do it")
            Section {
                current.steps.forEachIndexed { index, step ->
                    Row(Modifier.padding(bottom = 8.dp)) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
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

class ExerciseDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlyRepository.get(app)
    private val id = MutableStateFlow<String?>(null)

    private val _exercise = MutableStateFlow<Exercise?>(null)
    val exercise: StateFlow<Exercise?> = _exercise.asStateFlow()

    private val _bests = MutableStateFlow(PreviousBests())
    val bests: StateFlow<PreviousBests> = _bests.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val trend: StateFlow<List<Int>> = id
        .flatMapLatest { current ->
            current?.let { repository.dao.estimateTrend(it) } ?: flowOf(emptyList<WorkSet>())
        }
        .map { sets -> sets.mapNotNull { it.e1rmGrams } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unit: StateFlow<Load.Unit> = repository.settings.unit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Unit.KG)

    val stock: StateFlow<List<Plates.Stock>> = repository.settings.plateStock
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Plates.DEFAULT_STOCK)

    val bar: StateFlow<Int> = repository.settings.barGrams
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Plates.DEFAULT_BAR_GRAMS)

    fun load(exerciseId: String) = viewModelScope.launch {
        id.value = exerciseId
        _exercise.value = repository.dao.exercise(exerciseId)
        // Judged against everything, rather than against everything before a moment, because
        // this is a display of the current state rather than a decision about a new set.
        _bests.value = repository.previousBests(exerciseId, Long.MAX_VALUE)
    }
}
