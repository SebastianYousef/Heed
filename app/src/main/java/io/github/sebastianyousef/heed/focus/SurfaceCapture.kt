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
