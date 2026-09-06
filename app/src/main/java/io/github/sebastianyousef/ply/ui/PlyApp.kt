package io.github.sebastianyousef.ply.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sebastianyousef.keel.core.Time
import io.github.sebastianyousef.ply.train.RestTimerService

/**
 * The two halves, in one app, split the way the previous one was.
 *
 * Training and Movement answer different questions and share a database, a theme and a
 * widget. They are one app rather than two because the thing that makes a step counter
 * worth opening is the day you did not train, and the thing that makes a training log
 * worth opening is the day you did — so between them there is always a reason, and neither
 * on its own has one every day.
 */
private enum class Half(val label: String) {
    TRAIN("Training"),
    MOVE("Movement"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlyApp() {
    val context = LocalContext.current
    val model: SessionViewModel = viewModel()
    val session by model.session.collectAsStateWithLifecycle()
    val pending by model.pending.collectAsStateWithLifecycle()

    var half by rememberSaveable { mutableStateOf(Half.TRAIN) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var detailOf by rememberSaveable { mutableStateOf<String?>(null) }

    // A back press should close whatever is on top before it leaves the app, and a session
    // in progress should never be the thing back exits — losing the screen you are logging
    // into because you pressed back once is the failure this prevents.
    BackHandler(enabled = picking || settings || detailOf != null) {
        picking = false
        settings = false
        detailOf = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                settings -> "Settings"
                                detailOf != null -> "Exercise"
                                else -> half.label
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        session?.let {
                            Text(
                                "${it.title} · ${Time.duration(System.currentTimeMillis() - it.startedAt)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (half == Half.TRAIN && session != null && !settings) {
                        TextButton(onClick = { model.end() }) { Text("Finish") }
                    }
                    IconButton(onClick = { settings = !settings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            if (!settings) {
                NavigationBar {
                    NavigationBarItem(
                        selected = half == Half.TRAIN,
                        onClick = { half = Half.TRAIN },
                        icon = { Icon(Icons.Default.FitnessCenter, contentDescription = null) },
                        label = { Text("Training") },
                    )
                    NavigationBarItem(
                        selected = half == Half.MOVE,
                        onClick = { half = Half.MOVE },
                        icon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null) },
                        label = { Text("Movement") },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                settings -> SettingsScreen(model)
                detailOf != null -> ExerciseDetail(exerciseId = detailOf.orEmpty())
                picking -> ExercisePicker(
                    onPick = {
                        model.select(it)
                        picking = false
                    },
                    onDismiss = { picking = false },
                )
                half == Half.TRAIN && session == null -> TrainHome(onStart = { model.start() })
                half == Half.TRAIN -> SessionScreen(
                    model = model,
                    onPickExercise = { picking = true },
                    onOpenExercise = { detailOf = it },
                    onStartRest = { seconds ->
                        RestTimerService.start(context, seconds, pending.exercise?.name.orEmpty())
                    },
                )
                else -> MoveScreen()
            }
        }
    }
}
