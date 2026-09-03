package se.kth.notiapp.digest

import se.kth.notiapp.data.NotificationRecord

/**
 * Turns a pile of filtered notifications into something a human reads in five seconds.
 *
 * Kept behind an interface because this is the one place in the app where a real language
 * model earns its keep. Classification cannot use one — it runs on the delivery path, on
 * every notification, all day. Summarisation runs a handful of times a day, off the hot
 * path, and can be deferred to when the phone is charging, so the cost model is completely
 * different.
 *
 * To add an on-device LLM (Gemma 3 1B via MediaPipe is the obvious pick — roughly 550 MB,
 * a second or two per digest on a recent phone):
 *
 *   1. add `implementation("com.google.mediapipe:tasks-genai:0.10.x")`
 *   2. download the .task bundle on first run over Wi-Fi, into filesDir
 *   3. implement this interface with LlmInference.createFromOptions(...) and prompt it
 *      with the same grouped structure [TemplateSummarizer] builds
 *   4. register it in [Summarizers.best] ahead of the template one
 *
 * Until then the templated summary below is genuinely fine, and costs nothing.
 */
interface Summarizer {
    val name: String
    suspend fun isAvailable(): Boolean
    suspend fun summarize(records: List<NotificationRecord>): String
}

object Summarizers {
    private val registry = mutableListOf<Summarizer>(TemplateSummarizer)

    /** Install a richer summarizer ahead of the default. */
    fun register(summarizer: Summarizer) {
        registry.add(0, summarizer)
    }

    suspend fun best(): Summarizer = registry.firstOrNull { it.isAvailable() } ?: TemplateSummarizer
}

/**
 * Deterministic, zero-cost summary. Groups by app, leads with whatever scored highest,
 * and names the senders it can — which in practice is most of what a digest needs to say.
 */
object TemplateSummarizer : Summarizer {

    override val name = "Template"
    override suspend fun isAvailable() = true

    override suspend fun summarize(records: List<NotificationRecord>): String {
        if (records.isEmpty()) return "Nothing was filtered."

        val byApp = records.groupBy { it.appLabel }.entries.sortedByDescending { it.value.size }
        val lines = mutableListOf<String>()

        // Lead with anything that came close to the threshold — these are the ones the
        // user is most likely to feel they should have seen.
        val borderline = records.filter { it.score >= 0.4f }.sortedByDescending { it.score }.take(3)
        if (borderline.isNotEmpty()) {
            lines += "Closest calls:"
            for (r in borderline) {
                val who = r.title?.takeIf { it.isNotBlank() } ?: r.appLabel
                val what = r.text?.take(70)?.replace('\n', ' ') ?: ""
                lines += "  • $who — $what"
            }
            lines += ""
        }

        lines += "Filed quietly:"
        for ((app, items) in byApp) {
            val categories = items.mapNotNull { it.category }.distinct()
            val hint = when {
                categories.contains("promo") -> " (promotions)"
                categories.contains("social") -> " (social)"
                categories.contains("email") -> " (email)"
                else -> ""
            }
            lines += "  • $app: ${items.size}$hint"
        }

        return lines.joinToString("\n")
    }
}
