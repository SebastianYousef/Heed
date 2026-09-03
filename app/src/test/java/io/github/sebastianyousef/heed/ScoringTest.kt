package io.github.sebastianyousef.heed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.sebastianyousef.heed.data.AppPolicy
import io.github.sebastianyousef.heed.data.Decision
import io.github.sebastianyousef.heed.data.NotificationRecord
import io.github.sebastianyousef.heed.score.FeatureExtractor
import io.github.sebastianyousef.heed.score.OnlineClassifier
import io.github.sebastianyousef.heed.score.Rules
import io.github.sebastianyousef.heed.score.ScoringPipeline

private fun record(
    title: String? = null,
    text: String? = null,
    pkg: String = "com.example.app",
    category: String? = null,
    importance: Int = 3,
    hasPerson: Boolean = false,
) = NotificationRecord(
    sbnKey = "$pkg|$title|$text",
    packageName = pkg,
    appLabel = "Example",
    title = title,
    text = text,
    category = category,
    systemImportance = importance,
    hasPerson = hasPerson,
    // Fixed mid-afternoon timestamp so the time-of-day features do not make tests
    // depend on when they run.
    postedAt = 1_700_000_000_000L,
)

class FeatureExtractorTest {

    @Test
    fun `extraction is deterministic`() {
        val r = record(title = "Anna", text = "are you coming?")
        val a = FeatureExtractor.extract(r)
        val b = FeatureExtractor.extract(r)
        assertTrue(a.indices.contentEquals(b.indices))
        assertTrue(a.values.contentEquals(b.values))
    }

    @Test
    fun `otp detection needs both a code word and a digit run`() {
        assertTrue(FeatureExtractor.looksLikeOtp("your verification code is 448210"))
        assertTrue(FeatureExtractor.looksLikeOtp("din kod är 1234"))
        // A number alone is not a code — order confirmations are full of them.
        assertFalse(FeatureExtractor.looksLikeOtp("your order 448210 has shipped"))
        // A code word alone is not either.
        assertFalse(FeatureExtractor.looksLikeOtp("enter your code to continue"))
    }

    @Test
    fun `text block is length-normalised`() {
        val short = FeatureExtractor.extract(record(text = "hi"))
        val long = FeatureExtractor.extract(record(text = "word ".repeat(80)))
        fun textNorm(f: io.github.sebastianyousef.heed.score.Features): Double {
            var n = 0.0
            for (i in 0 until f.size) {
                if (f.indices[i] < FeatureExtractor.TEXT_DIM) n += f.values[i] * f.values[i]
            }
            return Math.sqrt(n)
        }
        // Both should sit at unit norm regardless of how much text there was.
        assertEquals(1.0, textNorm(short), 0.01)
        assertEquals(1.0, textNorm(long), 0.01)
    }
}

class RulesTest {

    @Test
    fun `calls and alarms are never filterable`() {
        assertEquals(true, Rules.evaluate(record(category = "call"), AppPolicy.LEARN).override)
        assertEquals(true, Rules.evaluate(record(category = "alarm"), AppPolicy.LEARN).override)
    }

    @Test
    fun `one-time codes outrank a never-alert policy`() {
        val otp = record(text = "Your login code is 992014")
        assertEquals(true, Rules.evaluate(otp, AppPolicy.NEVER_ALERT).override)
    }

    @Test
    fun `explicit per-app policy wins over the prior`() {
        val promo = record(text = "50% off everything", category = "promo")
        assertEquals(true, Rules.evaluate(promo, AppPolicy.ALWAYS_ALERT).override)
        assertEquals(false, Rules.evaluate(promo, AppPolicy.NEVER_ALERT).override)
    }

    @Test
    fun `an app that posts at minimum importance is taken at its word`() {
        assertEquals(false, Rules.evaluate(record(importance = 1), AppPolicy.LEARN).override)
    }

    @Test
    fun `promos score below direct messages`() {
        val promo = Rules.evaluate(record(category = "promo"), AppPolicy.LEARN).prior
        val msg = Rules.evaluate(record(category = "msg", hasPerson = true), AppPolicy.LEARN).prior
        assertTrue("expected msg ($msg) > promo ($promo)", msg > promo)
    }
}

