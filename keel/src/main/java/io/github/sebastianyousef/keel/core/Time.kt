package io.github.sebastianyousef.keel.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Day boundaries and durations, in one place.
 *
 * Heed reached five private copies of "midnight this morning" — the enforcer, the watcher,
 * the view model, the statistics and the widget — and two of the duration formatter. None
 * of them disagreed yet, which is exactly why it was worth fixing before one of them did:
 * a total and the limit enforced against it must agree about when the day starts, or the
 * limit silently applies to the wrong window. The same is true of a day of steps and the
 * goal it is measured against, and of a training week and the volume in it.
 *
 * All of it in the device's own zone, deliberately. A session logged at 22:00 belongs to
 * the day you were in the gym, not to whatever UTC thought at the time — and a day that
 * moves under you when you fly is a smaller problem than a week that starts on Sunday
 * evening because the phone is east of Greenwich.
 */
object Time {

    const val DAY_MS = 86_400_000L

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Midnight at the start of today. */
    fun startOfToday(): Long = startOfDay(LocalDate.now(zone))

    /** Midnight at the start of the day [daysAgo] days back. */
    fun startOfDaysAgo(daysAgo: Int): Long = startOfDay(LocalDate.now(zone).minusDays(daysAgo.toLong()))

    /** Midnight at the start of the day containing [millis]. */
    fun startOfDayFor(millis: Long): Long = startOfDay(dateOf(millis))

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun dateOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Which hour of its own day [millis] falls in, 0..23. Steps are bucketed by this. */
    fun hourOf(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(zone).hour

    /**
     * Midnight on the Monday of the week containing [millis].
     *
     * Monday rather than the locale's own first day, because a training week is a unit
     * the user plans in and not a calendar convention — and because a volume figure that
     * silently reshuffles when the phone's locale changes is worse than one that is
     * always wrong in the same direction.
     */
    fun startOfWeekFor(millis: Long): Long =
        startOfDay(dateOf(millis).with(java.time.DayOfWeek.MONDAY))

    /** Whole days between the two, by calendar day rather than by elapsed milliseconds. */
    fun daysBetween(fromMillis: Long, toMillis: Long): Long =
        ChronoUnit.DAYS.between(dateOf(fromMillis), dateOf(toMillis))

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

    /**
     * "2:30", "0:45", "12:05" — a clock, for the one place a second is the unit.
     *
     * Separate from [duration] rather than a flag on it, because they are answering
     * different questions. A rest timer is read at a glance while you are counting down
     * to something, so it needs the seconds and the fixed width that stops the digits
     * jumping about; a session length is read afterwards, where seconds are noise.
     */
    fun clock(seconds: Long): String {
        val negative = seconds < 0
        val total = kotlin.math.abs(seconds)
        return buildString {
            if (negative) append('-')
            append(total / 60)
            append(':')
            append((total % 60).toString().padStart(2, '0'))
        }
    }

    /** "just now", "12m ago", "3h ago", "2d ago". */
    fun relative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val delta = Duration.ofMillis(now - timestamp)
        return when {
            delta.toMinutes() < 1 -> "just now"
            delta.toHours() < 1 -> "${delta.toMinutes()}m ago"
            delta.toDays() < 1 -> "${delta.toHours()}h ago"
            delta.toDays() < 7 -> "${delta.toDays()}d ago"
            else -> "${delta.toDays() / 7}w ago"
        }
    }
}
