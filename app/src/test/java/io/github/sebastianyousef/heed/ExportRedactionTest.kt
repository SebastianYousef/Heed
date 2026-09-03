package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.data.AppPolicy
import io.github.sebastianyousef.heed.data.AppPolicyRecord
import io.github.sebastianyousef.heed.data.Decision
import io.github.sebastianyousef.heed.data.DigestRecord
import io.github.sebastianyousef.heed.data.Feedback
import io.github.sebastianyousef.heed.data.LiveChannelRecord
import io.github.sebastianyousef.heed.data.NotificationRecord
import io.github.sebastianyousef.heed.export.ExportBuilder
import io.github.sebastianyousef.heed.export.Redaction
import io.github.sebastianyousef.heed.export.RedactionLevel
import io.github.sebastianyousef.heed.score.FeatureExtractor
import io.github.sebastianyousef.heed.score.OnlineClassifier
import io.github.sebastianyousef.heed.score.ScoringPipeline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every string here is planted somewhere a redacted export must never carry it. If a
 * future change starts serialising a new field, one of these will show up in the output
 * and this test fails.
 */
private const val SECRET_TITLE = "ZZTITLESECRETZZ"
private const val SECRET_BODY = "ZZBODYSECRETZZ"
private const val SECRET_BIG = "ZZBIGTEXTSECRETZZ"
private const val SECRET_SUB = "ZZSUBTEXTSECRETZZ"
private const val SECRET_CHANNEL = "chat_with_0701234567"
private const val SECRET_SUMMARY = "ZZDIGESTSUMMARYSECRETZZ"

private val ALL_SECRETS = listOf(
    SECRET_TITLE, SECRET_BODY, SECRET_BIG, SECRET_SUB, SECRET_CHANNEL, SECRET_SUMMARY,
    "0701234567",
)

private fun secretRecord() = NotificationRecord(
    sbnKey = "0|com.example.chat|1|$SECRET_CHANNEL|10123",
    packageName = "com.example.chat",
    appLabel = "Chat",
    title = SECRET_TITLE,
    text = "$SECRET_BODY your code is 994412",
    bigText = SECRET_BIG,
    subText = SECRET_SUB,
    category = "msg",
    channelId = SECRET_CHANNEL,
    systemImportance = 4,
    postedAt = 1_700_000_000_000L,
    score = 0.8f,
    scoreReason = "Direct message; From a named person",
    decision = Decision.ALERTED,
    feedback = Feedback.CLICKED,
)

private fun input(level: RedactionLevel) = ExportBuilder.Input(
    level = level,
    generatedAt = 1_700_000_100_000L,
    appVersion = "0.1.0 (1)",
    device = mapOf("manufacturer" to "Google", "model" to "Pixel 7"),
    settings = mapOf("threshold" to 0.55f),
    records = listOf(secretRecord()),
    policies = listOf(
        AppPolicyRecord(
            packageName = "com.example.chat",
            appLabel = "Chat",
            policy = AppPolicy.LEARN,
            alertedCount = 3,
            suppressedCount = 7,
        )
    ),
    liveChannels = listOf(
        LiveChannelRecord("com.example.fit", SECRET_CHANNEL, "Fit", 1_700_000_000_000L, 6)
    ),
    digests = listOf(
        DigestRecord(
            id = 1,
            createdAt = 1_700_000_000_000L,
            windowStart = 0,
            windowEnd = 1,
            notificationCount = 4,
            summary = SECRET_SUMMARY,
        )
    ),
    modelExamples = 42,
    modelConfidence = 0.41f,
    structuredWeights = mapOf("category:promo" to -0.8f),
)

class ExportRedactionTest {

    @Test
    fun `a redacted export carries none of your notification text`() {
        val json = ExportBuilder.build(input(RedactionLevel.REDACTED))
        for (secret in ALL_SECRETS) {
            assertFalse("redacted export leaked '$secret'", json.contains(secret))
        }
    }

    @Test
    fun `a stats-only export carries none of it either`() {
        val json = ExportBuilder.build(input(RedactionLevel.STATS_ONLY))
        for (secret in ALL_SECRETS) {
            assertFalse("stats-only export leaked '$secret'", json.contains(secret))
        }
    }

