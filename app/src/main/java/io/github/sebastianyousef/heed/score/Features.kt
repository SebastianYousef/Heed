package io.github.sebastianyousef.heed.score

import io.github.sebastianyousef.heed.data.NotificationRecord
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sparse feature vector. Only non-zero entries are stored, which is what makes the
 * whole classification path cheap enough to run on the notification-enqueue critical
 * path (target: well under 10ms, since NotificationAssistantService is timed).
 */
class Features(val indices: IntArray, val values: FloatArray) {
    val size get() = indices.size
}

/**
 * Turns a notification into a fixed-width sparse vector using feature hashing, so we
 * never have to ship or grow a vocabulary. Three blocks:
 *
 *  - text     hashed uni/bigrams of title+body, L2-normalised
 *  - app      hashed package name, so the model can learn "Slack is usually noise"
 *  - struct   hand-built signals (category, importance, time of day, OTP, ...)
 *
 * This is deliberately not a neural embedding. A hashed linear model trains online from
 * a handful of examples, costs microseconds, and its weights are directly inspectable —
 * all three matter more here than the last few points of accuracy. If you later want
 * semantic matching, swap this class for a MiniLM ONNX encoder; nothing downstream
 * changes as long as [DIM] stays consistent.
 */
object FeatureExtractor {

    const val TEXT_DIM = 4096
    const val APP_DIM = 256
    const val STRUCT_DIM = 32
    const val DIM = TEXT_DIM + APP_DIM + STRUCT_DIM

    private const val APP_OFFSET = TEXT_DIM
    private const val STRUCT_OFFSET = TEXT_DIM + APP_DIM

    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** A number that looks like a one-time code, next to a word that says it is one. */
    private val OTP_WORDS = setOf(
        "code", "otp", "verification", "verify", "passcode", "pin", "2fa", "authenticate",
        "kod", "verifiering", "engangskod", "inloggning", "bankid",
    )
    private val DIGIT_RUN = Regex("\\b\\d{4,8}\\b")

    private val PROMO_WORDS = setOf(
        "sale", "off", "discount", "deal", "deals", "offer", "offers", "promo", "coupon",
        "save", "free", "limited", "exclusive", "shop", "now", "unsubscribe", "newsletter",
        "rea", "rabatt", "erbjudande", "kampanj", "gratis",
    )
    private val URGENT_WORDS = setOf(
        "urgent", "asap", "immediately", "deadline", "due", "overdue", "expires", "expiring",
        "cancelled", "canceled", "delayed", "failed", "declined", "alert", "emergency",
        "confirm", "confirmed", "arriving", "delivered", "payment", "invoice", "reminder",
        "brådskande", "omedelbart", "förfaller", "inställd", "försenad", "påminnelse", "faktura",
    )
    private val URL = Regex("https?://|www\\.")
    private val MONEY = Regex("[€$£]\\s?\\d|\\d+\\s?(kr|sek|eur|usd|gbp)\\b", RegexOption.IGNORE_CASE)

    /**
     * Human-readable names for the structured slots, aligned to the indices in [S].
     * Used by the data export so a learned weight can be reported as something a person
     * can reason about rather than an offset into a float array.
     */
    val STRUCT_NAMES: List<String> = listOf(
        "category:message", "category:call", "category:alarm", "category:event",
        "category:reminder", "category:promo", "category:social", "category:email",
        "category:service", "from_named_person", "system_importance", "group_summary",
        "hour_sin", "hour_cos", "night", "looks_like_otp", "mentions_money",
        "promo_words", "urgent_words", "text_length", "has_title", "has_url",
        "app_chattiness",
    )

    /** Structured slot indices, relative to [STRUCT_OFFSET]. */
    private object S {
        const val CAT_MSG = 0; const val CAT_CALL = 1; const val CAT_ALARM = 2
        const val CAT_EVENT = 3; const val CAT_REMINDER = 4; const val CAT_PROMO = 5
        const val CAT_SOCIAL = 6; const val CAT_EMAIL = 7; const val CAT_SERVICE = 8
        const val HAS_PERSON = 9; const val IMPORTANCE = 10; const val GROUP_SUMMARY = 11
        const val HOUR_SIN = 12; const val HOUR_COS = 13; const val NIGHT = 14
        const val OTP = 15; const val MONEY = 16; const val PROMO_HITS = 17
        const val URGENT_HITS = 18; const val LENGTH = 19; const val HAS_TITLE = 20
        const val HAS_URL = 21; const val APP_CHATTINESS = 22
    }

