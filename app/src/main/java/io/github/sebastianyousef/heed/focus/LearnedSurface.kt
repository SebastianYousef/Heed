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
    fun match(current: Set<String>, surfaces: List<LearnedSurface>): LearnedSurface? =
        surfaces
            .map { it to similarity(current, it.tokens) }
            .filter { it.second >= THRESHOLD }
            .sortedWith(compareByDescending<Pair<LearnedSurface, Double>> { it.second }
                .thenBy { it.first.block })
            .firstOrNull()?.first
}
