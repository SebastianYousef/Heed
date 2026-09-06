package io.github.sebastianyousef.ply.train

/**
 * What to put on the bar, per side.
 *
 * Worth having for one reason: the arithmetic is done standing over a bar with a number in
 * your head, it is done twenty times a session, and it is done wrong often enough that
 * people load 97.5 and record 100. The calculator makes the recorded weight and the loaded
 * weight the same thing.
 *
 * Greedy from the heaviest plate down, which is optimal here in a way it is not in general:
 * gym plates are near-perfectly divisible — every plate is a whole multiple of the one
 * below it in the standard metric set — so the greedy choice can never strand a remainder
 * that a different combination would have reached. Where an inventory *is* awkward (a gym
 * with 20s and 15s and no 10s), greedy can fall short, so the shortfall is reported rather
 * than hidden: telling you the bar will be 2.5 kg light is useful, and silently rounding is
 * how a log stops matching reality.
 */
object Plates {

    /**
     * One plate size and how many *pairs* of it are available.
     *
     * Pairs, not plates, because a barbell is loaded symmetrically and an odd plate is
     * unusable — an inventory counted in singles would offer a combination that cannot be
     * put on the bar.
     */
    data class Stock(val gramsEach: Int, val pairs: Int)

    data class Plan(
        /** Heaviest first, as they go on the bar. Grams each. */
        val perSide: List<Int>,
        /** What the bar will actually weigh once loaded this way. */
        val achievedGrams: Int,
        /** How far short of the target that is. Zero when it can be hit exactly. */
        val shortfallGrams: Int,
    ) {
        val exact: Boolean get() = shortfallGrams == 0
    }

    /**
     * The standard metric plates, in grams, with a pair count generous enough that the
     * default never invents a limit the user did not set.
     */
    val DEFAULT_STOCK = listOf(
        Stock(25_000, 4),
        Stock(20_000, 4),
        Stock(15_000, 2),
        Stock(10_000, 2),
        Stock(5_000, 2),
        Stock(2_500, 2),
        Stock(1_250, 2),
    )

    /** 20 kg. The bar itself, which is what makes the whole calculation necessary. */
    const val DEFAULT_BAR_GRAMS = 20_000

    fun plan(
        targetGrams: Int,
        barGrams: Int = DEFAULT_BAR_GRAMS,
        stock: List<Stock> = DEFAULT_STOCK,
    ): Plan? {
        // Below the bar there is nothing to say. A dumbbell or a machine has no bar and no
        // plates, and returning an empty plan for it would read as "load nothing" rather
        // than as "this does not apply".
        if (targetGrams < barGrams) return null

        var remainingPerSide = (targetGrams - barGrams) / 2
        val chosen = mutableListOf<Int>()
        for (entry in stock.sortedByDescending { it.gramsEach }) {
            var used = 0
            while (used < entry.pairs && remainingPerSide >= entry.gramsEach) {
                chosen += entry.gramsEach
                remainingPerSide -= entry.gramsEach
                used++
            }
        }

        val perSideTotal = chosen.sum()
        val achieved = barGrams + perSideTotal * 2
        return Plan(
            perSide = chosen,
            achievedGrams = achieved,
            // An odd gram left over by the halving is part of the shortfall, which is why
            // this is measured against the target rather than against remainingPerSide.
            shortfallGrams = targetGrams - achieved,
        )
    }
}
