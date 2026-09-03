package io.github.sebastianyousef.heed.export

import io.github.sebastianyousef.heed.score.FeatureExtractor
import org.json.JSONObject

/**
 * How much of your data an export is allowed to carry.
 *
 * The point of an export is to let someone else — a future you, or someone helping you
 * tune this — see why Heed made the calls it made. That diagnosis almost never needs the
 * words in your notifications, so the default does not include them.
 */
enum class RedactionLevel {
    /**
     * Aggregates only. Counts, distributions, per-app totals and what the model has
     * learned. No row-level data at all, so nothing can be traced to one notification.
     */
    STATS_ONLY,

    /**
     * Per-notification rows with every piece of text replaced by its shape — how long it
     * was, whether it held a link or a number, whether it looked like a one-time code.
     * Enough to debug a misclassification without disclosing what anyone said to you.
     */
    REDACTED,

    /**
     * Everything, including notification text. Only for reading yourself. Assume anything
     * you have ever been sent is in here: messages, one-time codes, account numbers.
     */
    FULL,
}

/**
 * What a piece of text looked like, without saying what it was.
 *
 * These are all derived measurements — none of them can be inverted back into the
 * original. They are also, not coincidentally, close to what the classifier itself sees,
 * which is what makes them useful for working out why it scored something the way it did.
 */
data class TextShape(
    val chars: Int,
    val words: Int,
    val digitGroups: Int,
    val hasUrl: Boolean,
    val hasEmail: Boolean,
    val hasMoney: Boolean,
    val looksLikeOtp: Boolean,
)

object Redaction {

    private val WORD = Regex("[^\\p{L}\\p{N}]+")
    private val DIGITS = Regex("\\d+")
    private val URL = Regex("https?://|www\\.")
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val MONEY = Regex("[€$£]\\s?\\d|\\d+\\s?(kr|sek|eur|usd|gbp)\\b", RegexOption.IGNORE_CASE)

    fun shape(text: String?): TextShape {
        val t = text.orEmpty()
        val lower = t.lowercase()
        return TextShape(
            chars = t.length,
            words = t.split(WORD).count { it.isNotBlank() },
            digitGroups = DIGITS.findAll(t).count(),
            hasUrl = URL.containsMatchIn(lower),
            hasEmail = EMAIL.containsMatchIn(lower),
            hasMoney = MONEY.containsMatchIn(lower),
            looksLikeOtp = FeatureExtractor.looksLikeOtp(lower),
        )
    }

    /** Compact JSON, stored on the row when its text is scrubbed. */
    fun encode(shape: TextShape): String = JSONObject().apply {
        put("chars", shape.chars)
        put("words", shape.words)
        put("digitGroups", shape.digitGroups)
        put("hasUrl", shape.hasUrl)
        put("hasEmail", shape.hasEmail)
        put("hasMoney", shape.hasMoney)
        put("looksLikeOtp", shape.looksLikeOtp)
    }.toString()

    fun decode(json: String): TextShape? = runCatching {
        val o = JSONObject(json)
        TextShape(
            chars = o.getInt("chars"),
            words = o.getInt("words"),
            digitGroups = o.getInt("digitGroups"),
            hasUrl = o.getBoolean("hasUrl"),
            hasEmail = o.getBoolean("hasEmail"),
            hasMoney = o.getBoolean("hasMoney"),
            looksLikeOtp = o.getBoolean("looksLikeOtp"),
        )
    }.getOrNull()

    /**
     * A stable, short, one-way label for a string that may itself be identifying.
     *
     * Notification channel ids are the case that matters: most apps use something generic
     * like "messages", but a few mint one per conversation and put a phone number or an
     * account id in it. Hashing keeps rows correlatable across an export without carrying
     * whatever the app decided to embed.
     */
    fun pseudonym(value: String?): String {
        if (value.isNullOrEmpty()) return "none"
        var h = 2166136261u
        for (c in value) { h = h xor c.code.toUInt(); h *= 16777619u }
        return "h" + h.toString(16).padStart(8, '0')
    }
}
