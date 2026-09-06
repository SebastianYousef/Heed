package io.github.sebastianyousef.keel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One line of description, with the reasoning behind it folded away.
 *
 * These apps explain themselves more than most, and that is deliberate — a tool you cannot
 * interrogate is one you stop trusting the first time it is wrong, and every one of these
 * makes claims you are being asked to take on faith. But there is a difference between
 * *available* and *unavoidable*. When every card is three paragraphs at full volume the
 * effect on screen is the opposite of the intent: nothing is emphasised, the control you
 * came for is below the fold, and the reader learns to skip the prose wholesale —
 * including the two sentences that mattered.
 *
 * So the short line stays visible and carries the decision. The reasoning moves one tap
 * away, where it costs nothing to leave unread and nothing to find.
 *
 * **Never used for warnings.** An error whose explanation is hidden behind a tap is one
 * you have to opt into understanding, which is how a warning becomes decoration. Nor for
 * the first description of a control that can surprise you: someone who does not read it
 * should still not be caught out by it.
 */
@Composable
fun Explain(
    short: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(short) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Column(modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                short,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide the reasoning" else "Why this works this way",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(expanded) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        }
    }
}

/**
 * A row that is one decision: what it is, what it currently does, and its control.
 *
 * Extracted because five screens in one app had each grown their own version of "label,
 * description, trailing switch" with different spacing and different text styles, so the
 * same idea looked like three different ideas depending on which screen you were on. With
 * two apps sharing a base the stakes are higher: it would look like two different apps.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    detail: String? = null,
    control: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            control()
        }
        detail?.let {
            Explain(short = "Why", detail = it, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A heading between cards, so a long screen reads as a few groups rather than a list. */
@Composable
fun GroupHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
    Spacer(Modifier.height(2.dp))
}

/**
 * A label and its figure on one line — the read-only twin of [SettingRow].
 *
 * Exists because "name on the left, number on the right" had otherwise been written
 * inline every time a screen needed to state a fact, and the two ended up with different
 * emphasis: some screens bolded the figure and some the label, which reads as one of them
 * being the point when neither is.
 */
@Composable
fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (emphasis) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
