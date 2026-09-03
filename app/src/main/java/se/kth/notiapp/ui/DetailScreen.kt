package se.kth.notiapp.ui

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
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.kth.notiapp.data.Decision
import se.kth.notiapp.data.Feedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: InboxViewModel, id: Long, onBack: () -> Unit) {
    val flow = remember(id) { vm.observe(id) }
    val record by flow.collectAsState()

    LaunchedEffect(id) { vm.markSeen(id) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(record?.appLabel ?: "Notification") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                                se.kth.notiapp.data.CapturePath.ASSISTANT -> "assistant (before display)"
                                se.kth.notiapp.data.CapturePath.QUIET_SOURCE -> "quiet source (silent app)"
                                se.kth.notiapp.data.CapturePath.CANCEL_AFTER -> "cancelled after posting"
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
        }
    }
}
