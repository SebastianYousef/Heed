package io.github.sebastianyousef.ply.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sebastianyousef.keel.ui.GroupHeading
import io.github.sebastianyousef.ply.data.Exercise

/**
 * Choosing what you are about to do, out of eight hundred and seventy-six things.
 *
 * The list is long enough that a plain alphabetical scroll is unusable, and the honest
 * observation is that almost nobody picks out of the long tail: you do the same fifteen
 * movements, and the one you want next is nearly always one you did recently. So the
 * screen leads with what you have actually done, most recent first, and the full library
 * is underneath for the day it is not.
 *
 * Search matches on the name only. Matching the instructions as well sounds generous and
 * makes the results incomprehensible — typing "bench" would return every exercise whose
 * description mentions lying on one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExercisePicker(
    onPick: (Exercise) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    model: LibraryViewModel = viewModel(),
) {
    val all by model.exercises.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf<String?>(null) }

    val results = remember(all, query, muscle) {
        all.asSequence()
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .filter { muscle == null || muscle in it.primary }
            .sortedBy { it.name }
            .toList()
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search exercises") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MUSCLES.forEach { name ->
                FilterChip(
                    selected = muscle == name,
                    onClick = { muscle = if (muscle == name) null else name },
                    label = { Text(name.replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            if (query.isBlank() && muscle == null && recent.isNotEmpty()) {
                item { GroupHeading("Recent") }
                items(recent, key = { "recent-${it.id}" }) { Row(it, onPick) }
                item {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    GroupHeading("Everything")
                }
            }
            items(results, key = { it.id }) { Row(it, onPick) }
        }
    }
}

@Composable
private fun Row(exercise: Exercise, onPick: (Exercise) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(exercise) }
            .padding(vertical = 11.dp)
    ) {
        Text(
            exercise.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            listOfNotNull(
                exercise.equipment?.replaceFirstChar { it.uppercase() },
                exercise.primary.joinToString(", ").ifBlank { null },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The dataset's own seventeen muscles, in the order a body is usually thought about
 * rather than alphabetically — pushing, then pulling, then legs.
 *
 * Hard-coded rather than read from the data, because it is a closed vocabulary that only
 * changes when the vendored file does, and deriving it would mean a query on every open
 * to produce a list that is identical every time.
 */
private val MUSCLES = listOf(
    "chest", "shoulders", "triceps",
    "lats", "middle back", "biceps", "traps", "forearms",
    "quadriceps", "hamstrings", "glutes", "calves",
    "abdominals", "lower back", "abductors", "adductors", "neck",
)
