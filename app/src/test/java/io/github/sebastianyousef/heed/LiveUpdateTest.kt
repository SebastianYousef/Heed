package io.github.sebastianyousef.heed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.sebastianyousef.heed.capture.LiveUpdateDetector
import io.github.sebastianyousef.heed.capture.NotificationMapper

class LiveUpdateDetectorTest {

    private val stepCounter = Triple("com.example.pedometer", "steps", "0|com.example.pedometer|1|null|10123")

    @Test
    fun `a step counter rewriting itself is recognised as a live display`() {
        val detector = LiveUpdateDetector(threshold = 5, windowMs = 120_000)
        val (pkg, channel, key) = stepCounter
        var now = 1_000_000L

        // Four updates is not yet a pattern.
        repeat(4) {
            assertNull(detector.record(key, pkg, channel, now))
            now += 10_000
        }
        assertFalse(detector.isLive(pkg, channel))

        // The fifth within the window tips it over.
        val burst = detector.record(key, pkg, channel, now)
        assertNotNull("expected detection on the fifth update", burst)
        assertEquals(5, burst)
        assertTrue(detector.isLive(pkg, channel))
    }

    @Test
    fun `detection fires exactly once so we do not rewrite the row repeatedly`() {
        val detector = LiveUpdateDetector(threshold = 3, windowMs = 120_000)
        val (pkg, channel, key) = stepCounter
        var now = 0L
        repeat(3) { detector.record(key, pkg, channel, now); now += 1_000 }
        // Already known — every later update is a no-op.
        repeat(20) {
            assertNull(detector.record(key, pkg, channel, now))
            now += 1_000
        }
    }

    @Test
    fun `an occasional notification is never mistaken for a live display`() {
        val detector = LiveUpdateDetector(threshold = 5, windowMs = 120_000)
        var now = 0L
        // One message every ten minutes, well outside the window.
        repeat(20) {
            assertNull(detector.record("key", "com.example.chat", "messages", now))
            now += 600_000
        }
        assertFalse(detector.isLive("com.example.chat", "messages"))
    }

    @Test
    fun `detection is scoped to the channel, not the whole app`() {
        val detector = LiveUpdateDetector(threshold = 3, windowMs = 120_000)
        var now = 0L
        repeat(3) { detector.record("k1", "com.example.fit", "activity", now); now += 1_000 }

        assertTrue(detector.isLive("com.example.fit", "activity"))
        // The same app's actual alerts must still get through.
        assertFalse(detector.isLive("com.example.fit", "goal_reached"))
    }

    @Test
    fun `the user can undo a detection`() {
        val detector = LiveUpdateDetector(threshold = 2, windowMs = 120_000)
        detector.record("k", "com.example.app", "c", 0)
        detector.record("k", "com.example.app", "c", 1_000)
        assertTrue(detector.isLive("com.example.app", "c"))

        detector.forget("com.example.app", "c")
        assertFalse(detector.isLive("com.example.app", "c"))
    }

    @Test
    fun `a channel with no id still tracks separately from a named one`() {
        val detector = LiveUpdateDetector(threshold = 2, windowMs = 120_000)
        detector.record("k", "com.example.app", null, 0)
        detector.record("k", "com.example.app", null, 500)
        assertTrue(detector.isLive("com.example.app", null))
        assertFalse(detector.isLive("com.example.app", "named"))
    }
}

class ContentHashTest {

    @Test
    fun `identical content hashes the same`() {
        assertEquals(
            NotificationMapper.contentHash("Steps", "8,432 steps", null, null),
            NotificationMapper.contentHash("Steps", "8,432 steps", null, null),
        )
    }

    @Test
    fun `a changed step count hashes differently`() {
        val before = NotificationMapper.contentHash("Steps", "8,432 steps", null, null)
        val after = NotificationMapper.contentHash("Steps", "8,449 steps", null, null)
        assertTrue("a live update must be detectable as a change", before != after)
    }

    @Test
    fun `an absent field and an empty one are the same content`() {
        // Both render as nothing, so for change detection they are the same notification.
        // Treating them as different would make an app that swaps null for "" look like
        // it posted something new.
        assertEquals(
            NotificationMapper.contentHash("t", null, null, null),
            NotificationMapper.contentHash("t", "", null, null),
        )
    }

    @Test
    fun `field order matters, so moved text counts as a change`() {
        assertTrue(
            NotificationMapper.contentHash("a", "b", null, null) !=
                NotificationMapper.contentHash("b", "a", null, null)
        )
    }
}
