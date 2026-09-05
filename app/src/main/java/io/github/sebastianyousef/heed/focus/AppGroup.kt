package io.github.sebastianyousef.heed.focus

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A set of apps that share one budget.
 *
 * Per-app limits have a hole in them that anyone who has used one has found. Thirty
 * minutes of Instagram, thirty of TikTok and thirty of Snapchat is an hour and a half of
 * the same activity, and every one of those limits reports success. The apps are
 * interchangeable — that is the whole point of them — so a limit that treats them
 * separately is a limit you satisfy by switching apps, which costs one tap and feels like
 * obeying the rule.
 *
 * A group budget is the honest version: name the thing you are actually trying to limit,
 * and spend against it wherever you spend it.
 *
 * Membership is stored on the group rather than on the rule, because an app in no group
 * is the common case and should cost nothing to represent. An app may belong to at most
 * one group; overlapping budgets would make "how much is left" a question with two
 * answers, and there is no honest way to pick between them.
 */
@Entity(tableName = "app_groups")
data class AppGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val name: String,

    /** Member packages, comma separated. */
    val packages: String = "",

    /** Seconds in any member app per day, across all of them. 0 means unlimited. */
    val dailyUsageSeconds: Int = 0,

    /** Times any member app may be opened per day, across all of them. 0 means unlimited. */
    val dailyLaunchLimit: Int = 0,

    /**
     * Seconds of scrolling per day across the group. 0 means unlimited.
     *
     * The one Heed cares most about, for the reason the per-app version exists: it
     * budgets the feed rather than the app, so a group of messaging-and-feed apps can be
     * held to ten minutes of scrolling while every conversation in them stays open.
     */
    val dailyScrollSeconds: Int = 0,

    /**
     * The colour this group's time is drawn in, as ARGB, or 0 for none.
     *
     * A fixed value rather than a Material role, for the same reason the productive and
     * distracting colours are fixed: a group's colour has to mean the same thing on every
     * device and in both themes, and a wallpaper-derived accent cannot promise that.
     *
     * 0 means the group makes no claim, and its apps are coloured by their category as
     * before — which is the right default, because a group is a budget first and a label
     * on a chart second.
     */
    val color: Int = 0,
) {
    val members: List<String>
        get() = packages.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun withMember(pkg: String, inGroup: Boolean): AppGroup {
        val next = members.toMutableSet()
        if (inGroup) next.add(pkg) else next.remove(pkg)
        return copy(packages = next.sorted().joinToString(","))
    }

    /** Nothing to enforce, so nothing to check on the hot path. */
    val hasLimits: Boolean
        get() = dailyUsageSeconds > 0 || dailyLaunchLimit > 0 || dailyScrollSeconds > 0

    companion object {
        /** Offered when creating one, because a blank name field is a decision nobody wants. */
        val SUGGESTIONS = listOf("Feeds", "Social", "Video", "News", "Games")

        /**
         * The colours a group may be given.
         *
         * A short list rather than a full picker, and the reason is legibility rather
         * than laziness: these have to stay apart from each other at nine pixels square
         * in a legend and as a thin segment of a bar, in both themes, and an arbitrary
         * colour cannot be held to that. Red first, because the most common thing anyone
         * wants to say about a group is that it is the bad one.
         */
        val COLOURS = listOf(
            0xFFE5484D.toInt(), // red
            0xFFF76B15.toInt(), // orange
            0xFFFFC53D.toInt(), // amber
            0xFF46A758.toInt(), // green
            0xFF00A2C7.toInt(), // cyan
            0xFF3E63DD.toInt(), // blue
            0xFF8E4EC6.toInt(), // purple
            0xFFE93D82.toInt(), // pink
        )
    }
}
