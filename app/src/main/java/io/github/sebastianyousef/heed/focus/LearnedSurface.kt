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

    data class Anchor(
        val packageName: String,
        val label: String,
        val viewId: String,
        /** False marks a screen that must never be blocked, whatever else matches. */
        val block: Boolean = true,
    )

    val anchors = listOf(
        Anchor("com.snapchat.android", "Spotlight", "com.snapchat.android:id/spotlight_card_static_thumbnail"),
        Anchor("com.snapchat.android", "Discover", "com.snapchat.android:id/df_large_story"),
        Anchor("com.instagram.android", "Reels", "com.instagram.android:id/clips_video_container"),
        Anchor("com.instagram.android", "Explore", "com.instagram.android:id/action_bar_search_edit_text"),
        Anchor("com.google.android.youtube", "Shorts", "com.google.android.youtube:id/reel_player_underlay"),
        Anchor("com.reddit.frontpage", "Short feed", "feed_vertical_pager"),
    )

    fun forPackage(pkg: String) = anchors.filter { it.packageName == pkg }

    /** Apps Heed can tell the feed apart in, and so can safely run in Precise mode. */
    fun hasBlockAnchors(pkg: String) = anchors.any { it.packageName == pkg && it.block }
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
