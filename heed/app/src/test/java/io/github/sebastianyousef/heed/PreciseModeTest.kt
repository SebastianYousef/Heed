package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.DetectionMode
import io.github.sebastianyousef.heed.focus.FocusMode
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.KnownScrollers
import io.github.sebastianyousef.heed.focus.KnownSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Snapchat regression, pinned.
 *
 * Blocking on a scroll count in an app that scrolls everywhere threw the user out of a
 * conversation with a friend the moment they scrolled it — Spotlight and a chat list emit
 * the same event, so behaviour alone genuinely cannot tell them apart. These tests fix
 * the contract that came out of that: a scroll count may only ever block in Automatic
 * mode, and apps Heed ships anchors for do not start in Automatic.
 */
class PreciseModeTest {

    private val snapchat = "com.snapchat.android"

    // The scroll-count cases themselves live in ScrollDecisionTest, against the function
    // the service actually calls. What is left here is the rule *shape* that keeps them
    // from arising: an app Heed can be precise in does not start out behavioural.

    @Test
    fun `apps with anchors are seeded into precise mode`() {
        val preset = KnownScrollers.presetFor(snapchat, "Snapchat")!!
        assertEquals(DetectionMode.PRECISE, preset.detection)
        assertTrue(KnownSurfaces.hasBlockAnchors(snapchat))

        // LinkedIn has no anchor, so there is nothing precise to be — and claiming
        // otherwise would leave it with a mode that can never fire.
        val linkedin = KnownScrollers.presetFor("com.linkedin.android", "LinkedIn")!!
        assertEquals(DetectionMode.BEHAVIOURAL, linkedin.detection)
    }

    @Test
    fun `a rule that only greys the screen is not a bedtime lockout`() {
        val grey = FocusRule(snapchat, "Snapchat", grayscale = true)
        assertTrue(grey.onlyChangesColour)

        val limited = FocusRule(snapchat, "Snapchat", dailyLaunchLimit = 5)
        assertFalse(limited.onlyChangesColour)
    }
}
