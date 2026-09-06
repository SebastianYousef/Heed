package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.data.Decision
import io.github.sebastianyousef.heed.data.Feedback
import io.github.sebastianyousef.heed.data.NotificationRecord
import io.github.sebastianyousef.heed.score.FeatureExtractor
import io.github.sebastianyousef.heed.score.OnlineClassifier
import io.github.sebastianyousef.heed.usage.AttentionStat
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

    /**
     * The join itself now happens in SQLite — see `HeedDao.observeAttention`, which does
     * the same arithmetic over an index instead of pulling every notification and every
     * session into memory to add up a handful of numbers per app. What is left in Kotlin
     * is the one derived figure, and it is the one worth pinning: the number that turns
     * "this app interrupted you fourteen times" into something that stings.
     */
    @Test
    fun `minutes per alert is time attributed to alerts, not total time`() {
        val stat = AttentionStat(
            packageName = "com.example.feed",
            appLabel = "Feed",
            alerts = 2,
            openedFromAlert = 2,
            msFromAlerts = 40 * 60_000L,
            totalMs = 45 * 60_000L,   // includes a session the user started themselves
            markedNoise = 0,
            todayMs = 0,
            launchesToday = 0,
        )
        assertEquals(20.0, stat.minutesPerAlert, 0.01)
    }

    @Test
    fun `an app that never interrupted you costs you nothing per interruption`() {
        // Guards the division: an app you only ever open yourself has no alerts to divide
        // by, and must not report an infinite or NaN cost.
        val stat = AttentionStat(
            packageName = "com.example.feed",
            appLabel = "Feed",
            alerts = 0,
            openedFromAlert = 0,
            msFromAlerts = 0L,
            totalMs = 30 * 60_000L,
            markedNoise = 0,
            todayMs = 0,
            launchesToday = 0,
        )
        assertEquals(0.0, stat.minutesPerAlert, 0.0)
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
