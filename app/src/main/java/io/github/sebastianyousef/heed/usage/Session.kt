package io.github.sebastianyousef.heed.usage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One continuous stretch with an app in the foreground.
 *
 * Built from UsageStatsManager events rather than anything invasive: the system already
 * records when an activity is resumed and paused, and that is all a session is.
 */
@Entity(
    tableName = "sessions",
    indices = [Index("startedAt"), Index("packageName"), Index("triggerNotificationId")],
)
data class SessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val packageName: String,
    val appLabel: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,

    /**
     * The notification this session appears to have come from, if any.
     *
     * This is the whole point of putting the two halves in one app. A screen-time app
     * knows you spent forty minutes in Instagram; it cannot know that a notification you
     * had already marked as noise is what put you there.
     */
    val triggerNotificationId: Long? = null,

    /** Scroll events observed during the session, or null if the watcher was off. */
    val scrollEvents: Int? = null,

    /** Longest sustained stretch of scrolling within the session, in ms. */
    val longestScrollBurstMs: Long? = null,

    /** True once this session has been folded back into the classifier. */
    val trainedOn: Boolean = false,
)

/**
 * A stretch of scrolling recorded by the accessibility watcher.
 *
 * Contains counts and timestamps and nothing else — no text, no view ids, no screen
 * content. The watcher never asks for any of that; see ScrollWatcherService.
 */
@Entity(tableName = "scroll_spans", indices = [Index("startedAt"), Index("consumed")])
data class ScrollSpan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startedAt: Long,
    val endedAt: Long,
    val events: Int,
    val longestBurstMs: Long,
    /** True once folded into a session. */
    val consumed: Boolean = false,
)

/**
 * A judgement about how a session was spent.
 *
 * Deliberately behavioural. "Doom scrolling" is not a place you go, it is a thing you do:
 * long, fast, continuous scrolling that you did not set out to do. Measuring the behaviour
 * means never having to read the screen, and means it works in an app nobody has added to
 * a list yet.
 */
enum class SessionQuality {
    /** Short, or with little scrolling. Someone opened an app and did a thing. */
    PURPOSEFUL,

    /** Long, but not scroll-dominated. Reading, watching, messaging. */
    ABSORBED,

    /** Long and scroll-dominated. The one worth interrupting. */
    SCROLLING,

    /** The watcher was not running, so scrolling is unknown. */
    UNKNOWN,
}

object SessionJudge {

    /** Scroll events per minute above which a stretch counts as feed-scrolling. */
    const val SCROLL_RATE_THRESHOLD = 25.0

    /** Below this a session is too short to be worth judging at all. */
    const val MIN_INTERESTING_MS = 90_000L

    fun judge(session: SessionRecord): SessionQuality {
        if (session.durationMs < MIN_INTERESTING_MS) return SessionQuality.PURPOSEFUL
        val events = session.scrollEvents ?: return SessionQuality.UNKNOWN

        val minutes = session.durationMs / 60_000.0
        val rate = if (minutes > 0) events / minutes else 0.0
        val burst = session.longestScrollBurstMs ?: 0L

        // Both a high rate and one long unbroken stretch. A rate alone can be a quick
        // hunt through a list; a burst alone can be one flick down a long article.
        return if (rate >= SCROLL_RATE_THRESHOLD && burst >= 60_000L) {
            SessionQuality.SCROLLING
        } else {
            SessionQuality.ABSORBED
        }
    }
}
