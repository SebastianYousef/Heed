package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.data.NotificationRecord
import io.github.sebastianyousef.heed.export.Redaction
import io.github.sebastianyousef.heed.score.FeatureExtractor
import io.github.sebastianyousef.heed.score.OnlineClassifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun record(text: String) = NotificationRecord(
    sbnKey = "k-$text",
    packageName = "com.example.app",
    appLabel = "Example",
    title = "Title",
    text = text,
    category = "msg",
    postedAt = 1_700_000_000_000L,
)

/** Simulates what the retention worker does to a row: shape recorded, words gone. */
private fun scrub(r: NotificationRecord) = r.copy(
    title = null,
    text = null,
    bigText = null,
    subText = null,
    textShape = Redaction.encode(Redaction.shape(r.body)),
    redactedAt = 1_700_000_500_000L,
)

class RetentionTest {

    @Test
    fun `scrubbing removes every readable field`() {
        val scrubbed = scrub(record("meet me at the library at six"))
        assertNull(scrubbed.title)
        assertNull(scrubbed.text)
        assertNull(scrubbed.bigText)
        assertNull(scrubbed.subText)
        assertEquals("", scrubbed.body)
    }

    @Test
    fun `scrubbing keeps everything that is not the words`() {
        val original = record("meet me at the library at six")
        val scrubbed = scrub(original)
        assertEquals(original.packageName, scrubbed.packageName)
        assertEquals(original.appLabel, scrubbed.appLabel)
        assertEquals(original.category, scrubbed.category)
        assertEquals(original.score, scrubbed.score)
        assertEquals(original.decision, scrubbed.decision)
        assertEquals(original.feedback, scrubbed.feedback)
        assertTrue(scrubbed.redactedAt != null)
    }

    @Test
    fun `the shape is preserved so a scrubbed row still explains itself`() {
        val original = record("your code is 448210")
        val shape = Redaction.decode(scrub(original).textShape!!)!!
        assertEquals(Redaction.shape(original.body).chars, shape.chars)
        assertTrue("the OTP signal outlives the code itself", shape.looksLikeOtp)
    }

    @Test
    fun `shape survives an encode-decode round trip`() {
        val shape = Redaction.shape("see https://example.com about the 450 kr invoice")
        assertEquals(shape, Redaction.decode(Redaction.encode(shape)))
    }

    /**
     * The guarantee the whole feature rests on. Deleting the words must not cost the app
     * anything it has learned — training already happened, into weights that live
     * somewhere else entirely.
     */
    @Test
    fun `scrubbing does not untrain the model`() {
        val classifier = OnlineClassifier()
        val wanted = listOf(record("are you coming tonight"), record("call me when you land"))
        val unwanted = listOf(record("50 percent off shop now"), record("exclusive deal today"))

        repeat(30) {
            wanted.forEach { classifier.train(FeatureExtractor.extract(it), 1f) }
            unwanted.forEach { classifier.train(FeatureExtractor.extract(it), 0f) }
        }

        val probe = record("hey are you free later")
        val before = classifier.predict(FeatureExtractor.extract(probe))
        val weightsBefore = classifier.serialize()
        val examplesBefore = classifier.examplesSeen

        // Scrub every row the model was trained on.
        (wanted + unwanted).map { scrub(it) }

        assertArrayEquals(
            "scrubbing must not touch a single weight",
            weightsBefore, classifier.serialize(),
        )
        assertEquals(examplesBefore, classifier.examplesSeen)
        assertEquals(
            "predictions must be identical after the text is gone",
            before, classifier.predict(FeatureExtractor.extract(probe)), 0f,
        )
        assertTrue("and it must still discriminate", before > 0.6f)
    }
}
