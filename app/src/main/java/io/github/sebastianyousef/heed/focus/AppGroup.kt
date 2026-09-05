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
    }
}