class OnlineClassifierTest {

    @Test
    fun `learns to separate two kinds of notification`() {
        val classifier = OnlineClassifier()

        val wanted = listOf(
            record(title = "Anna", text = "are you coming tonight?", category = "msg", hasPerson = true),
            record(title = "Erik", text = "call me when you land", category = "msg", hasPerson = true),
            record(title = "Mum", text = "dinner at seven", category = "msg", hasPerson = true),
        )
        val unwanted = listOf(
            record(title = "Flash sale", text = "50% off everything today only", category = "promo"),
            record(title = "Weekend deals", text = "exclusive discount just for you", category = "promo"),
            record(title = "Last chance", text = "limited time offer, shop now", category = "promo"),
        )

        repeat(30) {
            wanted.forEach { classifier.train(FeatureExtractor.extract(it), 1f) }
            unwanted.forEach { classifier.train(FeatureExtractor.extract(it), 0f) }
        }

        for (r in wanted) {
            val p = classifier.predict(FeatureExtractor.extract(r))
            assertTrue("expected high score for '${r.title}', got $p", p > 0.7f)
        }
        for (r in unwanted) {
            val p = classifier.predict(FeatureExtractor.extract(r))
            assertTrue("expected low score for '${r.title}', got $p", p < 0.3f)
        }
    }

    @Test
    fun `generalises to notifications it has not seen`() {
        val classifier = OnlineClassifier()
        repeat(40) {
            classifier.train(
                FeatureExtractor.extract(record(text = "discount sale offer shop now", category = "promo")),
                0f,
            )
            classifier.train(
                FeatureExtractor.extract(record(text = "hey are you free later", category = "msg", hasPerson = true)),
                1f,
            )
        }
        val unseenPromo = classifier.predict(
            FeatureExtractor.extract(record(text = "exclusive offer, shop the sale", category = "promo"))
        )
        val unseenMessage = classifier.predict(
            FeatureExtractor.extract(record(text = "are you free tomorrow", category = "msg", hasPerson = true))
        )
        assertTrue("promo $unseenPromo should score below message $unseenMessage",
            unseenMessage > unseenPromo)
    }

    @Test
    fun `weights survive a round trip through serialisation`() {
        val original = OnlineClassifier()
        val sample = FeatureExtractor.extract(record(text = "hello there", category = "msg"))
        repeat(10) { original.train(sample, 1f) }
        val before = original.predict(sample)

        val restored = OnlineClassifier()
        restored.load(original.serialize(), original.bias, original.examplesSeen)

        assertEquals(before, restored.predict(sample), 1e-6f)
        assertEquals(original.examplesSeen, restored.examplesSeen)
    }

    @Test
    fun `confidence starts at zero and grows with examples`() {
        val classifier = OnlineClassifier()
        assertEquals(0f, classifier.confidence(), 1e-6f)
        val sample = FeatureExtractor.extract(record(text = "something"))
        repeat(60) { classifier.train(sample, 1f) }
        assertEquals(0.5f, classifier.confidence(), 0.01f)
    }
}

class ScoringPipelineTest {

    @Test
    fun `a cold model defers entirely to the rules`() {
        val classifier = OnlineClassifier()
        val pipeline = ScoringPipeline(classifier)

        val promo = pipeline.score(
            record(text = "50% off, shop now", category = "promo"),
            AppPolicy.LEARN, appChattiness = 0f, threshold = 0.55f,
        )
        assertEquals(Decision.SUPPRESSED, promo.decision)
        assertTrue(promo.reason.contains("model still learning"))
    }

    @Test
    fun `a trained model can overturn a weak rule prior`() {
        val classifier = OnlineClassifier()
        val pipeline = ScoringPipeline(classifier)

        // The user consistently opens this app's "social" notifications, which the rules
        // treat as low value by default.
        val liked = record(text = "someone replied to your post", category = "social")
        repeat(300) { classifier.train(FeatureExtractor.extract(liked), 1f, weight = 3f) }

        val result = pipeline.score(liked, AppPolicy.LEARN, appChattiness = 0f, threshold = 0.55f)
        assertEquals(Decision.ALERTED, result.decision)
        assertFalse(result.forced)
    }
}
