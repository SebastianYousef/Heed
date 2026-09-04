package io.github.sebastianyousef.heed.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import io.github.sebastianyousef.heed.export.RedactionLevel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sebastianyousef.heed.focus.Grayscale
import androidx.compose.material3.CardDefaults
import android.provider.Settings
import io.github.sebastianyousef.heed.focus.ScrollWatcherService
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: InboxViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val stats by vm.modelStats.collectAsState()
    val retrained by vm.retrained.collectAsState()
    val exportReady by vm.exportReady.collectAsState()
    val scrubbed by vm.scrubbedCount.collectAsState()
    val readable by vm.readableCount.collectAsState()
    val exporting by vm.exporting.collectAsState()
    val context = LocalContext.current
    var level by remember { mutableStateOf(RedactionLevel.REDACTED) }

    // Hand the finished file straight to the share sheet, then forget it.
    LaunchedEffect(exportReady) {
        exportReady?.let { (uri, exportedLevel) ->
            context.startActivity(
                Intent.createChooser(vm.shareIntent(uri, exportedLevel), "Send Heed export")
            )
            vm.exportConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BedtimeCard(
                settings.bedtimeEnabled,
                settings.bedtimeStart,
                settings.bedtimeEnd,
                settings.grayscaleAtBedtime,
                Grayscale.isAvailable(context),
                vm::setBedtime,
                vm::setGrayscaleAtBedtime,
            )
            Spacer(Modifier.height(12.dp))
            ScreenAccessCard(
                ScrollWatcherService.isEnabled(context),
                vm,
                context,
            )
            Spacer(Modifier.height(12.dp))

            Section("Grey screen") {
                val context = LocalContext.current
                var available by remember { mutableStateOf(Grayscale.isAvailable(context)) }
                if (available) {
                    Text(
                        "Ready. Turn it on for bedtime, or for individual apps, in " +
                            "Attention.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        Grayscale.set(context, !Grayscale.isOn(context))
                    }) { Text("Try it now") }
                } else {
                    Text(
                        "Draining the colour out of the screen means writing Android's " +
                            "display filter, which is a secure setting — there is no " +
                            "in-app prompt that can grant it, on any phone, for any app. " +
                            "It takes one command over USB, once:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        Grayscale.ADB_COMMAND,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Heed writes exactly two keys with that permission, both listed in " +
                            "focus/Grayscale.kt, and it still has no network access to send " +
                            "anything anywhere. Apps that offer grayscale without asking for " +
                            "this are drawing a grey film over the screen, which dims it " +
                            "without removing a single colour cue.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { available = Grayscale.isAvailable(context) }) {
                        Text("I've run it — check again")
                    }
                }
            }

            Section("How strict to be") {
                Text(
                    "Anything scoring above ${(settings.threshold * 100).roundToInt()} gets through. " +
                        "Lower lets more in; higher keeps more for the digest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.threshold,
                    onValueChange = { vm.setThreshold(it) },
                    valueRange = 0.2f..0.9f,
                )
            }

            Section("Thinking time") {
                Text(
                    "How long to hold a silent notification before deciding: " +
                        "${settings.holdWindowMs} ms. Longer means bursts get collapsed into one " +
                        "interruption. Only applies to apps you've silenced — for anything else " +
                        "the alert has already fired and waiting would only delay the cleanup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.holdWindowMs.toFloat(),
                    onValueChange = { vm.setHoldWindow(it.toLong()) },
                    valueRange = 0f..10_000f,
                    steps = 9,
                )
            }

            Section("Summaries") {
                Text(
                    "Digest every ${settings.digestIntervalHours} hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.digestIntervalHours.toFloat(),
                    onValueChange = { vm.setDigestInterval(it.roundToInt()) },
                    valueRange = 1f..12f,
                    steps = 10,
                )
            }

            Section("Quiet hours") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only emergencies at night", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Between ${settings.quietHoursStart}:00 and ${settings.quietHoursEnd}:00 " +
                                "only calls, alarms and one-time codes get through — the classifier " +
                                "doesn't get a vote.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.quietHoursStrict,
                        onCheckedChange = { vm.setQuietStrict(it) },
                    )
                }
            }

            Section("Strict mode") {
                Text(
                    "Locks your rules for a chosen number of days. You can still make them " +
                        "stricter; you cannot make them looser, and you cannot cancel it " +
                        "early. The version of you who set a rule and the version who wants " +
                        "to break it are not the same person, and only one of them was " +
                        "thinking clearly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 7, 30).forEach { days ->
                        OutlinedButton(onClick = { vm.enableStrict(days) }) {
                            Text(if (days == 1) "1 day" else "$days days")
                        }
                    }
                }
                if (settings.strictUntil > System.currentTimeMillis()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Active. Rules are locked for another " +
                            "${(settings.strictUntil - System.currentTimeMillis()) / 86_400_000 + 1} day(s).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Section("Forgetting") {
                Text(
                    "Notification text is scrubbed after ${settings.contentRetentionDays} " +
                        "days. The row stays — which app, when, what Heed decided, what you " +
                        "told it — so your history and statistics survive. Only the words go.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.contentRetentionDays.toFloat(),
                    onValueChange = { vm.setContentRetention(it.roundToInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
                Text(
                    "Rows are deleted entirely after ${settings.recordRetentionDays} days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.recordRetentionDays.toFloat(),
                    onValueChange = { vm.setRecordRetention(it.roundToInt()) },
                    valueRange = 7f..365f,
                    steps = 51,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This costs Heed nothing it has learned. The model is trained the " +
                        "moment you react to a notification, and those weights are stored " +
                        "separately — the text was never what it was carrying. Scrubbing a " +
                        "week later cannot untrain anything.\n\n" +
                        "$readable readable · $scrubbed scrubbed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.scrubNow() }) { Text("Scrub now") }
            }

            Section("Export your data") {
                Text(
                    "Writes what Heed has seen and decided to a JSON file and opens the " +
                        "share sheet. Useful for working out why something was filtered, " +
                        "or for handing to someone helping you tune it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RedactionLevel.entries.forEach { option ->
                        FilterChip(
                            selected = level == option,
                            onClick = { level = option },
                            label = {
                                Text(
                                    when (option) {
                                        RedactionLevel.STATS_ONLY -> "Stats only"
                                        RedactionLevel.REDACTED -> "Redacted"
                                        RedactionLevel.FULL -> "Everything"
                                    }
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (level) {
                        RedactionLevel.STATS_ONLY ->
                            "Counts and distributions only. No row-level data, so nothing " +
                                "can be traced back to a single notification."
                        RedactionLevel.REDACTED ->
                            "One row per notification, but every piece of text is replaced " +
                                "by its shape — length, word count, whether it held a link " +
                                "or a number. App names and decisions are kept so a wrong " +
                                "call can be diagnosed; what anyone said to you is not. " +
                                "Channel ids are hashed, since some apps put phone numbers " +
                                "in them. Safe to send to someone else."
                        RedactionLevel.FULL ->
                            "Includes the full text of every notification — messages, " +
                                "one-time codes, account details. For reading yourself. " +
                                "Do not send this to anyone."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (level == RedactionLevel.FULL) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.export(level) }, enabled = !exporting) {
                    Text(if (exporting) "Preparing…" else "Export and share")
                }
            }

            Section("What it has learned") {
                val (examples, confidence) = stats
                Text(
                    if (examples == 0) {
                        "No training data yet. Every time you tap a notification, swipe one away, " +
                            "or mark something in the inbox, the model learns from it. Until then " +
                            "decisions come purely from the built-in rules."
                    } else {
                        "Trained on $examples of your reactions. The model now carries " +
                            "${(confidence * 100).roundToInt()}% of each decision, with the rules " +
                            "covering the rest."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                retrained?.let {
                    Text(
                        if (it == 0) {
                            "Nothing to replay yet — react to a few notifications first."
                        } else {
                            "Replayed $it of your judgements."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    "Rebuilding replays every judgement you have made, oldest first. Worth " +
                        "doing after an update that adds a new signal — the weights carried " +
                        "over from before have nothing to say about it, so it would " +
                        "otherwise take weeks to become useful again even though every " +
                        "example needed to fit it is already stored.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.retrain() }) { Text("Rebuild from history") }
                    TextButton(onClick = { vm.resetModel() }) { Text("Forget everything") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun BedtimeCard(
    enabled: Boolean,
    start: Int,
    end: Int,
    grey: Boolean,
    greyAvailable: Boolean,
    onChange: (Boolean, Int, Int) -> Unit,
    onGrey: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bedtime", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (enabled) {
                            "Apps with a rule are closed between $start:00 and $end:00. " +
                                "Calls, alarms and authenticators are untouched."
                        } else {
                            "Off."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { onChange(it, start, end) })
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Grey screen at night", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (greyAvailable) {
                            "Drains the colour out of the whole screen during those hours. " +
                                "Nothing is blocked — the phone just stops being interesting."
                        } else {
                            "Needs a one-time setup over USB. Open Settings to see the command."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = grey && greyAvailable,
                    enabled = greyAvailable,
                    onCheckedChange = onGrey,
                )
            }

            if (enabled) {
                Spacer(Modifier.height(4.dp))
                Text("Starts at $start:00", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = start.toFloat(),
                    onValueChange = { onChange(true, it.roundToInt(), end) },
                    valueRange = 18f..23f, steps = 4,
                )
                Text("Ends at $end:00", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = end.toFloat(),
                    onValueChange = { onChange(true, start, it.roundToInt()) },
                    valueRange = 4f..11f, steps = 6,
                )
            }
        }
    }
}

/**
 * The accessibility service, and the banking-app problem that turned out not to exist.
 *
 * This card used to carry a switch and a button for stepping out of banking apps' way,
 * built on the belief that Nordea, BankID, Swish and Revolut refuse to start while any
 * accessibility service is enabled. The belief was wrong about the cause. Accessibility
 * services are enabled **per Android user**, and the banks that appeared to object were
 * simply in a private space that `adb install` had put a second copy of Heed into. With
 * one copy, in the owner profile, they all start normally with screen access on —
 * confirmed on the device, and consistent with Mindful, which declares a strictly more
 * capable service than Heed and has never contained a line of code about banks.
 *
 * So the whole mechanism is gone, and the second reason is the better one. A button
 * inside a blocking app that switches off the thing doing the blocking is not a
 * concession to banks, it is a one-tap way out of every rule you set — reachable from
 * the notification shade, needing no password and no waiting. An app whose entire premise
 * is that the version of you who set the rule should outrank the version who wants out of
 * it has no business shipping that button.
 *
 * Screen access can still be turned off, in system settings, where turning something off
 * costs the deliberate walk it should.
 */
@Composable
private fun ScreenAccessCard(
    enabled: Boolean,
    vm: InboxViewModel,
    context: android.content.Context,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Screen access", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                if (enabled) {
                    "On. Heed can measure scrolling and tell one feed from another — the " +
                        "only way to block Snapchat's Spotlight without also blocking your " +
                        "chats.\n\nBanking apps are unaffected. They only object to an " +
                        "accessibility service in the same Android user as themselves, so " +
                        "one running in your owner profile does not stop a bank in your " +
                        "private space."
                } else {
                    "Off. Nothing about scrolling is running — not blocking a feed, not " +
                        "the scrolling budget, not breaking the feed.\n\nTime limits, " +
                        "opens, bedtime and the grey screen all still work; those run on " +
                        "usage statistics and need nothing from your screen."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (enabled) {
                Text(
                    "Heed will not offer to turn this off for you. A button that disables " +
                        "the blocking is a way around every rule you set, and it lived one " +
                        "tap deep in the notification shade.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("System settings") }
            } else {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Turn on screen access") }
            }
        }
    }
}
