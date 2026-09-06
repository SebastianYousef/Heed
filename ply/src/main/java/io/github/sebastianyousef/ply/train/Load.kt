package io.github.sebastianyousef.ply.train

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Every weight in this app is a whole number of grams.
 *
 * Not kilograms as a float, which is the obvious choice and the wrong one. A personal
 * record is decided by comparing two weights for equality or order, and 62.5 kg is not
 * representable in binary floating point — so two sets logged as the same weight through
 * two different paths (one stepped up from 60, one entered directly) can compare unequal,
 * and a total of forty such sets drifts. Grams are exact, and an integer is exactly as
 * cheap in SQLite as a real.
 *
 * Grams rather than the more obvious hundredths of a kilogram because the unit has to
 * survive being displayed in pounds as well, and 1 lb is 453.59237 g exactly — a number
 * that is whole in grams and is not in any coarser unit.
 */
object Load {

    const val GRAMS_PER_KG = 1_000
    const val GRAMS_PER_LB = 453.59237

    /** The increments the settings screen offers, in grams. 2.5 kg is the default. */
    val KG_INCREMENTS = listOf(250, 500, 1_000, 1_250, 2_000, 2_500, 5_000)

    /** The same list in pounds: 0.5, 1, 2.5, 5, 10 lb. */
    val LB_INCREMENTS = listOf(227, 454, 1_134, 2_268, 4_536)

    fun kgToGrams(kg: Double): Int = (kg * GRAMS_PER_KG).roundToInt()

    fun gramsToKg(grams: Int): Double = grams / GRAMS_PER_KG.toDouble()

    fun gramsToLb(grams: Int): Double = grams / GRAMS_PER_LB

    /**
     * "82.5", "80", "1.25" — the number alone, with the unit written separately.
     *
     * Trailing zeroes are dropped because a column of "80.0 / 82.5 / 85.0" is harder to
     * read at a glance than "80 / 82.5 / 85", and a decimal is only ever information when
     * it is not zero.
     *
     * Two decimals are kept where they mean something, because 1.25 kg is a real plate and
     * rounding it away would make two different loads print the same. The visible cost is
     * that a weight loaded in one unit and read in the other can show both of them —
     * 100 kg is 220.46 lb — which is the honest rendering of a converted figure rather
     * than a precision anybody put on the bar.
     */
    fun format(grams: Int, unit: Unit): String {
        val value = when (unit) {
            Unit.KG -> gramsToKg(grams)
            Unit.LB -> gramsToLb(grams)
        }
        val hundredths = (value * 100).roundToLong()
        return when {
            hundredths % 100L == 0L -> (hundredths / 100).toString()
            hundredths % 10L == 0L -> String.format(java.util.Locale.US, "%.1f", hundredths / 100.0)
            else -> String.format(java.util.Locale.US, "%.2f", hundredths / 100.0)
        }
    }

    /** What the user sees the weight measured in. Storage is unaffected by this. */
    enum class Unit(val label: String) {
        KG("kg"),
        LB("lb"),
    }

    /**
     * The next weight up or down, snapped to the increment.
     *
     * Snapping rather than adding, so that a weight arrived at some other way — imported,
     * typed, or left over from a smaller increment — is pulled onto the grid by the first
     * press rather than carrying its offset forever. Pressing up from 81 kg with a 2.5 kg
     * increment gives 82.5, not 83.5.
     */
    fun step(grams: Int, incrementGrams: Int, up: Boolean): Int {
        require(incrementGrams > 0) { "increment must be positive" }
        val onGrid = grams % incrementGrams == 0
        val next = when {
            up && onGrid -> grams + incrementGrams
            up -> (grams / incrementGrams + 1) * incrementGrams
            onGrid -> grams - incrementGrams
            else -> (grams / incrementGrams) * incrementGrams
        }
        return next.coerceAtLeast(0)
    }
}
