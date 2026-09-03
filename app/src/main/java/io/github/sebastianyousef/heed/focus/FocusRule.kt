package io.github.sebastianyousef.heed.focus

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * How Heed decides you are in a feed.
 *
 * BEHAVIOURAL needs no screen access at all and works in any app, but it cannot tell
 * Snapchat's Discovery from Snapchat's chat list — both are scrolling. PRECISE matches the
 * screen against surfaces you have taught it, so it can block Discovery and leave your
 * friends' stories alone, at the cost of needing to look at the screen's structure.
 */
enum class DetectionMode { BEHAVIOURAL, PRECISE }

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

    /**
     * Times you may open the app per day. 0 means unlimited.
     *
     * Often a better lever than time. Twenty two-minute checks cost less clock than one
     * forty-minute sitting, and do far more damage to your attention.
     */
    val dailyLaunchLimit: Int = 0,

    val detection: DetectionMode = DetectionMode.BEHAVIOURAL,

    /** Set for apps Heed configured itself, so the UI can say where the rule came from. */
    val fromPreset: Boolean = false,
)

/**
 * Apps whose business model is the scroll.
 *
 * Heed seeds rules for these and nothing else. Creating a rule for every app you own
 * buries the four that matter, and the previous build proved it: a Block rule ended up on
 * an authenticator app because it happened to be near the top of an unsorted list, while
 * Snapchat — the actual target — had no rule at all and was silently allowed.
 *
 * Seeded as NUDGE rather than BLOCK. An app that starts by hard-blocking things you did
 * not ask it to block gets uninstalled the same day.
 */
object KnownScrollers {

    val packages: Map<String, String> = mapOf(
        "com.snapchat.android" to "Snapchat",
        "com.google.android.youtube" to "YouTube",
        "com.instagram.android" to "Instagram",
        "com.linkedin.android" to "LinkedIn",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.twitter.android" to "X",
        "com.x.android" to "X",
        "com.reddit.frontpage" to "Reddit",
        "com.facebook.katana" to "Facebook",
        "com.pinterest" to "Pinterest",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "tv.twitch.android.app" to "Twitch",
        "com.netflix.mediaclient" to "Netflix",
    )

    fun isKnown(pkg: String) = packages.containsKey(pkg)

    fun presetFor(pkg: String, fallbackLabel: String): FocusRule? {
        val label = packages[pkg] ?: return null
        return FocusRule(
            packageName = pkg,
            appLabel = label.ifBlank { fallbackLabel },
            mode = FocusMode.NUDGE,
            fromPreset = true,
        )
    }
}
