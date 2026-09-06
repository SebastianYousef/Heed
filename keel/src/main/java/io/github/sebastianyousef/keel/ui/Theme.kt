package io.github.sebastianyousef.keel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Colours that must mean the same thing on every device, and therefore are not dynamic.
 *
 * Material You takes its palette from the wallpaper, which is right for nearly everything
 * — it is the user's phone and it should look like it. It is wrong for the small set of
 * colours that carry meaning rather than identity. A personal record has to read as *good*
 * on a phone whose wallpaper is red; a warning has to read as a warning on one whose
 * wallpaper is green. A wallpaper-derived accent cannot promise either.
 *
 * The values are picked to stay apart from each other at nine pixels square — a legend
 * swatch, a thin segment of a bar — in both themes. That constraint, rather than taste, is
 * why this is a short fixed list and not a colour picker.
 */
@Immutable
data class KeelSemantics(
    /** A record, a goal met, a streak held. */
    val success: Color = Color(0xFF46A758),
    /** A limit reached, a rule broken, data that will be lost. Never used decoratively. */
    val warning: Color = Color(0xFFF76B15),
    /** Beyond a warning: something is wrong and the app cannot do what it says it does. */
    val danger: Color = Color(0xFFE5484D),
    /**
     * The chart's own accent, for the series a screen is about.
     *
     * Deliberately separate from the theme's primary. A chart drawn in the wallpaper
     * accent looks correct on the phone it was designed on and illegible on the next one,
     * because dynamic primary can land anywhere on the wheel including places with almost
     * no contrast against the surface it sits on.
     */
    val series: List<Color> = listOf(
        Color(0xFF3E63DD), // blue
        Color(0xFF00A2C7), // cyan
        Color(0xFF8E4EC6), // purple
        Color(0xFFE93D82), // pink
        Color(0xFFFFC53D), // amber
        Color(0xFF46A758), // green
    ),
)

val LocalKeelSemantics = staticCompositionLocalOf { KeelSemantics() }

/**
 * The theme every app in the family wears.
 *
 * [MaterialExpressiveTheme] rather than [androidx.compose.material3.MaterialTheme]: it is
 * the same Material You colour system with a motion scheme attached, which is the part
 * that matters here. Motion in these apps is not decoration — a rest timer that animates
 * as it runs down, a bar that grows when a set lands, a number that counts rather than
 * cuts — and a motion scheme means those read as one app rather than as whatever each
 * screen's author felt like. Spatial springs for anything that moves, effects for anything
 * that only changes colour or opacity.
 *
 * Dynamic colour is unconditional. The minimum SDK is 33, so there is no device that can
 * run this and cannot do it, and the fixed fallback scheme that every Compose app carries
 * for older versions would be a branch that never executes — the exact kind of code an
 * audit deletes two years later wondering whether it ever ran.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KeelTheme(
    dark: Boolean = isSystemInDarkTheme(),
    semantics: KeelSemantics = KeelSemantics(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme =
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    CompositionLocalProvider(LocalKeelSemantics provides semantics) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

object Keel {
    val semantics: KeelSemantics
        @Composable @ReadOnlyComposable get() = LocalKeelSemantics.current
}
