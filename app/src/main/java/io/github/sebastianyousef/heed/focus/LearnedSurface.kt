package io.github.sebastianyousef.heed.focus

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A screen you have pointed at and named.
 *
 * Blocking Snapchat's Discovery while leaving your friends' stories alone cannot be done
 * by measuring behaviour — both are scrolling. It needs to know which screen you are on.
 *
 * Rather than hardcoding view ids per app, which breaks with every redesign and only ever
 * covers apps someone remembered, Heed learns them: you open the screen, tell Heed to look,
 * and it records a fingerprint.
 *
 * The fingerprint is a set of **view identifiers and class names only** — the structural
 * skeleton of the layout. Heed never reads `text` or `contentDescription` from the tree, so
 * it learns the shape of Discovery without learning a word of what is on it. That is a
 * narrower thing than "read the screen", and it is the whole reason this is acceptable.
 */
@Entity(tableName = "learned_surfaces", indices = [Index("packageName")])
data class LearnedSurface(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    /** What you called it: "Discovery", "Spotlight", "Friends' stories". */
    val label: String,
    /** Newline-separated view ids and class names present when captured. */
    val fingerprint: String,
    /** Block on sight, or explicitly allow (so it overrides a broader block). */
    val block: Boolean,
    val capturedAt: Long,
) {
    val tokens: Set<String> get() = fingerprint.lineSequence().filter { it.isNotBlank() }.toSet()
}

/**
 * Screens that can be identified by a single distinctive view id.
 *
 * A whole-layout fingerprint is the general solution and needs no prior knowledge, but
 * when one id uniquely names a surface it is strictly better: exact, cheap, and far more
 * tolerant of redesigns, since a layout can be rearranged around an element that keeps its
 * id. It is also the difference between "you are scrolling in Snapchat" and "you are in
 * Spotlight", which is precisely the distinction that behaviour cannot make.
 *
 * These identifiers are facts about other apps' layouts, verifiable by anyone with a node
 * inspector — Android's own uiautomator will print them. The set below was cross-checked
 * against the Mindful project's list; no code was taken from it, which matters because
 * Mindful is GPL-2.0 and Heed is not (see LICENSE).
 */
object KnownSurfaces {

    /**
     * How a screen announces itself.
     *
     * [WINDOW] means "a view with this id exists anywhere in the current window", which is
     * how a feed that fills the screen is recognised. [SOURCE] means "the thing that was
     * just scrolled has this id" — needed for screens that host the feed inside a pager
     * without changing anything about the window around it, where a window-wide search
     * would also match the surrounding app.
     */
    enum class Match {
        /** A view with this id exists anywhere in the current window. */
        WINDOW,

        /** The view that was just scrolled has this id. */
        SOURCE,

        /** A view that was just tapped, or one of its parents, has this id. */
        CLICK,
    }

    data class Anchor(
        val packageName: String,
        val label: String,
        val viewId: String,
        /** False marks a screen that must never be blocked, whatever else matches. */
        val block: Boolean = true,
        val match: Match = Match.WINDOW,
        /**
         * A view whose presence vetoes this anchor.
         *
         * Needed because a screen is not always one thing. Snapchat's Community tab shows
         * your friends' stories along the top and the Discover feed underneath, in a
         * single scrolling list — so "df_large_story exists" is true the moment you open
         * the tab, while you are still looking at your friends. Guarding on the friends'
         * cards means the block only fires once they have scrolled away, which is exactly
         * the line the user drew: not the discovery feed, but never my friends.
         */
        val unless: String? = null,
    )

