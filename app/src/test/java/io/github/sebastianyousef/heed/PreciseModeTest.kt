package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.DetectionMode
import io.github.sebastianyousef.heed.focus.FocusEnforcer
import io.github.sebastianyousef.heed.focus.FocusMode
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.KnownScrollers
import io.github.sebastianyousef.heed.focus.KnownSurfaces
import kotlinx.coroutines.runBlocking
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

    private class Fake(private val rule: FocusRule?) : FocusEnforcer.Data {
        override suspend fun rule(pkg: String) = rule
        override suspend fun scrollSecondsToday(pkg: String) = 0
        override suspend fun usageSecondsToday(pkg: String) = 0
        override suspend fun launchesToday(pkg: String) = 0
        override suspend fun isBedtime() = false
    }

    private val snapchat = "com.snapchat.android"

    @Test
    fun `precise mode never blocks on a scroll count`() = runBlocking {
        val rule = FocusRule(
            snapchat, "Snapchat",
            mode = FocusMode.BLOCK,
            scrollBudgetEvents = 4,
            detection = DetectionMode.PRECISE,
        )
        // Fifty scrolls in one unbroken burst: a chat history being read back, and
        // exactly the case that used to send the user to the home screen.
        assertEquals(
            FocusEnforcer.Verdict.Allow,
            FocusEnforcer(Fake(rule)).onScroll(snapchat, eventsThisBurst = 50, burstMs = 30_000),
        )
    }

    @Test
    fun `automatic mode still blocks on a scroll count`() = runBlocking {
        val rule = FocusRule(
            snapchat, "Snapchat",
            mode = FocusMode.BLOCK,
            scrollBudgetEvents = 4,
            detection = DetectionMode.BEHAVIOURAL,
        )
        assertTrue(
            FocusEnforcer(Fake(rule)).onScroll(snapchat, 10, 5_000)
                is FocusEnforcer.Verdict.Block
        )
    }

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
    fun `a rule that only greys the screen is not a bedtime lockout`() = runBlocking {
        val grey = FocusRule(snapchat, "Snapchat", grayscale = true)
        assertTrue(grey.onlyChangesColour)

        val limited = FocusRule(snapchat, "Snapchat", dailyLaunchLimit = 5)
        assertFalse(limited.onlyChangesColour)
    }
}
