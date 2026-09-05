package io.github.sebastianyousef.heed.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sebastianyousef.heed.core.Time
import io.github.sebastianyousef.heed.focus.AppGroup
import kotlinx.coroutines.delay

/**
 * Budgets that span several apps, because the thing you are trying to limit usually does.
 *
 * The hole this closes is the one every per-app limit has: thirty minutes each of three
 * interchangeable feeds is an hour and a half of the same activity, and all three limits
 * report success. Switching apps costs one tap and feels like obeying the rule, which is
 * what makes it the escape hatch people actually use — usually without noticing they are
 * using it.
 *
 * So this screen is deliberately not "another place to set limits". It is the place where
 * you name what you are actually limiting, and every number on it is the group's, never
 * one member's.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupsScreen(vm: InboxViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val groups by vm.groups.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Groups") },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "One budget, several apps",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Spend it wherever you spend it. Switching apps does not reset " +
                                "anything.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Explain(
                            short = "Why not just set a limit on each one",
                            detail = "Because the apps are interchangeable, which is the " +
                                "whole point of them. Half an hour each of three feeds is " +
                                "an hour and a half of the same activity, and every one of " +
                                "those three limits reports success. A group is the honest " +
                                "version of the limit you thought you were setting.",
                        )
                    }
                }
            }

            items(groups, key = { it.id }) { group ->
                GroupCard(group, vm) { onOpen(group.id) }
            }

            item { NewGroupCard(groups, vm, onOpen) }
        }
    }
}

/** One group: who is in it, and how much of the shared budget is left today. */
@Composable
private fun GroupCard(group: AppGroup, vm: InboxViewModel, onClick: () -> Unit) {
    val spend = spendToday(group, vm)

    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupDot(group.color)
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Time.duration(spend.usageSeconds * 1000L),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                when {
                    group.members.isEmpty() -> "No apps in it yet"
                    !group.hasLimits -> "${appCount(group.members.size)} · measured, not limited"
                    else -> "${appCount(group.members.size)} share this"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (group.members.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.members.take(8).forEach { pkg ->
                        AppIcon(pkg, rememberAppLabel(pkg, pkg), size = 26)
                    }
                    if (group.members.size > 8) {
                        Text(
                            "+${group.members.size - 8}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            GroupMeters(group, spend)
        }
    }
}

/** The three shared budgets, drawn only where one has been set. */
@Composable
private fun GroupMeters(group: AppGroup, spend: InboxViewModel.GroupSpend) {
    LimitMeter(
        label = "Time",
        used = spend.usageSeconds,
        limit = group.dailyUsageSeconds,
        render = { Time.duration(it * 1000L) },
    )
    LimitMeter(
        label = "Opens",
        used = spend.launches,
        limit = group.dailyLaunchLimit,
        render = { "$it" },
    )
    LimitMeter(
        label = "Scrolling",
        used = spend.scrollSeconds,
        limit = group.dailyScrollSeconds,
        render = { Time.duration(it * 1000L) },
    )
}

/**
 * Today's spend, re-read while the screen is open.
 *
 * Polled rather than observed because it is read with the same queries the enforcement
 * uses, and those take the member list as an argument — a shape Room cannot turn into a
 * single flow without a second copy of every one of them. Ten seconds is far below the
 * resolution anyone reads a daily total at.
 */
@Composable
private fun spendToday(group: AppGroup, vm: InboxViewModel): InboxViewModel.GroupSpend {
    val state by produceState(InboxViewModel.GroupSpend(), group) {
        while (true) {
            value = vm.spendToday(group)
            delay(10_000)
        }
    }
    return state
}

/** Making one, without first making up a name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewGroupCard(
    existing: List<AppGroup>,
    vm: InboxViewModel,
    onOpen: (Long) -> Unit,
) {
    val taken = existing.map { it.name }.toSet()
    val offered = AppGroup.SUGGESTIONS.filterNot { it in taken }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("New group", style = MaterialTheme.typography.titleSmall)
            Text(
                "Name it after the habit, not the app.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                offered.forEach { name ->
                    AssistChip(
                        onClick = { vm.createGroup(name) { onOpen(it) } },
                        label = { Text(name) },
                    )
                }
                AssistChip(
                    onClick = { vm.createGroup("Group ${existing.size + 1}") { onOpen(it) } },
                    label = { Text("Something else") },
                )
            }
        }
    }
}

/**
 * Editing one group: what it is called, what is in it, and what it is allowed.
 *
 * The order is the order the decisions happen in. You cannot sensibly set a shared budget
 * before you have said what is sharing it, so membership comes above the sliders and
 * today's spend sits at the top where it answers the question you opened the screen with.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupDetailScreen(vm: InboxViewModel, groupId: Long, onBack: () -> Unit) {
    val groups by vm.groups.collectAsState()
    val apps by vm.weekByApp.collectAsState()
    val strict by vm.strict.collectAsState()
    val group = groups.firstOrNull { it.id == groupId }

    // Deleted from under us — by this screen's own delete button, or from elsewhere.
    //
    // Gated on having seen the group at least once, because the list arrives empty for a
    // frame while the query runs and popping on that would close the screen before it
    // opened. The first version gated on the list being non-empty instead, which had
    // exactly one hole and it was the common case: delete your only group and the list is
    // empty too, so nothing popped and the screen sat there blank.
    var everLoaded by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(group != null) { if (group != null) everLoaded = true }
    LaunchedEffect(group, everLoaded) { if (group == null && everLoaded) onBack() }

    if (group == null) {
        GroupGone(everLoaded, onBack)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group.name) },
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
            val spend = spendToday(group, vm)

            // The same chart the whole-phone and per-app screens draw, at a third scope.
            // A group is a habit, and a habit is a thing you want the *week* of — the
            // day's number is what the meters below are for.
            val days by vm.groupDays(group).collectAsState(initial = emptyList())
            val opens by vm.groupOpens(group).collectAsState(initial = emptyList())
            var selectedDay by remember(groupId) { mutableStateOf<Int?>(null) }

            UsageChartCard(
                timeDays = days,
                openDays = opens,
                selectedDay = selectedDay,
                onSelect = { selectedDay = it },
                leading = { GroupDot(group.color, size = 22) },
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Today", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (group.members.isEmpty()) {
                            "Nothing in this group yet — pick its apps below."
                        } else {
                            Time.duration(spend.usageSeconds * 1000L) +
                                " · ${spend.launches} opens across " +
                                appCount(group.members.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!group.hasLimits && group.members.isNotEmpty()) {
                        Text(
                            "No shared limit set yet, so nothing is being enforced.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    GroupMeters(group, spend)
                }
            }

            if (group.members.isNotEmpty()) {
                MemberBreakdown(group, selectedDay, vm)
            }

            if (strict) {
                Text(
                    "Strict mode is on. You can tighten this group, but not loosen it — " +
                        "and not delete it while it has a limit.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            GroupSection("Name") {
                var name by rememberSaveable(group.id) { mutableStateOf(group.name) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) vm.saveGroup(group.copy(name = it.trim()))
                    },
                    singleLine = true,
                    label = { Text("What is this group") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            GroupSection("Apps in this group") {
                Explain(
                    short = "An app can be in one group at a time",
                    detail = "Two groups claiming the same app would make \"how much is " +
                        "left\" a question with two answers, and there is no honest way to " +
                        "pick between them. Adding an app here takes it out of whichever " +
                        "group had it.",
                )
                Spacer(Modifier.height(6.dp))

                // The week's apps, plus any member that has not been opened this week —
                // otherwise a member you have successfully stopped using becomes one you
                // cannot remove.
                val listed = apps.map { it.packageName }
                val extra = group.members.filterNot { it in listed }
                (listed.take(40) + extra).forEach { pkg ->
                    val label = rememberAppLabel(pkg, pkg)
                    val inThis = pkg in group.members
                    val otherGroup = groups.firstOrNull { it.id != group.id && pkg in it.members }
                    // The whole row toggles, not just the box. A twenty-app list where
                    // only a 24dp square responds is one you miss on the first try.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.saveGroup(group.withMember(pkg, !inThis)) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = inThis,
                            onCheckedChange = { vm.saveGroup(group.withMember(pkg, it)) },
                        )
                        Spacer(Modifier.width(4.dp))
                        AppIcon(pkg, label, size = 24)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            otherGroup?.let {
                                Text(
                                    "Currently in ${it.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            GroupSection("Colour") {
                Explain(
                    short = if (group.color == 0) {
                        "Not coloured · its apps keep their own categories"
                    } else {
                        "This group's time is drawn in its own colour"
                    },
                    detail = "A colour turns the weekly chart into an answer about the " +
                        "habit rather than about the apps: if the red part of Tuesday is " +
                        "half the bar, that is the thing to change. It overrides the " +
                        "productive/distracting colour for the apps in this group, " +
                        "because a group is the more specific statement of the two.",
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(0) + AppGroup.COLOURS).forEach { argb ->
                        Swatch(
                            argb = argb,
                            selected = group.color == argb,
                            onClick = { vm.saveGroup(group.copy(color = argb)) },
                        )
                    }
                }
            }

            GroupSection("Shared limits") {
                LimitSlider(
                    label = if (group.dailyUsageSeconds > 0) {
                        "${group.dailyUsageSeconds / 60} minutes a day, across the group"
                    } else "No shared time limit",
                    value = (group.dailyUsageSeconds / 60).toFloat(),
                    max = 240f,
                ) { vm.saveGroup(group.copy(dailyUsageSeconds = it * 60)) }

                LimitSlider(
                    label = if (group.dailyLaunchLimit > 0) {
                        "${group.dailyLaunchLimit} opens a day, across the group"
                    } else "No shared limit on opens",
                    value = group.dailyLaunchLimit.toFloat(),
                    max = 60f,
                ) { vm.saveGroup(group.copy(dailyLaunchLimit = it)) }

                LimitSlider(
                    label = if (group.dailyScrollSeconds > 0) {
                        "${group.dailyScrollSeconds / 60} minutes of scrolling a day, " +
                            "across the group"
                    } else "No shared scrolling budget",
                    value = (group.dailyScrollSeconds / 60).toFloat(),
                    max = 90f,
                ) { vm.saveGroup(group.copy(dailyScrollSeconds = it * 60)) }

                Explain(
                    short = "Scrolling is the one to reach for first",
                    detail = "It budgets the feed rather than the app, so a group of " +
                        "messaging-and-feed apps can be held to ten minutes of scrolling " +
                        "with every conversation in them left open. It needs screen access; " +
                        "the other two do not.",
                )
            }

            TextButton(
                onClick = { vm.deleteGroup(group) },
                enabled = !(strict && group.hasLimits),
            ) { Text("Delete this group") }
        }
    }
}

/**
 * The moment between a group disappearing and this screen closing — and the state it
 * would otherwise be stuck in if the group never existed at all.
 *
 * A blank screen with a back arrow is what an unhandled null looks like, so it is worth
 * five lines to say which of the two happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupGone(everLoaded: Boolean, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Text(
            if (everLoaded) "Deleted." else "This group no longer exists.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(padding).padding(20.dp),
        )
    }
}

/**
 * Which member spent it, for the period the chart has selected.
 *
 * The second question a shared budget raises and the one it cannot answer on its own: the
 * limit tells you the habit ran out, and this tells you which app it ran out in. Bars are
 * relative to the group rather than to the phone, because the comparison that matters
 * here is between members.
 */
@Composable
private fun MemberBreakdown(group: AppGroup, day: Int?, vm: InboxViewModel) {
    val rows by vm.groupMembers(group, day).collectAsState(initial = emptyList())
    val total = rows.sumOf { it.totalMs }
    val tint = if (group.color != 0) Color(group.color) else MaterialTheme.colorScheme.primary

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Where it went", style = MaterialTheme.typography.titleSmall)
            if (rows.isEmpty()) {
                Text(
                    "Nothing recorded for these apps in this period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                return@Column
            }
            Spacer(Modifier.height(6.dp))
            rows.forEach { row ->
                val label = rememberAppLabel(row.packageName, row.appLabel)
                Column(Modifier.padding(vertical = 5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(row.packageName, label, size = 24)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${row.launches} opens",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            Time.duration(row.totalMs),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(
                                    if (total > 0) (row.totalMs.toFloat() / total).coerceIn(0f, 1f)
                                    else 0f
                                )
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(tint)
                        )
                    }
                }
            }
        }
    }
}

/** A group's colour, or a hollow ring where it has none. */
@Composable
fun GroupDot(argb: Int, size: Int = 12, modifier: Modifier = Modifier) {
    if (argb == 0) return
    Box(
        modifier
            .padding(end = 8.dp)
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(argb))
    )
}

/** One colour to choose, including "none", which is the first and the default. */
@Composable
private fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val border = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (argb == 0) MaterialTheme.colorScheme.surfaceVariant else Color(argb))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) border else border.copy(alpha = 0.25f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (argb == 0) {
            Text(
                "none",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "1 app", "4 apps". Written once because it appears on both screens. */
private fun appCount(n: Int) = if (n == 1) "1 app" else "$n apps"

@Composable
private fun GroupSection(title: String, content: @Composable () -> Unit) {
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
