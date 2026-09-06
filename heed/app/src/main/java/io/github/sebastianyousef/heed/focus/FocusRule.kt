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

/**
 * What kind of time an app represents to you.
 *
 * Deliberately your judgement rather than Heed's. Every app that tries to classify this
 * for you gets it wrong for someone — YouTube is a lecture hall for one person and a slot
 * machine for the next, and a category shipped in a list would be wrong about the app
 * that matters most. Heed only colours what you have told it.
 *
 * [NEUTRAL] is the default and stays uncoloured. A screen that shades everything says
 * nothing; the signal is in the two you actually named.
 */
enum class AppCategory { NEUTRAL, PRODUCTIVE, DISTRACTING }

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

    /**
     * Scroll events before the feed is broken by a deliberate pause. 0 turns it off.
     *
     * The lever between "nudge me after ten minutes" and "stop me at the first flick".
     * An infinite feed works by never presenting a seam — there is no post that is the
     * last one, so there is never a moment where continuing is a decision rather than a
     * reflex. This manufactures the seam: after this many scrolls the feed is covered,
     * a timer runs, and getting back in costs a deliberate tap on the far side of it.
     *
     * Unlike [scrollBudgetEvents] this does not remove you from anything, so it repeats:
     * pass the seam and the count starts again. A budget you can spend all of in one
     * sitting is a budget that only ever fires once.
     */
    val scrollBreakEvents: Int = 0,

    /**
     * How long the seam lasts, in seconds.
     *
     * Long enough to be a pause rather than a stutter, short enough not to be punishment.
     * The point is to hand the decision back to you, not to make continuing expensive —
     * a wall you have to fight is a rule you turn off by Friday.
     */
    val breakSeconds: Int = 20,

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

    /**
     * Drain the colour out of the screen while this app is in front.
     *
     * A softer lever than any of the limits above, and often a more effective one. It
     * takes nothing away and blocks nothing, so there is no moment to argue with — the
     * app simply stops being fun to look at.
     */
    val grayscale: Boolean = false,

    /**
     * Carve-outs the user has switched off, comma separated.
     *
     * Stored as the *disabled* set rather than the enabled one so that a carve-out added
     * in a later version is on by default for everybody — which is the safe direction,
     * since a new exception can only ever protect something that was previously blocked.
     */
    val disabledExceptions: String = "",

    /**
     * Leave this app out of the statistics entirely.
     *
     * For the apps that are technically foreground time and are not *your* time: a
     * launcher you pass through on the way to something else, a system dialog, the
     * intent resolver. Counting those does not make the total more accurate, it makes
     * it less — twenty seconds of launcher between two real sessions is not screen time
     * in any sense a person means it.
     *
     * Excluded in SQL rather than filtered afterwards, so the totals, the chart, the
     * per-day breakdown and the app list can never disagree about it.
     */
    val excludedFromStats: Boolean = false,

    /** Your judgement about this app, for colouring the statistics. */
    val category: AppCategory = AppCategory.NEUTRAL,
) {
    fun isExceptionEnabled(key: String): Boolean =
        key !in disabledExceptions.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun withException(key: String, enabled: Boolean): FocusRule {
        val current = disabledExceptions.split(',').map { it.trim() }
            .filter { it.isNotEmpty() }.toMutableSet()
        if (enabled) current.remove(key) else current.add(key)
        return copy(disabledExceptions = current.sorted().joinToString(","))
    }

    /**
     * Whether this rule is inert without the accessibility service.
     *
     * Time limits, launch counts, bedtime and grayscale all run on usage statistics and
     * keep working with screen access off. Everything to do with scrolling does not:
     * measuring it, budgeting it, breaking it, and telling one feed from another all need
     * the event stream. A rule in that state is not degraded, it is doing nothing at all,
     * and the app has an obligation to say so rather than keep showing it as set.
     */
    val needsScreenAccess: Boolean
        get() = mode != FocusMode.OFF ||
            dailyScrollSeconds > 0 ||
            scrollBreakEvents > 0

    /**
     * A rule that asks only for a grey screen, and for nothing to be taken away.
     *
     * Bedtime locks every app that has a rule, which is right for a rule that sets a
     * limit and wrong for one that just drains the colour — turning an app grey is not a
     * request to be shut out of it at eleven at night.
     */
    val onlyChangesColour: Boolean
        get() = mode == FocusMode.OFF &&
            dailyUsageSeconds <= 0 &&
            dailyLaunchLimit <= 0 &&
            dailyScrollSeconds <= 0 &&
            scrollBreakEvents <= 0
}

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
            // Apps Heed ships anchors for start in Precise, because for those it can tell
            // the feed from the rest of the app. Starting them Behavioural is what made
            // Snapchat throw you out of a conversation: scrolling a chat list and
            // scrolling Spotlight are the same event, and only the surface tells them
            // apart. Everything else stays Behavioural, which is all that is available.
            detection = if (KnownSurfaces.hasBlockAnchors(pkg)) {
                DetectionMode.PRECISE
            } else {
                DetectionMode.BEHAVIOURAL
            },
        )
    }
}
