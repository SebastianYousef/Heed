package io.github.sebastianyousef.heed.focus

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One deliberate stretch of not being on your phone.
 *
 * Kept as its own record rather than inferred from [io.github.sebastianyousef.heed.usage.SessionRecord]
 * because it is a different kind of fact. A usage session is something Heed observed; a
 * focus session is something you decided, and the interesting question afterwards is
 * whether you finished it — which only the thing that started it can answer.
 */
@Entity(tableName = "focus_sessions", indices = [Index("startedAt")])
data class FocusSessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Study, Work, Creative — or whatever the user typed. */
    val label: String,

    val startedAt: Long,

    /** How long it was meant to last. 0 for an open-ended stopwatch. */
    val plannedMs: Long,

    /** Null while it is still running. */
    val endedAt: Long? = null,

    /** True when the user ended it rather than the clock. */
    val endedEarly: Boolean = false,

    /** How many times an app was turned away during it — the only interesting statistic. */
    val blocks: Int = 0,
)

/**
 * Whether a focus session is running, and what it stops.
 *
 * Written as a pure function of its state for the same reason [ScrollDecision] is: it is
 * consulted from a poller on a plain thread and from an accessibility callback, and a
 * decision that has to reach the database to answer is a decision that gets cached
 * badly and then drifts. Everything here is arithmetic on a timestamp.
 *
 * ### What it will not block, ever
 *
 * Three exemptions, and none of them are preferences.
 *
 * [CriticalApps] already refuses to block authenticators, diallers, alarms, password
 * managers and settings, and a focus session is not a reason to change that — being
 * locked out of a login code at the wrong moment is worse than any amount of focus.
 *
 * The **launcher and Heed itself** are exempt because blocking either is a trap rather
 * than a rule. Blocking bounces you to the home screen; if the home screen is blocked
 * there is nowhere to bounce to, and if Heed is blocked you cannot reach the button that
 * ends the session. Those are passed in by the caller rather than named here, because
 * which app is your launcher is a fact about the device.
 */
object FocusSession {

    /** Offered as starting points. Anything typed is equally valid. */
    val TYPES = listOf("Study", "Work", "Creative", "Reading")

    /**
     * How long ending a session early takes.
     *
     * The asymmetry is the entire mechanism, and it is the same one strict mode uses:
     * starting is instant, stopping waits. The person who set the session and the person
     * who wants out of it ninety seconds into a boring paragraph are not the same person,
     * and only one of them was thinking about the afternoon.
     *
     * Ninety seconds rather than never. A session you genuinely cannot leave is a promise
     * Android will not keep anyway — force-stopping Heed ends it instantly — so claiming
     * otherwise would be a lock that only works on people who believe it.
     */
    const val END_DELAY_SECONDS = 90

    data class State(
        val label: String,
        val startedAt: Long,
        /** 0 for a stopwatch. */
        val plannedMs: Long,
        val allowed: Set<String>,
        /** When the user asked to stop, or 0. */
        val endRequestedAt: Long = 0L,
        val sessionId: Long = 0L,
    ) {
        val endsAt: Long? get() = if (plannedMs > 0) startedAt + plannedMs else null

        fun elapsedMs(now: Long): Long = (now - startedAt).coerceAtLeast(0L)

        /** Null for a stopwatch, which has nothing to count down. */
        fun remainingMs(now: Long): Long? = endsAt?.let { (it - now).coerceAtLeast(0L) }

        fun expired(now: Long): Boolean = endsAt?.let { now >= it } ?: false

        /** When the early exit unlocks, or 0 if it has not been asked for. */
        val releaseAt: Long
            get() = if (endRequestedAt > 0) endRequestedAt + END_DELAY_SECONDS * 1000L else 0L

        fun canEndNow(now: Long): Boolean = endRequestedAt > 0 && now >= releaseAt

        fun secondsUntilRelease(now: Long): Int =
            if (endRequestedAt <= 0) END_DELAY_SECONDS
            else ((releaseAt - now).coerceAtLeast(0L) / 1000L).toInt()
    }

    /**
     * Whether this app should be turned away right now.
     *
     * An allowlist rather than a blocklist, because the useful version of this question is
     * "what do I need for the next hour" and it is a short list you can actually hold in
     * your head. A blocklist has to anticipate every app you might reach for, which means
     * it is always one app out of date — and the one it is missing is the one you open.
     */
    fun blocks(state: State?, pkg: String, exempt: Set<String>, now: Long): Boolean {
        state ?: return false
        if (state.expired(now)) return false
        if (pkg in exempt) return false
        if (CriticalApps.isProtected(pkg)) return false
        return pkg !in state.allowed
    }
}
