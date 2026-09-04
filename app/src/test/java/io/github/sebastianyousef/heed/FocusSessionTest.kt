package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.FocusSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A focus session, and the three things it must never shut you out of.
 *
 * The blocking rule here is the inverse of every other one in Heed — everything is closed
 * unless you named it — which makes the exemptions load-bearing rather than polite. Two
 * of them are traps rather than preferences: blocking sends you to the home screen, so a
 * blocked home screen has nowhere to send you, and a blocked Heed hides the only button
 * that ends the session.
 */
class FocusSessionTest {

    private val t0 = 1_700_000_000_000L
    private val exempt = setOf("io.github.sebastianyousef.heed", "com.android.launcher3")

    private fun session(
        plannedMs: Long = 45 * 60_000L,
        allowed: Set<String> = setOf("com.notes"),
        endRequestedAt: Long = 0L,
    ) = FocusSession.State(
        label = "Study",
        startedAt = t0,
        plannedMs = plannedMs,
        allowed = allowed,
        endRequestedAt = endRequestedAt,
    )

    @Test
    fun `blocks anything not on the allowlist`() {
        assertTrue(FocusSession.blocks(session(), "com.instagram.android", exempt, t0 + 60_000))
    }

    @Test
    fun `allows what you named`() {
        assertFalse(FocusSession.blocks(session(), "com.notes", exempt, t0 + 60_000))
    }

    @Test
    fun `never blocks the launcher or Heed itself`() {
        // Blocking bounces to the home screen; blocking the home screen is a loop.
        assertFalse(FocusSession.blocks(session(), "com.android.launcher3", exempt, t0 + 60_000))
        // And you have to be able to reach the button that ends the session.
        assertFalse(
            FocusSession.blocks(session(), "io.github.sebastianyousef.heed", exempt, t0 + 60_000)
        )
    }

    /** The same guard [ScrollBreakTest] checks, for the same reason. */
    @Test
    fun `never blocks an authenticator`() {
        assertFalse(
            FocusSession.blocks(
                session(),
                "com.google.android.apps.authenticator2",
                exempt,
                t0 + 60_000,
            )
        )
    }

    @Test
    fun `stops blocking the moment the clock runs out`() {
        val s = session(plannedMs = 60_000L)
        assertTrue(FocusSession.blocks(s, "com.instagram.android", exempt, t0 + 59_000))
        assertFalse(FocusSession.blocks(s, "com.instagram.android", exempt, t0 + 60_000))
    }

    /** A stopwatch has no end, so it blocks until it is ended by hand. */
    @Test
    fun `a stopwatch never expires on its own`() {
        val s = session(plannedMs = 0L)
        assertFalse(s.expired(t0 + 10 * 3600_000L))
        assertEquals(null, s.remainingMs(t0))
        assertTrue(FocusSession.blocks(s, "com.instagram.android", exempt, t0 + 10 * 3600_000L))
    }

    @Test
    fun `no session blocks nothing`() {
        assertFalse(FocusSession.blocks(null, "com.instagram.android", exempt, t0))
    }

    /**
     * The asymmetry that the whole feature rests on: starting is instant, stopping waits.
     */
    @Test
    fun `ending early takes the full delay`() {
        val requested = t0 + 10 * 60_000L
        val s = session(endRequestedAt = requested)

        assertFalse(s.canEndNow(requested))
        assertEquals(FocusSession.END_DELAY_SECONDS, s.secondsUntilRelease(requested))
        assertFalse(s.canEndNow(requested + (FocusSession.END_DELAY_SECONDS - 1) * 1000L))
        assertTrue(s.canEndNow(requested + FocusSession.END_DELAY_SECONDS * 1000L))
    }

    @Test
    fun `a session with no end request can never be ended now`() {
        assertFalse(session().canEndNow(t0 + 10 * 3600_000L))
    }

    /** The countdown still blocks while it runs out — asking is not leaving. */
    @Test
    fun `asking to end does not stop the blocking`() {
        val s = session(endRequestedAt = t0 + 60_000)
        assertTrue(FocusSession.blocks(s, "com.instagram.android", exempt, t0 + 61_000))
    }

    @Test
    fun `remaining time counts down and never goes negative`() {
        val s = session(plannedMs = 60_000L)
        assertEquals(60_000L, s.remainingMs(t0))
        assertEquals(20_000L, s.remainingMs(t0 + 40_000))
        assertEquals(0L, s.remainingMs(t0 + 90_000))
    }
}
