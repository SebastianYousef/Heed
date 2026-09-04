package io.github.sebastianyousef.heed.core

import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Day boundaries and durations, in one place.
 *
 * Five files had grown their own copy of "midnight this morning" — the enforcer, the
 * scroll watcher, the view model, the statistics and the widget — and two had their own
 * duration formatter. None of them disagreed yet, which is exactly why it was worth
 * fixing before one of them did: a screen-time total and the limit being enforced against
 * it must agree about when the day starts, or a limit silently applies to the wrong
 * window.
 */
object Time {

    /** Midnight at the start of today, in the device's own time zone. */
    fun startOfToday(): Long = midnight(0)

    /** Midnight at the start of the day [daysAgo] days back. */
    fun startOfDaysAgo(daysAgo: Int): Long = midnight(-daysAgo)

    private fun midnight(offsetDays: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (offsetDays != 0) add(Calendar.DAY_OF_YEAR, offsetDays)
    }.timeInMillis

    const val DAY_MS = 86_400_000L

    /**
     * "2h 14m", or "14m". Hours matter, seconds do not — nobody has ever changed their
     * behaviour because a figure moved by forty seconds.
     */
    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        val hours = minutes / 60
        return when {
            minutes < 1 -> "under a minute"
            hours < 1 -> "${minutes}m"
            else -> "${hours}h ${minutes % 60}m"
        }
    }

    /** "just now", "12m ago", "3h ago", "2d ago". */
    fun relative(timestamp: Long): String {
        val delta = System.currentTimeMillis() - timestamp
        if (delta < TimeUnit.MINUTES.toMillis(1)) return "just now"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
        if (minutes < 60) return "${minutes}m ago"
        val hours = TimeUnit.MILLISECONDS.toHours(delta)
        if (hours < 24) return "${hours}h ago"
        return "${TimeUnit.MILLISECONDS.toDays(delta)}d ago"
    }
}
