package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.data.Decision
import io.github.sebastianyousef.heed.data.Feedback
import io.github.sebastianyousef.heed.data.NotificationRecord
import io.github.sebastianyousef.heed.score.FeatureExtractor
import io.github.sebastianyousef.heed.score.OnlineClassifier
import io.github.sebastianyousef.heed.usage.AttentionStats
import io.github.sebastianyousef.heed.usage.SessionJudge
import io.github.sebastianyousef.heed.usage.SessionQuality
import io.github.sebastianyousef.heed.usage.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun session(
    minutes: Long,
    scrolls: Int? = null,
    burstSeconds: Long = 0,
    pkg: String = "com.example.feed",
    trigger: Long? = null,
) = SessionRecord(
    id = trigger ?: 0,
    packageName = pkg,
    appLabel = "Feed",
    startedAt = 1_000_000L,
    endedAt = 1_000_000L + minutes * 60_000,
    durationMs = minutes * 60_000,
    triggerNotificationId = trigger,
    scrollEvents = scrolls,
    longestScrollBurstMs = burstSeconds * 1_000,
)

class SessionJudgeTest {

    @Test
    fun `a quick look is purposeful however much you scrolled`() {
        assertEquals(SessionQuality.PURPOSEFUL, SessionJudge.judge(session(1, scrolls = 200, burstSeconds = 60)))
    }

    @Test
    fun `long and scroll-dominated is doom scrolling`() {
        // 20 minutes, 600 scrolls (30/min), one unbroken 4-minute stretch.
        assertEquals(
            SessionQuality.SCROLLING,
            SessionJudge.judge(session(20, scrolls = 600, burstSeconds = 240)),
        )
    }

    @Test
    fun `long but slow is absorbed, not scrolling`() {
        // Reading an article: plenty of time, hardly any scrolling.
        assertEquals(
            SessionQuality.ABSORBED,
            SessionJudge.judge(session(20, scrolls = 40, burstSeconds = 240)),
        )
    }

    @Test
    fun `fast but broken up is not scrolling either`() {
        // Hunting through a list: high rate, but never a sustained stretch — you kept
        // stopping to look at things.
        assertEquals(
            SessionQuality.ABSORBED,
            SessionJudge.judge(session(20, scrolls = 600, burstSeconds = 20)),
        )
    }

    @Test
    fun `without the watcher we say we do not know rather than guessing`() {
        assertEquals(SessionQuality.UNKNOWN, SessionJudge.judge(session(20, scrolls = null)))
    }
}

class AttentionStatsTest {

    private fun notification(id: Long, pkg: String, decision: Decision, feedback: Feedback = Feedback.NONE) =
        NotificationRecord(
            id = id,
            sbnKey = "k$id",
            packageName = pkg,
            appLabel = "Feed",
            title = "t",
            postedAt = 1_000_000L,
            decision = decision,
            feedback = feedback,
        )

    @Test
    fun `attributes time back to the notification that caused it`() {
        val notifications = listOf(
            notification(1, "com.example.feed", Decision.ALERTED),
            notification(2, "com.example.feed", Decision.ALERTED),
            notification(3, "com.example.feed", Decision.SUPPRESSED),
        )
        val sessions = listOf(
            session(30, scrolls = 900, burstSeconds = 300, trigger = 1),
            session(10, scrolls = 20, trigger = 2),
            session(5),  // opened by hand, no trigger
        )

        val stat = AttentionStats.build(notifications, sessions).single()
        assertEquals(2, stat.alerts)             // suppressed one does not count as an interruption
        assertEquals(2, stat.openedFromAlert)
        assertEquals(40 * 60_000L, stat.msFromAlerts)
        assertEquals(45 * 60_000L, stat.totalMs)  // includes the self-initiated session
        assertEquals(1, stat.scrollingSessions)
        assertEquals(20.0, stat.minutesPerAlert, 0.01)
    }

    @Test
    fun `an app you only open yourself is not blamed for interrupting you`() {
        val stat = AttentionStats.build(emptyList(), listOf(session(30))).single()
        assertEquals(0, stat.alerts)
        assertEquals(0, stat.openedFromAlert)
        assertEquals(0L, stat.msFromAlerts)
        assertEquals(0.0, stat.minutesPerAlert, 0.0)
    }

    @Test
    fun `a session whose trigger has been deleted is not counted as attributed`() {
        // Retention can remove the notification while the session survives.
        val sessions = listOf(session(30, trigger = 999))
        val stat = AttentionStats.build(emptyList(), sessions).single()
        assertEquals(0, stat.openedFromAlert)
    }
}

class BaitFeedbackTest {

    /**
     * The correction that the doom-scrolling half makes to the notification half: a tap is
     * not proof of relevance. Bait works by being tapped.
     */
    @Test
    fun `a notification that leads to scrolling trains as a negative`() {
        val classifier = OnlineClassifier()
        val bait = NotificationRecord(
            sbnKey = "bait",
            packageName = "com.example.feed",
            appLabel = "Feed",
            title = "You have new suggestions",
            text = "see what people are posting",
            postedAt = 1_000_000L,
        )
        val features = FeatureExtractor.extract(bait)

        // Treated as a plain tap, the model would learn to like it.
        repeat(20) { classifier.train(features, 1f, weight = 1f) }
        val asClick = classifier.predict(features)
        assertTrue("a plain tap should read as positive", asClick > 0.7f)

        // Once the sessions that followed are known to be scrolling, it goes the other way.
        repeat(20) { classifier.train(features, 0f, weight = 1.5f) }
        assertTrue(
            "learning what the tap led to should pull it back down, got ${classifier.predict(features)}",
            classifier.predict(features) < asClick,
        )
    }
}