    /**
     * @param appChattiness how much this app notifies relative to everything else, 0..1.
     *        Passed in rather than looked up so extraction stays pure and fast.
     */
    fun extract(record: NotificationRecord, appChattiness: Float = 0f): Features {
        val idx = ArrayList<Int>(96)
        val vals = ArrayList<Float>(96)

        // --- text block ---
        val text = record.body.lowercase()
        val tokens = text.split(TOKEN_SPLIT).filter { it.length in 2..24 }.take(120)
        val textBuckets = HashMap<Int, Float>(tokens.size * 2)
        var promoHits = 0
        var urgentHits = 0
        for ((i, t) in tokens.withIndex()) {
            addHashed(textBuckets, t, TEXT_DIM, 0)
            if (i > 0) addHashed(textBuckets, tokens[i - 1] + "_" + t, TEXT_DIM, 0)
            if (t in PROMO_WORDS) promoHits++
            if (t in URGENT_WORDS) urgentHits++
        }
        // L2-normalise so a long email body does not outweigh a two-word SMS.
        var norm = 0f
        for (v in textBuckets.values) norm += v * v
        norm = if (norm > 0f) sqrt(norm) else 1f
        for ((k, v) in textBuckets) { idx += k; vals += v / norm }

        // --- app identity block ---
        addSingle(idx, vals, APP_OFFSET + bucket(record.packageName, APP_DIM), 1f)

        // --- structured block ---
        fun struct(slot: Int, value: Float) {
            if (value != 0f) addSingle(idx, vals, STRUCT_OFFSET + slot, value)
        }
        when (record.category) {
            "msg" -> struct(S.CAT_MSG, 1f)
            "call" -> struct(S.CAT_CALL, 1f)
            "alarm" -> struct(S.CAT_ALARM, 1f)
            "event" -> struct(S.CAT_EVENT, 1f)
            "reminder" -> struct(S.CAT_REMINDER, 1f)
            "promo" -> struct(S.CAT_PROMO, 1f)
            "social" -> struct(S.CAT_SOCIAL, 1f)
            "email" -> struct(S.CAT_EMAIL, 1f)
            "progress", "service", "transport", "sys" -> struct(S.CAT_SERVICE, 1f)
        }
        struct(S.HAS_PERSON, if (record.hasPerson) 1f else 0f)
        struct(S.IMPORTANCE, record.systemImportance / 5f)
        struct(S.GROUP_SUMMARY, if (record.isGroupSummary) 1f else 0f)

        val cal = Calendar.getInstance().apply { timeInMillis = record.postedAt }
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        val radians = (hour / 24f) * 2f * Math.PI.toFloat()
        struct(S.HOUR_SIN, sin(radians))
        struct(S.HOUR_COS, cos(radians))
        struct(S.NIGHT, if (hour >= 22f || hour < 7f) 1f else 0f)

        struct(S.OTP, if (looksLikeOtp(text)) 1f else 0f)
        struct(S.MONEY, if (MONEY.containsMatchIn(text)) 1f else 0f)
        struct(S.PROMO_HITS, minOf(promoHits, 5) / 5f)
        struct(S.URGENT_HITS, minOf(urgentHits, 5) / 5f)
        struct(S.LENGTH, minOf(text.length, 400) / 400f)
        struct(S.HAS_TITLE, if (!record.title.isNullOrBlank()) 1f else 0f)
        struct(S.HAS_URL, if (URL.containsMatchIn(text)) 1f else 0f)
        struct(S.APP_CHATTINESS, appChattiness)

        return Features(idx.toIntArray(), vals.toFloatArray())
    }

    fun looksLikeOtp(lowercaseText: String): Boolean =
        DIGIT_RUN.containsMatchIn(lowercaseText) &&
            lowercaseText.split(TOKEN_SPLIT).any { it in OTP_WORDS }

    private fun addSingle(idx: ArrayList<Int>, vals: ArrayList<Float>, index: Int, value: Float) {
        idx += index; vals += value
    }

    /**
     * Signed feature hashing: the sign bit of a second hash decides whether a token adds
     * or subtracts, so collisions cancel out on average instead of compounding.
     */
    private fun addHashed(into: HashMap<Int, Float>, token: String, dim: Int, offset: Int) {
        val h = fnv1a(token)
        val slot = offset + ((h shr 1) % dim.toUInt()).toInt()
        val sign = if (h and 1u == 1u) 1f else -1f
        into[slot] = (into[slot] ?: 0f) + sign
    }

    private fun bucket(s: String, dim: Int): Int = ((fnv1a(s) shr 1) % dim.toUInt()).toInt()

    private fun fnv1a(s: String): UInt {
        var h = 2166136261u
        for (c in s) { h = h xor c.code.toUInt(); h *= 16777619u }
        return h
    }
}
