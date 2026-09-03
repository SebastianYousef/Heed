package se.kth.notiapp.capture

import android.app.PendingIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.kth.notiapp.data.NotificationRecord
import se.kth.notiapp.score.ScoreResult

/**
 * A short delay line for notifications that have not alerted the user.
 *
 * This is only sound when the source app is already silenced — otherwise the phone has
 * buzzed and holding just delays our cleanup. Given a silent source, though, the wait is
 * free and buys three things:
 *
 *  1. Burst collapsing. Five messages from one group chat in two seconds is one
 *     interruption, not five.
 *  2. Transient filtering. Plenty of apps post a notification and immediately update or
 *     retract it; holding means we never act on a version that no longer exists.
 *  3. Headroom. Anything more expensive than the linear model — an LLM pass, say — needs
 *     somewhere to run that is not the delivery path.
 *
 * The window is drained as a batch: the first arrival starts the clock, everything that
 * lands before it expires goes out together.
 */
class HoldBuffer(
    private val scope: CoroutineScope,
    private val onFlush: suspend (List<Held>) -> Unit,
) {
    data class Held(
        val record: NotificationRecord,
        val score: ScoreResult,
        /**
         * Kept so a re-raised alert can open the app the notification came from.
         * PendingIntents cannot be persisted, which is why this lives in memory and dies
         * with the window.
         */
        val contentIntent: PendingIntent?,
    )

    private val pending = LinkedHashMap<String, Held>()
    private var timer: Job? = null

    @Synchronized
    fun submit(held: Held, holdMs: Long) {
        pending[held.record.sbnKey] = held
        if (timer == null) {
            timer = scope.launch {
                delay(holdMs)
                val batch = drain()
                if (batch.isNotEmpty()) onFlush(batch)
            }
        }
    }

    /** The notification went away on its own — drop it rather than acting on a ghost. */
    @Synchronized
    fun withdraw(sbnKey: String) {
        pending.remove(sbnKey)
    }

    @Synchronized
    fun flushNow() {
        timer?.cancel()
        timer = null
        val batch = pending.values.toList()
        pending.clear()
        if (batch.isNotEmpty()) scope.launch { onFlush(batch) }
    }

    @Synchronized
    private fun drain(): List<Held> {
        val batch = pending.values.toList()
        pending.clear()
        timer = null
        return batch
    }
}
