package io.github.sebastianyousef.heed.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sebastianyousef.heed.core.Time
import io.github.sebastianyousef.heed.focus.CriticalApps
import io.github.sebastianyousef.heed.focus.DetectionMode
import io.github.sebastianyousef.heed.focus.FocusMode
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.Grayscale
import io.github.sebastianyousef.heed.focus.KnownSurfaces
import io.github.sebastianyousef.heed.focus.LearnedSurface
import io.github.sebastianyousef.heed.focus.ScrollWatcherService
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything about one app, on its own screen.
 *
 * Splitting this out of the list is the whole usability fix. A rule editor has five
 * controls that interact with each other, and inlining five of those in a scrolling list
 * meant you could never see two apps at once and never see one app's rules in full.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(vm: InboxViewModel, packageName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val stats by vm.attention.collectAsState()
    val rules by vm.focusRules.collectAsState()
    val surfaceMap by vm.surfaces.collectAsState()
    val week by vm.weekByApp.collectAsState()
    val strict by vm.strict.collectAsState()

    val stat = stats.firstOrNull { it.packageName == packageName }
    val fallbackLabel = stat?.appLabel ?: packageName
    val label = rememberAppLabel(packageName, fallbackLabel)
    val rule = rules[packageName] ?: FocusRule(packageName, label)
    val surfaces = surfaceMap[packageName].orEmpty()
    val weekMs = week.firstOrNull { it.packageName == packageName }?.totalMs ?: 0L
    val protected = CriticalApps.isProtected(packageName)

    var greyAvailable by remember { mutableStateOf(Grayscale.isAvailable(context)) }
    val watcherEnabled = remember { ScrollWatcherService.isEnabled(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val days by vm.appDays(packageName).collectAsState(initial = emptyList())
            val opens by vm.appOpens(packageName).collectAsState(initial = emptyList())
            var selectedDay by remember(packageName) { mutableStateOf<Int?>(null) }

            // The same card the Attention screen draws, at a different scope. Nothing
            // about how it reads or behaves should change between the two.
            UsageChartCard(
                timeDays = days,
                openDays = opens,
                selectedDay = selectedDay,
                onSelect = { selectedDay = it },
                leading = { AppIcon(packageName, label, size = 46) },
            )

            // The number only Heed can produce, because only Heed holds both halves.
            stat?.let {
                if (it.alerts > 0 && it.minutesPerAlert >= 1) {
                    Text(
                        "${it.alerts} notifications got through, and they cost you about " +
                            "${it.minutesPerAlert.roundToInt()} minutes each.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (protected) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Never blocked", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "This looks like an authenticator, dialler, alarm or password " +
                                "app. Heed will measure it but will never stop you opening " +
                                "it, whatever rule is set — being locked out of a login code " +
                                "at the wrong moment is worse than any amount of screen time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (strict) {
                Text(
                    "Strict mode is on. You can tighten these, but not loosen them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SettingBlock("What Heed does here") {
                Text(
                    if (rule.detection == DetectionMode.PRECISE) {
                        "In Precise mode, Block only removes you from the screens named " +
                            "below. Everything else in the app is untouched."
                    } else {
                        "In Automatic mode, Block stops you after a few scrolls anywhere " +
                            "in the app — it cannot tell a feed from a conversation."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusMode.entries.forEach { mode ->
                        FilterChip(
                            selected = rule.mode == mode,
                            onClick = { vm.setFocusRule(rule.copy(mode = mode, fromPreset = false)) },
                            label = {
                                Text(
                                    when (mode) {
                                        FocusMode.OFF -> "Just measure"
                                        FocusMode.NUDGE -> "Nudge"
                                        FocusMode.BLOCK -> "Block the feed"
                                    }
                                )
                            },
                        )
                    }
                }
            }

            SettingBlock("Grey screen in this app") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (greyAvailable) {
                            "Colour drains away while this app is in front, and comes back " +
                                "when you leave. Nothing is blocked. For a feed built on " +
                                "thumbnails this does more than a time limit and starts no " +
                                "argument."
                        } else {
                            "Needs a one-time setup over USB — see Settings."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = rule.grayscale && greyAvailable,
                        enabled = greyAvailable,
                        onCheckedChange = { vm.setGrayscale(packageName, label, it) },
                    )
                }
            }

            SettingBlock("Limits") {
                LimitSlider(
                    label = if (rule.dailyUsageSeconds > 0) {
                        "${rule.dailyUsageSeconds / 60} minutes a day"
                    } else "No time limit",
                    value = (rule.dailyUsageSeconds / 60).toFloat(),
                    max = 180f,
                ) { vm.setFocusRule(rule.copy(dailyUsageSeconds = it * 60)) }

                LimitSlider(
                    label = if (rule.dailyLaunchLimit > 0) {
                        "${rule.dailyLaunchLimit} opens a day"
                    } else "No limit on opens",
                    value = rule.dailyLaunchLimit.toFloat(),
                    max = 50f,
                ) { vm.setFocusRule(rule.copy(dailyLaunchLimit = it)) }

                Text(
                    "Opens are often the better lever. Twenty two-minute checks cost less " +
                        "clock than one forty-minute sitting and do far more damage.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingBlock("Scrolling") {
                if (!watcherEnabled) {
                    Text(
                        "Screen access is off, so Heed cannot measure scrolling in this app. " +
                            "Everything above still works. Turning it on will stop banking " +
                            "apps from starting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Turn on screen access") }
                } else {
                    DetectionPicker(rule, surfaces, vm)

                    Spacer(Modifier.height(6.dp))
                    LimitSlider(
                        label = if (rule.dailyScrollSeconds > 0) {
                            "${rule.dailyScrollSeconds / 60} minutes of scrolling a day — " +
                                "messages and everything else stay open"
                        } else "No scrolling budget",
                        value = (rule.dailyScrollSeconds / 60).toFloat(),
                        max = 60f,
                    ) { vm.setFocusRule(rule.copy(dailyScrollSeconds = it * 60)) }

                    Spacer(Modifier.height(14.dp))
                    ScrollBreak(rule, vm)
                }
            }
        }
    }
}

/**
 * Break the feed every so many posts, and make continuing a decision.
 *
 * This is a different lever from every other control on this screen, and the difference
 * is worth stating rather than burying in a slider label. A limit takes the app away when
 * it runs out; a nudge argues with you once and then gives up for the visit. This takes
 * nothing and never gives up — it puts a seam in the feed, waits, and hands it straight
 * back. The whole mechanism of an infinite feed is that there is no post that is the last
 * one, so continuing is never a decision. All this has to do is manufacture the moment
 * where it is.
 *
 * The honesty about detection matters here more than anywhere else on the screen. In
 * Precise mode the seam only counts scrolls on a screen Heed recognises as a feed, so
 * your conversations cannot trigger it. In Automatic there is no such thing as a feed —
 * only scroll events — so it will fire in a chat too. That is a real cost and the text
 * says so, because someone who meets an unexpected pause mid-conversation and has to
 * guess why will turn the whole feature off.
 */
@Composable
private fun ScrollBreak(rule: FocusRule, vm: InboxViewModel) {
    Text("Break the feed", style = MaterialTheme.typography.bodyMedium)
    Text(
        if (rule.scrollBreakEvents > 0) {
            "Every ${rule.scrollBreakEvents} scrolls the feed is covered for " +
                "${rule.breakSeconds} seconds. Nothing is taken away and nothing closes — " +
                "you tap once and carry on. It happens again after the next " +
                "${rule.scrollBreakEvents}."
        } else {
            "Off. A feed has no last post, so carrying on is never a decision. This puts a " +
                "pause in the way often enough that it becomes one."
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LimitSlider(
        label = if (rule.scrollBreakEvents > 0) {
            "Every ${rule.scrollBreakEvents} scrolls"
        } else "No break",
        value = rule.scrollBreakEvents.toFloat(),
        max = 120f,
    ) { vm.setFocusRule(rule.copy(scrollBreakEvents = it)) }

    if (rule.scrollBreakEvents > 0) {
        LimitSlider(
            label = "Pause for ${rule.breakSeconds} seconds",
            value = rule.breakSeconds.toFloat(),
            max = 120f,
        ) { vm.setFocusRule(rule.copy(breakSeconds = it.coerceIn(3, 120))) }

        Text(
            if (rule.detection == DetectionMode.PRECISE) {
                "In Precise mode this only counts scrolls on the feeds Heed knows, so your " +
                    "chats and your friends' stories will never trigger it."
            } else {
                "In Automatic mode Heed cannot tell a feed from a chat, so this counts " +
                    "every scroll in the app — including a long conversation. Switch to " +
                    "Precise above to confine it to the feed."
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (rule.detection == DetectionMode.PRECISE) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/**
 * Automatic versus Precise, and the honest description of what each can do.
 *
 * This is where the Snapchat bug lived. Automatic sees TYPE_VIEW_SCROLLED and nothing
 * else, so "block after four scrolls" fires identically in Spotlight and in a conversation
 * with a friend — which is exactly what happened. Precise is the only mode that can tell
 * them apart, so for apps Heed ships anchors for it is now the default, and blocking by
 * scroll count is confined to Automatic.
 */
@Composable
private fun DetectionPicker(
    rule: FocusRule,
    surfaces: List<LearnedSurface>,
    vm: InboxViewModel,
) {
    val known = KnownSurfaces.forPackage(rule.packageName)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DetectionMode.entries.forEach { mode ->
            FilterChip(
                selected = rule.detection == mode,
                onClick = { vm.setFocusRule(rule.copy(detection = mode)) },
                label = { Text(if (mode == DetectionMode.BEHAVIOURAL) "Automatic" else "Precise") },
            )
        }
    }
    Text(
        if (rule.detection == DetectionMode.BEHAVIOURAL) {
            "Automatic watches how fast and how long you scroll and never looks at your " +
                "screen. It cannot tell a feed from a chat list, so blocking on it will " +
                "throw you out of conversations too."
        } else {
            "Precise matches the screen against ones it knows, so it can stop Spotlight " +
                "and Discover and leave your friends' stories and your chats completely " +
                "alone. It reads the layout's structure — view ids and class names — and " +
                "never the text on it."
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (rule.detection == DetectionMode.PRECISE) {
        val exceptions = KnownSurfaces.exceptionsFor(rule.packageName)
        exceptions.forEach { exception ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(exception.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        exception.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = rule.isExceptionEnabled(exception.key),
                    onCheckedChange = { vm.setException(rule, exception.key, it) },
                )
            }
        }

        if (known.isEmpty() && surfaces.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Heed does not know any screens in this app yet, so Precise mode will not " +
                    "stop anything until you teach it one. Automatic works with no setup " +
                    "at all — it just cannot tell a feed from a chat.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (known.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Heed already knows: " + known.joinToString { it.label } +
                    ". Nothing else in this app is touched.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.armSurfaceCapture() }) { Text("Teach it another screen") }
        Text(
            "Tap this, then open the screen you mean and wait a second. Heed records the " +
                "shape of the next screen it sees. Teach it a screen you want left alone " +
                "and mark it Allowed — that beats any block.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        surfaces.forEach { surface ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    surface.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = surface.block,
                    onClick = { vm.setSurfaceBlock(surface, !surface.block) },
                    label = { Text(if (surface.block) "Blocked" else "Allowed") },
                )
                TextButton(onClick = { vm.deleteSurface(surface.id) }) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun SettingBlock(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun LimitSlider(label: String, value: Float, max: Float, onChange: (Int) -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = value.coerceIn(0f, max),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = 0f..max,
    )
}
