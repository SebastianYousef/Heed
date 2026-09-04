package io.github.sebastianyousef.heed.focus

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the structure of the current screen, and nothing else.
 *
 * The one function here is the entire extent of Heed's screen access. It collects view
 * identifiers and class names — enough to recognise "this is the layout Discovery uses" —
 * and never touches `text` or `contentDescription`. That distinction is what makes
 * granting this service acceptable: it learns the shape of a feed without learning
 * anything that is in it.
 */
object SurfaceCapture {

    private const val MAX_DEPTH = 18
    private const val MAX_TOKENS = 400

    @Volatile var armed = false
        private set

    /** The UI asks for the next screen to be recorded. */
    fun arm() { armed = true }
    fun disarm() { armed = false }

    /**
     * Is a view with this id anywhere on screen?
     *
     * An indexed lookup rather than a walk of the tree. It is exact, it costs a fraction
     * of a full traversal, and it survives redesigns that move an element around as long
     * as the element keeps its id — which is far more often than a layout keeps its shape.
     * For the screens Heed ships anchors for, this replaces fingerprinting entirely.
     */
    fun hasAnchor(root: AccessibilityNodeInfo?, viewId: String): Boolean {
        root ?: return false
        return runCatching {
            root.findAccessibilityNodeInfosByViewId(viewId).isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * The event's own source node, for screens that identify themselves by the id of the
     * thing being scrolled rather than by anything in the window as a whole.
     */
    fun sourceHasId(node: AccessibilityNodeInfo?, viewId: String): Boolean =
        node?.viewIdResourceName == viewId

    /**
     * Is a view with this id not merely present, but actually on screen and big?
     *
     * Presence alone is the wrong test for a feed that shares a scrolling list with
     * something else. Snapchat's Community tab holds your friends' stories along the top
     * and Discover beneath them, and both are in the node tree from the moment the tab
     * opens — so "df_large_story exists" is true while you are still looking at your
     * friends, and "friend_card_frame exists" stays true after you have scrolled well
     * past them. Neither answers the question that matters, which is what is in front of
     * your eyes right now.
     *
     * Bounds do answer it. A card scrolled off the top has a negative bottom edge; one
     * below the fold starts past the screen height. Requiring a real intersection, and a
     * meaningful share of the viewport, turns "is it in the layout" into "is it what you
     * are looking at".
     */
    fun hasVisibleAnchor(
        root: AccessibilityNodeInfo?,
        viewId: String,
        screenHeight: Int,
        minFraction: Float = 0f,
    ): Boolean {
        root ?: return false
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }
            .getOrNull() ?: return false
        if (nodes.isEmpty()) return false

        val rect = android.graphics.Rect()
        var covered = 0
        for (node in nodes) {
            node ?: continue
            node.getBoundsInScreen(rect)
            val top = rect.top.coerceAtLeast(0)
            val bottom = rect.bottom.coerceAtMost(screenHeight)
            if (bottom > top && rect.width() > 0) covered += bottom - top
        }
        if (covered <= 0) return false
        if (minFraction <= 0f) return true
        return covered.toFloat() / screenHeight >= minFraction
    }

    /**
     * The view's own id and those of its nearest parents.
     *
     * A tap lands on whatever is under the finger — a thumbnail, a caption, a play icon —
     * never on the card that owns them. Walking a few levels up finds the card without
     * traversing anything else on screen.
     */
    fun selfAndAncestorIds(node: AccessibilityNodeInfo?, depth: Int): Set<String> {
        var current = node ?: return emptySet()
        val ids = LinkedHashSet<String>()
        var steps = 0
        while (steps <= depth) {
            current.viewIdResourceName?.let { ids += it }
            current = current.parent ?: break
            steps++
        }
        return ids
    }

    fun fingerprint(root: AccessibilityNodeInfo?): Set<String> {
        if (root == null) return emptySet()
        val tokens = LinkedHashSet<String>()
        walk(root, 0, tokens)
        return tokens
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, into: MutableSet<String>) {
        if (node == null || depth > MAX_DEPTH || into.size >= MAX_TOKENS) return

        // Structure only. Nothing here reads what the view says.
        node.viewIdResourceName?.let { into += "id:$it" }
        node.className?.let { into += "cls:$it@$depth" }

        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, into)
        }
    }
}
