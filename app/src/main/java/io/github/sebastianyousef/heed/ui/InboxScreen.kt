package io.github.sebastianyousef.heed.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import io.github.sebastianyousef.heed.data.Decision
import io.github.sebastianyousef.heed.data.NotificationRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    vm: InboxViewModel,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
    onApps: () -> Unit,
) {
    val tab by vm.tab.collectAsState()
    val records by vm.records.collectAsState()
    val pending by vm.pendingCount.collectAsState()
    val connected by vm.listenerConnected.collectAsState()

    // Re-checked on every resume, because the user can revoke this in Settings at any
    // point and the app would otherwise carry on believing it can alert them.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var canPost by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canPost = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Heed") },
                actions = {
                    IconButton(onClick = onApps) {
                        Icon(Icons.Default.Apps, contentDescription = "Per-app rules")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!connected) DisconnectedBanner()
            if (!canPost) CannotPostBanner()

            // The tabs and the pager are two views of one selection, so each has to follow
            // the other: tapping a tab animates the pager, and settling on a page selects
            // the tab. Driving them independently is how you end up reading "Needed" over
            // a list of filtered notifications.
            val pagerState = rememberPagerState(
                initialPage = tab.ordinal,
                pageCount = { InboxTab.entries.size },
            )
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    InboxTab.entries.getOrNull(page)?.let(vm::selectTab)
                }
            }
            LaunchedEffect(tab) {
                if (pagerState.currentPage != tab.ordinal) {
                    pagerState.animateScrollToPage(tab.ordinal)
                }
            }

            TabRow(selectedTabIndex = pagerState.currentPage) {
                InboxTab.entries.forEach { t ->
                    Tab(
                        selected = t == tab,
                        onClick = { vm.selectTab(t) },
                        text = {
                            Text(
                                if (t == InboxTab.FILTERED && pending > 0) "${t.label} ($pending)"
                                else t.label
                            )
                        },
                    )
                }
            }

            // Top-aligned explicitly. HorizontalPager centres its pages vertically by
            // default, and a LazyColumn shorter than the viewport wraps its content — so
            // a tab with four notifications in it drew them as a block floating in the
            // middle of the screen. The list also fills the height below, so neither the
            // pager nor the column has any slack left to centre.
            HorizontalPager(
                state = pagerState,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                // Only the settled tab has rows: the view model holds one query at a
                // time, and re-querying three of them so a neighbouring page can be
                // pre-rendered would cost three times the work to show a list nobody is
                // looking at. The page being swiped towards fills in as it lands.
                val visible = if (page == tab.ordinal) records else emptyList()
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when (InboxTab.entries[page]) {
                                InboxTab.NEEDED -> "Nothing has needed you yet."
                                InboxTab.FILTERED -> "Nothing filtered yet."
                                InboxTab.ALL -> "No notifications captured yet.\nGrant notification access in Settings."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.id }) { record ->
                            NotificationCard(record) { onOpen(record.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(record: NotificationRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (record.decision == Decision.ALERTED) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.appLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    relativeTime(record.postedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                ScoreChip(record.score, record.scoreReason.contains("never filtered"))
            }
            record.title?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            record.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The one failure this app must never hide. If Android has unbound the listener, Heed
 * is silently seeing nothing — and an empty inbox looks identical to a quiet day.
 */
/**
 * The mirror of [DisconnectedBanner] for the other half of the job. Heed can read your
 * notifications without this permission but cannot raise a single one of its own, so the
 * filtering still runs and nothing ever reaches you — a silent, total failure.
 */
@Composable
private fun CannotPostBanner() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Heed can't alert you",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Notification permission is off, so nothing Heed decides is important can " +
                    "reach you. It will keep filing everything silently until you turn it on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }) { Text("Turn on notifications") }
        }
    }
}

@Composable
private fun DisconnectedBanner() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Heed isn't seeing your notifications",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Android has unbound the notification listener. Nothing is being filtered " +
                    "or recorded until it reconnects.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text("Check notification access") }
        }
    }
}
