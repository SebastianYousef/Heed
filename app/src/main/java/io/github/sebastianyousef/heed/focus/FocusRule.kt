package io.github.sebastianyousef.heed.focus

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class FocusMode {
    /** Measure only. */
    OFF,

    /** Let it run, then interrupt with a few seconds of friction. */
    NUDGE,

    /** Stop it the moment scrolling starts. No "continue" button. */
    BLOCK,
}

/**
 * What Heed is allowed to do about one app.
 *
 * The interesting field is [dailyScrollSeconds], which budgets *scrolling* rather than the
 * app. Every competitor limits time in an app, which forces a choice nobody wants: block
 * LinkedIn and lose your messages, or allow it and lose your evening. Budgeting the
 * scrolling separates the two — message all day, get five minutes of feed.
 *
 * Blocking a specific surface (Snapchat's Spotlight but not its chats) would need to read
 * the screen, which Heed deliberately cannot do. [scrollBudgetEvents] is the approximation:
 * a feed is continuous scrolling and a chat list is not, so a tight budget in BLOCK mode
 * stops the feed within a flick or two while leaving normal use alone.
 */
@Entity(tableName = "focus_rules")
data class FocusRule(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val mode: FocusMode = FocusMode.OFF,

    /** Scroll events tolerated before BLOCK fires. Small numbers mean "instantly". */
    val scrollBudgetEvents: Int = 4,

    /** Seconds of scrolling allowed per day. 0 means unlimited. */
    val dailyScrollSeconds: Int = 0,

    /** Seconds in the foreground allowed per day. 0 means unlimited. */
    val dailyUsageSeconds: Int = 0,
)
