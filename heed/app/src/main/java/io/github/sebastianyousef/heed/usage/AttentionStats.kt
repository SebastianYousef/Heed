package io.github.sebastianyousef.heed.usage


/**
 * What an app actually costs you, joined across both halves of Heed.
 *
 * Screen-time apps can produce [totalMs]. Notification apps can produce [alerts]. Only
 * something holding both can produce [openedFromAlert] and [msFromAlerts] — the chain
 * from "this app interrupted you" to "and that is where the evening went".
 *
 * Built by SQLite rather than in Kotlin. The version that assembled this in memory read
 * every notification and every session on each database change to add up a handful of
 * numbers per app, which is where most of the app's memory and startup cost went.
 */
data class AttentionStat(
    val packageName: String,
    val appLabel: String,
    /** Notifications from this app that Heed let through. */
    val alerts: Int,
    /** How many of those were followed by you opening the app. */
    val openedFromAlert: Int,
    /** Total foreground time attributable to those notifications. */
    val msFromAlerts: Long,
    /** Total foreground time in this app, however you got there. */
    val totalMs: Long,
    /** Notifications from this app you explicitly called noise. */
    val markedNoise: Int,
    /** Foreground time today only, which is the number people actually want. */
    val todayMs: Long,
    val launchesToday: Int,
) {
    /** Minutes of your time per notification this app sent. The number that stings. */
    val minutesPerAlert: Double
        get() = if (alerts == 0) 0.0 else (msFromAlerts / 60_000.0) / alerts
}