    /**
     * The identifiers themselves are facts about other apps' layouts — anyone can read
     * them out of a running app with Android's own uiautomator, and several are published
     * in more than one open-source blocker. The set below was cross-checked against the
     * Mindful project, which independently arrived at the same ids for Snapchat,
     * Instagram, YouTube and Reddit; the code that uses them here is Heed's own, because
     * Mindful is GPL-2.0 and Heed is MIT (see LICENSE).
     *
     * Note what is *not* here. There is no anchor for a chat list, a story tray or a
     * profile, because Heed only ever blocks on a positive match: anything it cannot
     * name is allowed. That is the property that keeps conversations safe, and it holds
     * even when Snapchat ships a redesign that breaks every id below — a stale anchor
     * fails open, into doing nothing.
     */
    val anchors = listOf(
        // Read off Snapchat 14.20.0.50 with uiautomator rather than taken from another
        // project's list — where they disagreed, the list was wrong. Mindful's
        // `spotlight_card_static_thumbnail` does not exist in this version at all, which
        // is a reminder that these rot silently and that failing open matters.
        Anchor("com.snapchat.android", "Spotlight", "com.snapchat.android:id/spotlight_container"),
        Anchor(
            "com.snapchat.android", "Discover", "com.snapchat.android:id/df_large_story",
            unless = "com.snapchat.android:id/friend_card_frame",
        ),
        // Tapping a Discover card is the other way into a recommended video, and the one
        // the guard above deliberately leaves open: while your friends' stories are on
        // screen the feed is not blocked, so the tap has to be. Nothing a friend posted
        // is inside a df_large_story, so this cannot catch them.
        Anchor(
            "com.snapchat.android", "a recommended video",
            "com.snapchat.android:id/df_large_story", match = Match.CLICK,
        ),
        Anchor("com.instagram.android", "Reels", "com.instagram.android:id/clips_video_container"),
        Anchor("com.instagram.android", "Explore", "com.instagram.android:id/action_bar_search_edit_text"),
        Anchor("com.google.android.youtube", "Shorts", "com.google.android.youtube:id/reel_player_underlay"),
        // Reddit's short feed names itself on the scrolled view rather than on the window,
        // and its id carries no package prefix.
        Anchor("com.reddit.frontpage", "Short feed", "feed_vertical_pager", match = Match.SOURCE),
    )

    fun forPackage(pkg: String): List<Anchor> {
        val direct = anchors.filter { it.packageName == pkg }
        if (direct.isNotEmpty()) return direct

        // Unofficial YouTube clients keep the upstream layout and its ids, and differ only
        // in package name — so the same anchor works if it is rewritten to match.
        if (pkg.contains("youtube", ignoreCase = true)) {
            return anchors.filter { it.packageName == "com.google.android.youtube" }
                .map { it.copy(packageName = pkg, viewId = it.viewId.replace("com.google.android.youtube", pkg)) }
        }
        return emptyList()
    }

    /** Apps Heed can tell the feed apart in, and so can safely run in Precise mode. */
    fun hasBlockAnchors(pkg: String) = forPackage(pkg).any { it.block }
}

object SurfaceMatcher {

    /**
     * Jaccard overlap. Layouts shift between visits — a different number of tiles, a
     * banner that is sometimes there — so an exact match is useless and a threshold is
     * necessary. 0.6 is high enough that Discovery and a chat list do not collide.
     */
    const val THRESHOLD = 0.6

    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }.toDouble()
        return intersection / (a.size + b.size - intersection)
    }

    /**
     * The best match above the threshold. An explicit allow wins ties against a block, so
     * teaching "friends' stories" carves a hole in a broader "block Snapchat feeds" rule.
     */
    fun match(current: Set<String>, surfaces: List<LearnedSurface>): LearnedSurface? {
        // A single-token surface is an anchor: one id that names the screen outright.
        // Presence is the whole test, and it beats any similarity score.
        val anchored = surfaces.filter { it.tokens.size == 1 }
            .filter { it.tokens.first() in current }
        if (anchored.isNotEmpty()) {
            return anchored.minByOrNull { if (it.block) 1 else 0 }
        }
        return matchByShape(current, surfaces)
    }

    private fun matchByShape(current: Set<String>, surfaces: List<LearnedSurface>): LearnedSurface? =
        surfaces
            .filter { it.tokens.size > 1 }
            .map { it to similarity(current, it.tokens) }
            .filter { it.second >= THRESHOLD }
            .sortedWith(compareByDescending<Pair<LearnedSurface, Double>> { it.second }
                .thenBy { it.first.block })
            .firstOrNull()?.first
}
