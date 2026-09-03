package io.github.sebastianyousef.heed.export

import io.github.sebastianyousef.heed.data.AppPolicyRecord
import io.github.sebastianyousef.heed.data.DigestRecord
import io.github.sebastianyousef.heed.data.LiveChannelRecord
import io.github.sebastianyousef.heed.data.NotificationRecord
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Builds the export document.
 *
 * Deliberately free of Android dependencies so the redaction guarantees can be tested on
 * the JVM — there is a test that stuffs known secrets into every text field and asserts
 * they appear nowhere in the output. A privacy promise that is only enforced by reading
 * the code carefully is not a promise.
 */
object ExportBuilder {

    const val SCHEMA_VERSION = 1

    data class Input(
        val level: RedactionLevel,
        val generatedAt: Long,
        val appVersion: String,
        val device: Map<String, String>,
        val settings: Map<String, Any>,
        val records: List<NotificationRecord>,
        val policies: List<AppPolicyRecord>,
        val liveChannels: List<LiveChannelRecord>,
        val digests: List<DigestRecord>,
        val modelExamples: Int,
        val modelConfidence: Float,
        val structuredWeights: Map<String, Float>,
    )

    fun build(input: Input): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("generatedAt", input.generatedAt)
        root.put("appVersion", input.appVersion)
        root.put("redaction", input.level.name)
        root.put("about", aboutText(input.level))

        root.put("device", JSONObject(input.device.toMap()))
        root.put("settings", JSONObject(input.settings.toMap()))
        root.put("totals", totals(input.records))
        root.put("distributions", distributions(input.records))
        root.put("apps", apps(input.policies))
        root.put("liveDisplays", liveDisplays(input.liveChannels, input.level))
        root.put("model", model(input))
        root.put("digests", digests(input.digests, input.level))

