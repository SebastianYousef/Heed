package io.github.sebastianyousef.ply.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
private fun notificationsGranted(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

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
    var routines by rememberSaveable { mutableStateOf(false) }
    // What the picker is picking *for*. A single picker screen serving two callers, because
    // two copies of a searchable list of 876 things is two copies that drift.
    var pickingForRoutine by rememberSaveable { mutableStateOf(false) }
    val routineModel: RoutineViewModel = viewModel()

    /**
     * Whether Ply may post the rest timer.
     *
     * The timer is a foreground service, so it runs whether or not this is granted — the
     * countdown simply becomes invisible, which is indistinguishable from a timer that
     * does not work. It is the only notification Ply ever posts.
     *
     * Asked for when a session starts rather than at first launch: before the app has
     * shown what a notification would be *for*, the prompt is one people refuse, and
     * refusing it permanently is worse than asking a moment later. Not asked on the
     * logging screen itself, because a system dialog between a person and the log button
     * is exactly what that screen is arranged to avoid.
     */
    var notificationsAllowed by remember { mutableStateOf(notificationsGranted(context)) }
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notificationsAllowed = notificationsGranted(context) }

    LaunchedEffect(session?.id) {
        if (session != null && !notificationsAllowed) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // A back press should close whatever is on top before it leaves the app, and a session
    // in progress should never be the thing back exits — losing the screen you are logging
    // into because you pressed back once is the failure this prevents.
    BackHandler(enabled = picking || settings || detailOf != null || routines) {
        picking = false
        settings = false
        detailOf = null
        if (!picking && !settings && detailOf == null) routines = false
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
                                routines -> "Routines"
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
                settings -> SettingsScreen()
                detailOf != null -> ExerciseDetail(exerciseId = detailOf.orEmpty())
                picking -> ExercisePicker(
                    onPick = { exercise ->
                        if (pickingForRoutine) routineModel.add(exercise) else model.select(exercise)
                        picking = false
                        pickingForRoutine = false
                    },
                )
                routines -> RoutineScreen(
                    model = routineModel,
                    onAddExercise = {
                        pickingForRoutine = true
                        picking = true
                    },
                    onStart = { routineId ->
                        model.start(routineId)
                        routines = false
                    },
                )
                half == Half.TRAIN && session == null -> TrainHome(
                    onStart = { model.start() },
                    onRoutines = { routines = true },
                    onRepeat = { model.repeat(it) },
                )
                half == Half.TRAIN -> SessionScreen(
                    model = model,
                    onPickExercise = { picking = true },
                    onOpenExercise = { detailOf = it },
                    onStartRest = { seconds ->
                        RestTimerService.start(context, seconds, pending.exercise?.name.orEmpty())
                    },
                    restTimerMuted = !notificationsAllowed,
                    onFixRestTimer = {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
                else -> MoveScreen()
            }
        }
    }
}
