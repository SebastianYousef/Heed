package se.kth.notiapp.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import se.kth.notiapp.data.AppPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: InboxViewModel, onBack: () -> Unit) {
    val policies by vm.policies.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Per-app rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(policies, key = { it.packageName }) { policy ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(policy.appLabel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${policy.alertedCount} shown · ${policy.suppressedCount} filed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppPolicy.entries.forEach { option ->
                                FilterChip(
                                    selected = policy.policy == option,
                                    onClick = {
                                        vm.setPolicy(policy.packageName, policy.appLabel, option)
                                    },
                                    label = {
                                        Text(
                                            when (option) {
                                                AppPolicy.LEARN -> "Learn"
                                                AppPolicy.ALWAYS_ALERT -> "Always"
                                                AppPolicy.NEVER_ALERT -> "Never"
                                            }
                                        )
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Silenced in Android", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (policy.sourceSilenced) {
                                        "Safe to hold and decide before anything reaches you."
                                    } else {
                                        "Not silenced — notifications from this app alert you before " +
                                            "notiApp can act."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (policy.sourceSilenced) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            Switch(
                                checked = policy.sourceSilenced,
                                onCheckedChange = { vm.setSourceSilenced(policy.packageName, it) },
                            )
                        }

                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, policy.packageName)
                            )
                        }) { Text("Open Android settings for this app") }
                    }
                }
            }
        }
    }
}
