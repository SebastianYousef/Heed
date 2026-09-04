package io.github.sebastianyousef.heed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sebastianyousef.heed.core.Time
import io.github.sebastianyousef.heed.focus.FocusSession
import io.github.sebastianyousef.heed.focus.FocusSessionRecord
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A stretch of time you decided not to be on your phone, and the record of whether you
 * managed it.
 *
 * The difference between this and every limit elsewhere in Heed is which way round the
 * question is asked. A limit is per-app and reactive: you have had enough of this one.
 * A session is the opposite — everything is shut unless you named it — and that is why it
 * needs its own screen rather than another slider on the app list.
 *
 * The load-bearing part is not the timer. It is that starting is one tap and stopping
 * takes ninety seconds, because the person who set the session and the person who wants
 * out of it are not the same person and only one of them was thinking about the
 * afternoon. Everything else here is bookkeeping around that asymmetry.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FocusScreen(vm: InboxViewModel, onSettings: () -> Unit) {
    val session by vm.focus.collectAsState()
    val history by vm.focusHistory.collectAsState()
    val allowed by vm.focusAllowed.collectAsState()
    val apps by vm.weekByApp.collectAsState()

    // One tick a second, and only while this screen is composed. A countdown is the one
    // thing here that has to move on its own.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Focus") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                if (session != null) {
                    RunningCard(session!!, now, vm)
                } else {
                    StartCard(vm)
                }
            }

            item {
                AllowlistCard(
                    apps = apps,
                    allowed = allowed,
                    locked = session != null,
                    onToggle = { pkg, on ->
                        vm.setFocusAllowed(if (on) allowed + pkg else allowed - pkg)
                    },
                )
            }

            if (history.isNotEmpty()) {
                item {
                    Text(
                        "Earlier sessions",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(history, key = { it.id }) { HistoryRow(it) }
            }
        }
    }
}

/** Pick what kind of session, and how long. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartCard(vm: InboxViewModel) {
    var label by rememberSaveable { mutableStateOf(FocusSession.TYPES.first()) }
    var minutes by rememberSaveable { mutableStateOf(45) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Start a session",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Everything closes except the apps you allow below. Authenticators, " +
                    "calls, alarms and your home screen are never touched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )

            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FocusSession.TYPES.forEach { type ->
                    FilterChip(
                        selected = label == type,
                        onClick = { label = type },
                        label = { Text(type) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // 0 is the stopwatch. Offered because not every stretch of work has a
                // known length, and forcing a guess makes the timer the point rather
                // than the work.
                listOf(25, 45, 60, 90, 0).forEach { m ->
                    FilterChip(
                        selected = minutes == m,
                        onClick = { minutes = m },
                        label = { Text(if (m == 0) "Stopwatch" else "${m}m") },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.startFocus(label, minutes * 60_000L) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (minutes == 0) "Start $label" else "Start $label · ${minutes}m",
                )
            }
        }
    }
}

/** The running session, and the deliberately slow way out of it. */
@Composable
private fun RunningCard(state: FocusSession.State, now: Long, vm: InboxViewModel) {
    val remaining = state.remainingMs(now)
    val expired = state.expired(now)

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                state.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
            Text(
                when {
                    expired -> "Done"
                    remaining != null -> clock(remaining)
                    else -> clock(state.elapsedMs(now))
                },
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                when {
                    expired -> "Your session finished. Nothing is being blocked."
                    remaining != null -> "left · started ${clock(state.elapsedMs(now))} ago"
                    else -> "counting up · stopwatch"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )

            Spacer(Modifier.height(18.dp))
            when {
                expired -> Button(
                    onClick = { vm.finishExpiredFocus() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Finish") }

                state.endRequestedAt <= 0L -> {
                    OutlinedButton(
                        onClick = { vm.requestFocusEnd() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) { Text("End early") }
                    Text(
                        "Takes ${FocusSession.END_DELAY_SECONDS} seconds. Starting was " +
                            "instant; stopping is not, and that gap is the whole point.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                state.canEndNow(now) -> {
                    Button(
                        onClick = { vm.endFocus(early = true) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("End it now") }
                    OutlinedButton(
                        onClick = { vm.cancelFocusEnd() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) { Text("Actually, keep going") }
                }

                else -> {
                    Button(
                        onClick = { },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Ending in ${state.secondsUntilRelease(now)}s") }
                    OutlinedButton(
                        onClick = { vm.cancelFocusEnd() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) { Text("Keep going") }
                }
            }
        }
    }
}

/**
 * What stays open.
 *
 * Drawn from the apps you have actually used rather than everything installed, because a
 * list of three hundred packages is not a thing anyone picks four apps out of — and Heed
 * has no business enumerating apps you never open.
 *
 * Locked while a session runs. Being able to add an app to the allowlist mid-session
 * would make the whole thing decorative.
 */
@Composable
private fun AllowlistCard(
    apps: List<io.github.sebastianyousef.heed.data.AppUsageRow>,
    allowed: Set<String>,
    locked: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Allowed during a session", style = MaterialTheme.typography.titleSmall)
            Text(
                if (allowed.isEmpty()) {
                    "Nothing yet — a session would close everything but your home screen."
                } else {
                    "${allowed.size} apps stay open."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (locked) {
                Text(
                    "Locked while a session is running.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Done" else "Choose apps")
            }

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                apps.take(40).forEach { app ->
                    val label = rememberAppLabel(app.packageName, app.appLabel)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = app.packageName in allowed,
                            enabled = !locked,
                            onCheckedChange = { onToggle(app.packageName, it) },
                        )
                        Spacer(Modifier.width(4.dp))
                        AppIcon(app.packageName, label, size = 24)
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(record: FocusSessionRecord) {
    val format = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
    val actual = (record.endedAt ?: record.startedAt) - record.startedAt

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(record.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    buildString {
                        append(format.format(Date(record.startedAt)))
                        if (record.blocks > 0) append(" · ${record.blocks} turned away")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Time.duration(actual), style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        record.endedAt == null -> "running"
                        record.endedEarly -> "ended early"
                        else -> "finished"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (record.endedEarly) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

/** "45:00" or "1:12:30". Seconds matter here, unlike everywhere else in the app. */
private fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.getDefault(), "%d:%02d", m, sec)
}
