package se.kth.notiapp.score

import se.kth.notiapp.data.AppPolicy
import se.kth.notiapp.data.Decision
import se.kth.notiapp.data.NotificationRecord

data class ScoreResult(
    val score: Float,
    val decision: Decision,
    val reason: String,
    /** True when a rule forced the outcome and the classifier was not consulted. */
    val forced: Boolean,
)

/**
 * Combines the rules and the learned model into one decision.
 *
 * The blend is deliberately weighted by how much the model has actually seen: on a fresh
 * install the rules decide everything, and the model fades in as the user teaches it.
 * Without this the first week would be a coin flip, which is precisely when a user
 * decides whether to keep the app.
 */
class ScoringPipeline(private val classifier: OnlineClassifier) {

    fun score(
        record: NotificationRecord,
        policy: AppPolicy,
        appChattiness: Float,
        threshold: Float,
    ): ScoreResult {
        val verdict = Rules.evaluate(record, policy)

        verdict.override?.let { forced ->
            return ScoreResult(
                score = if (forced) 1f else 0f,
                decision = if (forced) Decision.ALERTED else Decision.SUPPRESSED,
                reason = verdict.reasons.joinToString("; "),
                forced = true,
            )
        }

        val features = FeatureExtractor.extract(record, appChattiness)
        val modelScore = classifier.predict(features)
        val confidence = classifier.confidence()
        val blended = (1f - confidence) * verdict.prior + confidence * modelScore

        val reason = buildString {
            append(verdict.reasons.joinToString("; "))
            if (confidence > 0.05f) {
                append(" · model says ")
                append((modelScore * 100).toInt())
                append("% (trained on ")
                append(classifier.examplesSeen)
                append(" of your reactions)")
            } else {
                append(" · model still learning, using rules only")
            }
        }

        return ScoreResult(
            score = blended,
            decision = if (blended >= threshold) Decision.ALERTED else Decision.SUPPRESSED,
            reason = reason,
            forced = false,
        )
    }
}
