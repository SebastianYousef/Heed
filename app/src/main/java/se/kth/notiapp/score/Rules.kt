package se.kth.notiapp.score

import se.kth.notiapp.data.AppPolicy
import se.kth.notiapp.data.NotificationRecord

/**
 * A rule verdict. [override] short-circuits the classifier entirely:
 * true  = always let this through, whatever the model thinks
 * false = always file this silently
 * null  = no strong opinion, here is a prior in [prior] instead
 */
data class RuleVerdict(
    val override: Boolean?,
    val prior: Float,
    val reasons: List<String>,
)

/**
 * Hand-written priors and hard overrides.
 *
 * These exist for two reasons. First, cold start: a fresh install has no training data,
 * and a phone that swallows your 2FA code on day one never gets a day two. Second,
 * there are categories where a learned model is simply the wrong tool — an alarm or an
 * incoming call should never be filterable no matter how consistently the user has
 * ignored them in the past.
 */
object Rules {

    /** Categories that are never worth interrupting for, whatever the text says. */
    private val LOW_VALUE_CATEGORIES = setOf("promo", "social", "recommendation")

    /** Categories that always ring through. */
    private val CRITICAL_CATEGORIES = setOf("call", "alarm")

    fun evaluate(record: NotificationRecord, policy: AppPolicy): RuleVerdict {
        val reasons = mutableListOf<String>()

        // --- hard overrides, highest priority first ---

        if (record.category in CRITICAL_CATEGORIES) {
            return RuleVerdict(true, 1f, listOf("Category is ${record.category} — never filtered"))
        }

        if (FeatureExtractor.looksLikeOtp(record.body.lowercase())) {
            return RuleVerdict(true, 1f, listOf("Looks like a one-time code"))
        }

        when (policy) {
            AppPolicy.ALWAYS_ALERT ->
                return RuleVerdict(true, 1f, listOf("You set ${record.appLabel} to always alert"))
            AppPolicy.NEVER_ALERT ->
                return RuleVerdict(false, 0f, listOf("You set ${record.appLabel} to never alert"))
            AppPolicy.LEARN -> Unit
        }

        // IMPORTANCE_HIGH+ set by the app itself is a weak signal (every app thinks it is
        // important), but IMPORTANCE_MIN/LOW is a strong one — the app is telling us it
        // does not expect to interrupt.
        if (record.systemImportance <= 1) {
            return RuleVerdict(false, 0f, listOf("App posted it as a silent/minimum-importance notification"))
        }

        // --- soft prior ---

        var prior = 0.4f

        if (record.category in LOW_VALUE_CATEGORIES) {
            prior -= 0.25f; reasons += "Category is ${record.category}"
        }
        if (record.category == "msg") {
            prior += 0.25f; reasons += "Direct message"
        }
        if (record.category == "event" || record.category == "reminder") {
            prior += 0.15f; reasons += "Calendar or reminder"
        }
        if (record.hasPerson) {
            prior += 0.15f; reasons += "From a named person"
        }
        if (record.isGroupSummary) {
            prior -= 0.15f; reasons += "Group summary rather than a new item"
        }
        if (record.systemImportance >= 4) {
            prior += 0.1f; reasons += "App marked it high importance"
        }

        val lower = record.body.lowercase()
        if (lower.contains("unsubscribe") || lower.contains("% off")) {
            prior -= 0.2f; reasons += "Marketing language"
        }

        if (reasons.isEmpty()) reasons += "No strong signals either way"
        return RuleVerdict(null, prior.coerceIn(0f, 1f), reasons)
    }
}
