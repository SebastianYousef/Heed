package io.github.sebastianyousef.keel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A number you change without a keyboard.
 *
 * This is the single most important control in an app you use standing up between sets,
 * and every app in the category gets it wrong the same way: the number is a text field, so
 * changing it means summoning a keyboard, which covers the screen, takes a moment to
 * animate, needs the old value cleared, and puts a "done" button between you and the log.
 * That is six or seven interactions to say "the same as last time but five kilos more".
 *
 * Here it is two: press plus twice. The value itself is still tappable for the rare case
 * where the jump is large enough that stepping to it is worse — but that is the exception
 * the control degrades to, not the path it is built around.
 *
 * Holding either end repeats, and accelerates while held, because the alternative to
 * acceleration is that a large change is a long press *and* forty taps. The first repeat
 * waits longer than the rest so a slightly slow tap is never read as a hold.
 *
 * @param onTapValue if given, the value itself becomes a target — for a keypad, a picker,
 *        or whatever the caller thinks is the right escape hatch. Null leaves it inert.
 */
@Composable
fun KeelStepper(
    value: String,
    onStep: (up: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    unit: String? = null,
    enabled: Boolean = true,
    onTapValue: (() -> Unit)? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RepeatingIconButton(
                icon = { Icon(Icons.Default.Remove, contentDescription = null) },
                description = "Decrease",
                enabled = enabled,
                onFire = { onStep(false) },
            )
            Column(
                Modifier
                    .weight(1f)
                    .then(
                        if (onTapValue == null) {
                            Modifier
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .pointerInput(Unit) { detectTapGestures { onTapValue() } }
                        }
                    )
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        value,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    unit?.let {
                        Spacer(Modifier.width(3.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
                caption?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RepeatingIconButton(
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                description = "Increase",
                enabled = enabled,
                onFire = { onStep(true) },
            )
        }
    }
}

/**
 * Fires once on press, then repeats while held, faster the longer it is held.
 *
 * Written rather than taken from Material because none of its buttons repeat — a
 * long press on an IconButton is a single click no matter how long it lasts, which for a
 * stepper means the only way to add fifty kilos is twenty taps.
 */
@Composable
private fun RepeatingIconButton(
    icon: @Composable () -> Unit,
    description: String,
    enabled: Boolean,
    onFire: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val fire by rememberUpdatedState(onFire)
    var held by remember { mutableStateOf(false) }

    LaunchedEffect(held) {
        if (!held) return@LaunchedEffect
        // The press itself has already fired once. Wait long enough that an ordinary tap
        // that happens to linger never becomes two.
        delay(FIRST_REPEAT_MS)
        var interval = REPEAT_MS
        while (true) {
            fire()
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            delay(interval)
            interval = (interval * ACCELERATION).toLong().coerceAtLeast(MIN_REPEAT_MS)
        }
    }

    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                }
            )
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        fire()
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        held = true
                        tryAwaitRelease()
                        held = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
        ) { icon() }
    }
}

/**
 * How much of a budget is gone, as a bar and as a sentence.
 *
 * Both, deliberately. The bar is what makes "nearly there" visible at a glance; the
 * numbers are what makes it checkable, and a figure you cannot check is one you stop
 * believing the first time it disagrees with you.
 */
@Composable
fun KeelMeter(
    label: String,
    progress: Float,
    detail: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    complete: Boolean = progress >= 1f,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                detail,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (complete) FontWeight.SemiBold else FontWeight.Normal,
                color = if (complete) color else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

/**
 * A ring, for the one figure a screen is about.
 *
 * Drawn rather than taken from Material's progress indicator because that one is sized for
 * a loading state — it has a fixed cap style, a fixed track alpha and an indeterminate
 * animation attached to it, none of which are wanted for a figure that is simply *true*
 * and not in progress. Overshoot is kept rather than clamped away: a day at 140% of a
 * goal should look different from a day at exactly 100%, so the ring keeps going round.
 */
@Composable
fun KeelRing(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    thickness: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val stroke = thickness.toPx()
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val offset = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = offset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val full = progress.coerceAtLeast(0f)
            // A second lap, drawn under the first, so passing the goal reads as passing it
            // rather than as the ring simply being full.
            if (full > 1f) {
                drawArc(
                    color = color.copy(alpha = 0.35f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = offset,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * (if (full > 1f) full - 1f else full).coerceIn(0f, 1f),
                useCenter = false,
                topLeft = offset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

private const val FIRST_REPEAT_MS = 420L
private const val REPEAT_MS = 110L
private const val MIN_REPEAT_MS = 28L
private const val ACCELERATION = 0.86
