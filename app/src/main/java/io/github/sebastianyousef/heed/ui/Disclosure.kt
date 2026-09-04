package io.github.sebastianyousef.heed.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * Heed explains itself more than most apps do, and that is deliberate — a filter you
 * cannot interrogate is one you stop trusting the first time it is wrong. But there is a
 * difference between *available* and *unavoidable*, and every settings card had been
 * written as three paragraphs at full volume. The effect on screen is the opposite of the
 * intent: when everything is explained equally loudly, nothing is emphasised, the control
 * you came for is somewhere below the fold, and the reader learns to skip the prose
 * wholesale — including the two sentences that actually mattered.
 *
 * So the short line stays visible and carries the decision. The reasoning moves one tap
 * away, where it costs nothing to leave unread and nothing to find.
 *
 * Not collapsed for warnings. An error banner whose explanation is hidden behind a tap is
 * an error you have to opt into understanding, which is how a warning becomes decoration.
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
            Spacer(Modifier.padding(horizontal = 3.dp))
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
 * Extracted because five screens had each grown their own version of "label, description,
 * trailing switch" with different spacing and different text styles, so the same idea
 * looked like three different ideas depending on which screen you were on.
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
            Spacer(Modifier.padding(horizontal = 4.dp))
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
