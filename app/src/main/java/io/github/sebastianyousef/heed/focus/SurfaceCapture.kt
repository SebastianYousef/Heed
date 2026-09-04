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
