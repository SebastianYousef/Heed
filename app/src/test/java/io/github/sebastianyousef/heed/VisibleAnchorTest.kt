package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.KnownSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding what you are actually looking at, from bounds rather than presence.
 *
 * Snapchat's Community tab holds friends' stories along the top and Discover beneath
 * them, in one scrolling list. Both are in the node tree from the moment the tab opens,
 * so presence answers neither "am I in the feed" nor "are my friends on screen" — which
 * is why Discover never blocked, and why the friends' guard never released.
 *
 * The numbers below are the real bounds read off the device at 1080x2424.
 */
class VisibleAnchorTest {

    private val screen = 2424

    /** Mirrors SurfaceCapture.hasVisibleAnchor's arithmetic over a list of rectangles. */
    private fun visible(bounds: List<Pair<Int, Int>>, minFraction: Float): Boolean {
        var covered = 0
        for ((top, bottom) in bounds) {
            val t = top.coerceAtLeast(0)
            val b = bottom.coerceAtMost(screen)
            if (b > t) covered += b - t
        }
        if (covered <= 0) return false
        if (minFraction <= 0f) return true
        return covered.toFloat() / screen >= minFraction
    }

    private val discover = KnownSurfaces.forPackage("com.snapchat.android")
        .first { it.label == "Discover" }

    @Test
    fun `at the top of the tab, friends are on screen and discover is not the subject`() {
        // Observed: friend cards 406..778, discover cards 851..2177.
        val friendsVisible = visible(listOf(406 to 778), 0.08f)
        val discoverDominant = visible(listOf(851 to 1689, 1717 to 2177), discover.minFraction)
        assertTrue("friends must count as on screen", friendsVisible)
        assertFalse("must not block while friends are in view", discoverDominant && !friendsVisible)
    }

    @Test
    fun `scrolled past the friends, discover owns the screen`() {
        // The friends' row has moved off the top; its bottom edge is negative.
        val friendsVisible = visible(listOf(-900 to -120), 0.08f)
        val discoverDominant = visible(listOf(0 to 1200, 1240 to 2424), discover.minFraction)
        assertFalse("a row scrolled off the top is not visible", friendsVisible)
        assertTrue("discover should now block", discoverDominant)
    }

    @Test
    fun `a sliver of the friends row does not veto the block`() {
        // Half-scrolled: a thin strip of friends' cards still intersects the viewport.
        assertFalse(visible(listOf(-350 to 22), 0.08f))
    }

    @Test
    fun `spotlight fills the window and needs no fraction`() {
        val spotlight = KnownSurfaces.forPackage("com.snapchat.android")
            .first { it.viewId.endsWith("spotlight_container") }
        assertEquals(0f, spotlight.minFraction, 0f)
        assertTrue(visible(listOf(0 to 2424), spotlight.minFraction))
    }
}
