package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.data.NotificationRecord
import io.github.sebastianyousef.heed.score.FeatureExtractor
import io.github.sebastianyousef.heed.score.OnlineClassifier
import io.github.sebastianyousef.heed.score.SenderHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Learning who matters, and when.
 *
 * "Is this from WhatsApp" is a much weaker question than "is this from the person I
 * always reply to". The app name cannot separate a partner from the flat's bin-day
 * group, which is how a filter ends up either interrupting for everything or burying the
 * one message that mattered.
 */
class SenderFeatureTest {

    private fun record(
        conversation: String?,
        text: String = "are you around",
        hour: Int = 12,
    ): NotificationRecord {
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
        }.timeInMillis
        return NotificationRecord(
            sbnKey = "k$conversation$hour",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "Someone",
            text = text,
            category = "msg",
            postedAt = at,
            conversationId = conversation,
        )
    }

    @Test
    fun `different senders in the same app get different features`() {
        val a = FeatureExtractor.extract(record("p:aaa")).indices.toSet()
        val b = FeatureExtractor.extract(record("p:bbb")).indices.toSet()
        assertNotEquals(a, b)
    }

    @Test
    fun `a notification with no sender still extracts`() {
        val f = FeatureExtractor.extract(record(null))
        assertTrue(f.size > 0)
        assertTrue(f.indices.all { it < FeatureExtractor.DIM })
    }

    @Test
    fun `the model can learn one sender matters and another does not`() {
        val classifier = OnlineClassifier(dim = FeatureExtractor.DIM)
        val partner = record("p:partner", "dinner?")
        val binday = record("p:binday", "recycling tomorrow")

        repeat(25) {
            classifier.train(FeatureExtractor.extract(partner), label = 1f, weight = 1f)
            classifier.train(FeatureExtractor.extract(binday), label = 0f, weight = 1f)
        }

        val forPartner = classifier.predict(FeatureExtractor.extract(partner))
        val forBinday = classifier.predict(FeatureExtractor.extract(binday))
        assertTrue(
            "same app, same category, opposite outcomes: $forPartner vs $forBinday",
            forPartner - forBinday > 0.4f,
        )
    }

    @Test
    fun `the same sender at a different hour is a different picture`() {
        val morning = FeatureExtractor.extract(
            record("p:standup", hour = 9),
            sender = SenderHistory(seen = 40, engagement = 0.8f, engagementAtHour = 0.9f),
        )
        val midnight = FeatureExtractor.extract(
            record("p:standup", hour = 23),
            sender = SenderHistory(seen = 40, engagement = 0.8f, engagementAtHour = 0.05f),
        )
        // Same conversation and same words; only the hour and its history differ.
        assertNotEquals(morning.values.toList(), midnight.values.toList())
    }

    @Test
    fun `never having seen a sender is neutral, not negative`() {
        val unknown = SenderHistory.UNKNOWN
        assertEquals(0, unknown.seen)
        assertEquals(0f, unknown.engagement, 0f)
        // A first message from someone new is judged on its content. Treating unfamiliar
        // as unwanted would filter exactly the messages that most need to get through.
        assertEquals(0f, unknown.engagementAtHour, 0f)
    }
}
