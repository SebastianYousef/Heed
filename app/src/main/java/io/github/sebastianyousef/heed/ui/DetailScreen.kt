package io.github.sebastianyousef.heed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sebastianyousef.heed.data.Decision
import io.github.sebastianyousef.heed.data.Feedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: InboxViewModel, id: Long, onBack: () -> Unit) {
    val flow = remember(id) { vm.observe(id) }
    val record by flow.collectAsState()

    LaunchedEffect(id) { vm.markSeen(id) }

    var confirmForget by remember { mutableStateOf(false) }
    if (confirmForget) {
        ForgetDialog(
            onDismiss = { confirmForget = false },
            onConfirm = {
                confirmForget = false
                vm.forget(id)
                onBack()
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(record?.appLabel ?: "Notification") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmForget = true }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Forget this notification",
                        )
                    }
                },
            )
        },
    ) { padding ->
        val r = record ?: return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                r.title ?: r.appLabel,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${relativeTime(r.postedAt)} · ${r.category ?: "no category"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                r.bigText ?: r.text ?: "",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(24.dp))

            // The "why" panel. Every decision the app makes should be legible — a filter
            // you cannot interrogate is a filter you stop trusting the first time it is wrong.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        when (r.decision) {
                            Decision.ALERTED -> "Shown to you"
                            Decision.SUPPRESSED -> "Filed quietly"
                            Decision.HELD -> "Still deciding"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        r.scoreReason.ifBlank { "No reason recorded." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Score ${(r.score * 100).toInt()} / 100 · captured via ${
                            when (r.capturePath) {
                                io.github.sebastianyousef.heed.data.CapturePath.QUIET_SOURCE -> "quiet source (silent app)"
                                io.github.sebastianyousef.heed.data.CapturePath.CANCEL_AFTER -> "cancelled after posting"
                            }
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Was this right?", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.mark(id, Feedback.MARKED_IMPORTANT) },
                    enabled = r.feedback != Feedback.MARKED_IMPORTANT,
                ) {
                    Icon(Icons.Default.ThumbUp, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("I needed this")
                }
                OutlinedButton(
                    onClick = { vm.mark(id, Feedback.MARKED_NOISE) },
                    enabled = r.feedback != Feedback.MARKED_NOISE,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.ThumbDown, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("Noise")
                }
            }
            if (r.feedback != Feedback.NONE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Recorded as ${r.feedback.name.lowercase().replace('_', ' ')} — the model has been updated.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(28.dp))
            OutlinedButton(
                onClick = { confirmForget = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("Forget this notification")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The confirmation, and an honest note about what deleting does not do.
 *
 * It would be easy to write "this is gone" and leave it there. It is not quite true: the
 * words and the row do go, and nothing in the app will show or export them again, but the
 * model was trained the moment feedback was given and those weights carry no link back to
 * the row that produced them. Saying so costs one sentence and is the difference between
 * a privacy claim that holds and one that sounds better than it is.
 */
@Composable
private fun ForgetDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget this notification?") },
        text = {
            Text(
                "The text and the record both go, permanently. Nothing in the inbox, the " +
                    "statistics or any future export will show it again.\n\n" +
                    "What this cannot undo is the model: if you already marked this one, " +
                    "that lesson went into the weights when you gave it and there is no " +
                    "way back from those to this notification.",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Forget", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}