    @Test
    fun `the full export does contain the text, which proves the search works`() {
        // Without this, the two tests above would still pass if the builder emitted nothing.
        val json = ExportBuilder.build(input(RedactionLevel.FULL))
        assertTrue(json.contains(SECRET_TITLE))
        assertTrue(json.contains(SECRET_BODY))
        assertTrue(json.contains(SECRET_SUMMARY))
    }

    @Test
    fun `a redacted export still keeps what is needed to diagnose a decision`() {
        val root = JSONObject(ExportBuilder.build(input(RedactionLevel.REDACTED)))
        val row = root.getJSONArray("notifications").getJSONObject(0)

        assertEquals("Chat", row.getString("label"))
        assertEquals("msg", row.getString("category"))
        assertEquals("ALERTED", row.getString("decision"))
        assertEquals("CLICKED", row.getString("feedback"))
        assertTrue(row.getDouble("score") > 0.7)

        assertFalse("raw text must not be present", row.has("title"))
        assertFalse("raw text must not be present", row.has("text"))

        // The shape survives, so a misclassification is still explainable.
        val shape = row.getJSONObject("textShape")
        assertTrue(shape.getInt("chars") > 0)
        assertTrue("the OTP signal is preserved without the code", shape.getBoolean("looksLikeOtp"))
    }

    @Test
    fun `stats-only omits row-level data entirely`() {
        val root = JSONObject(ExportBuilder.build(input(RedactionLevel.STATS_ONLY)))
        assertFalse(root.has("notifications"))
        // Aggregates still present.
        assertEquals(1, root.getJSONObject("totals").getInt("captured"))
        assertEquals(42, root.getJSONObject("model").getInt("examplesSeen"))
    }

    @Test
    fun `digest summaries quote notifications, so they are held back unless full`() {
        val redacted = JSONObject(ExportBuilder.build(input(RedactionLevel.REDACTED)))
        val digest = redacted.getJSONArray("digests").getJSONObject(0)
        assertFalse(digest.has("summary"))
        assertEquals(4, digest.getInt("notificationCount"))
    }
}

class PseudonymTest {

    @Test
    fun `hashing a channel id does not carry the id`() {
        val hashed = Redaction.pseudonym(SECRET_CHANNEL)
        assertFalse(hashed.contains("0701234567"))
        assertFalse(hashed.contains("chat"))
    }

    @Test
    fun `the same channel hashes the same, so rows stay correlatable`() {
        assertEquals(Redaction.pseudonym("messages"), Redaction.pseudonym("messages"))
        assertTrue(Redaction.pseudonym("messages") != Redaction.pseudonym("promotions"))
    }
}

class ScoreReasonTest {

    @Test
    fun `the reason string never quotes the notification, since exports carry it`() {
        val pipeline = ScoringPipeline(OnlineClassifier())
        val result = pipeline.score(
            secretRecord(),
            AppPolicy.LEARN,
            appChattiness = 0f,
            threshold = 0.55f,
        )
        for (secret in ALL_SECRETS) {
            assertFalse("scoreReason leaked '$secret'", result.reason.contains(secret))
        }
    }
}

class TextShapeTest {

    @Test
    fun `shape measures without disclosing`() {
        val shape = Redaction.shape("Meet me at https://example.com about the 450 kr invoice")
        assertTrue(shape.hasUrl)
        assertTrue(shape.hasMoney)
        assertFalse(shape.looksLikeOtp)
        assertEquals(1, shape.digitGroups)
        assertTrue(shape.words > 5)
    }

    @Test
    fun `an empty notification shapes to zero rather than throwing`() {
        val shape = Redaction.shape(null)
        assertEquals(0, shape.chars)
        assertEquals(0, shape.words)
        assertFalse(shape.hasUrl)
    }

    @Test
    fun `feature names line up with the structured slots the model exposes`() {
        // The export reports weights by name; a mismatch would mislabel every one of them.
        val weights = OnlineClassifier().structuredWeights()
        assertEquals(FeatureExtractor.STRUCT_NAMES.size, weights.size)
        assertTrue(weights.containsKey("looks_like_otp"))
    }
}