        if (input.level != RedactionLevel.STATS_ONLY) {
            root.put("notifications", notifications(input.records, input.level))
        }
        return root.toString(2)
    }

    private fun aboutText(level: RedactionLevel): String = when (level) {
        RedactionLevel.STATS_ONLY ->
            "Heed export, aggregates only. No row-level data: nothing here can be traced " +
                "back to an individual notification."
        RedactionLevel.REDACTED ->
            "Heed export. Notification text has been replaced by its shape (length, word " +
                "count, whether it contained a link or a number). App names and scoring " +
                "decisions are included so misclassifications can be diagnosed; the words " +
                "in your notifications are not. Channel ids are hashed because some apps " +
                "embed phone numbers or account ids in them."
        RedactionLevel.FULL ->
            "Heed export, UNREDACTED. Contains the full text of your notifications, which " +
                "may include messages, one-time codes and account details. Do not share " +
                "this with anyone."
    }

    private fun totals(records: List<NotificationRecord>) = JSONObject().apply {
        put("captured", records.size)
        put("alerted", records.count { it.decision.name == "ALERTED" })
        put("suppressed", records.count { it.decision.name == "SUPPRESSED" })
        put("stillHeld", records.count { it.decision.name == "HELD" })
        put("withFeedback", records.count { it.feedback.name != "NONE" })
        put("oldestAt", records.minOfOrNull { it.postedAt } ?: 0L)
        put("newestAt", records.maxOfOrNull { it.postedAt } ?: 0L)
    }

    private fun distributions(records: List<NotificationRecord>) = JSONObject().apply {
        put("byDecision", countBy(records) { it.decision.name })
        put("byCapturePath", countBy(records) { it.capturePath.name })
        put("byFeedback", countBy(records) { it.feedback.name })
        put("byCategory", countBy(records) { it.category ?: "none" })

        // Hour of day matters for tuning quiet hours.
        val hours = JSONObject()
        val cal = Calendar.getInstance()
        val buckets = IntArray(24)
        for (r in records) {
            cal.timeInMillis = r.postedAt
            buckets[cal.get(Calendar.HOUR_OF_DAY)]++
        }
        buckets.forEachIndexed { hour, n -> if (n > 0) hours.put(hour.toString(), n) }
        put("byHourOfDay", hours)

        // Where the threshold actually sits relative to the scores being produced.
        val alerted = IntArray(10)
        val suppressed = IntArray(10)
        for (r in records) {
            val bucket = (r.score * 10).toInt().coerceIn(0, 9)
            if (r.decision.name == "ALERTED") alerted[bucket]++ else suppressed[bucket]++
        }
        put("scoreHistogram", JSONObject().apply {
            put("bucketWidth", 0.1)
            put("alerted", JSONArray(alerted.toList()))
            put("suppressed", JSONArray(suppressed.toList()))
        })
    }

    private fun apps(policies: List<AppPolicyRecord>) = JSONArray().apply {
        for (p in policies) {
            put(JSONObject().apply {
                put("package", p.packageName)
                put("label", p.appLabel)
                put("policy", p.policy.name)
                put("sourceSilenced", p.sourceSilenced)
                put("alerted", p.alertedCount)
                put("suppressed", p.suppressedCount)
            })
        }
    }

    private fun liveDisplays(channels: List<LiveChannelRecord>, level: RedactionLevel) =
        JSONArray().apply {
            for (c in channels) {
                put(JSONObject().apply {
                    put("package", c.packageName)
                    put("label", c.appLabel)
                    put(
                        "channel",
                        if (level == RedactionLevel.FULL) c.channelId
                        else Redaction.pseudonym(c.channelId),
                    )
                    put("burstSize", c.burstSize)
                    put("detectedAt", c.detectedAt)
                })
            }
        }

    private fun model(input: Input) = JSONObject().apply {
        put("examplesSeen", input.modelExamples)
        put("confidence", input.modelConfidence)
        put(
            "note",
            "confidence is how much of each decision the learned model carries; the rest " +
                "comes from the built-in rules.",
        )
        // The interpretable half of the model: what it has worked out about each signal.
        put("structuredWeights", JSONObject(input.structuredWeights.toMap()))
    }

    private fun digests(digests: List<DigestRecord>, level: RedactionLevel) = JSONArray().apply {
        for (d in digests) {
            put(JSONObject().apply {
                put("createdAt", d.createdAt)
                put("notificationCount", d.notificationCount)
                put("delivered", d.delivered)
                // A digest summary quotes notifications, so it only survives at FULL.
                if (level == RedactionLevel.FULL) put("summary", d.summary)
            })
        }
    }

    private fun notifications(records: List<NotificationRecord>, level: RedactionLevel) =
        JSONArray().apply {
            val cal = Calendar.getInstance()
            for (r in records) {
                cal.timeInMillis = r.postedAt
                val row = JSONObject().apply {
                    put("package", r.packageName)
                    put("label", r.appLabel)
                    put("category", r.category ?: JSONObject.NULL)
                    put(
                        "channel",
                        if (level == RedactionLevel.FULL) (r.channelId ?: JSONObject.NULL)
                        else Redaction.pseudonym(r.channelId),
                    )
                    put("systemImportance", r.systemImportance)
                    put("postedAt", r.postedAt)
                    put("hourOfDay", cal.get(Calendar.HOUR_OF_DAY))
                    put("hasPerson", r.hasPerson)
                    put("isGroupSummary", r.isGroupSummary)
                    put("updateCount", r.updateCount)
                    put("score", r.score)
                    put("decision", r.decision.name)
                    put("capturePath", r.capturePath.name)
                    put("feedback", r.feedback.name)
                    // Built from rule names and the app label, never from message text.
                    put("scoreReason", r.scoreReason)
                }

                if (level == RedactionLevel.FULL) {
                    row.put("title", r.title ?: JSONObject.NULL)
                    row.put("text", r.text ?: JSONObject.NULL)
                    row.put("bigText", r.bigText ?: JSONObject.NULL)
                    row.put("subText", r.subText ?: JSONObject.NULL)
                } else {
                    row.put("textShape", shapeJson(Redaction.shape(r.body)))
                }
                put(row)
            }
        }

    private fun shapeJson(shape: TextShape) = JSONObject().apply {
        put("chars", shape.chars)
        put("words", shape.words)
        put("digitGroups", shape.digitGroups)
        put("hasUrl", shape.hasUrl)
        put("hasEmail", shape.hasEmail)
        put("hasMoney", shape.hasMoney)
        put("looksLikeOtp", shape.looksLikeOtp)
    }

    private fun <T> countBy(items: List<T>, key: (T) -> String) = JSONObject().apply {
        items.groupingBy(key).eachCount().forEach { (k, v) -> put(k, v) }
    }
}
