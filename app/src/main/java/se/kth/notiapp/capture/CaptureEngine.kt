package se.kth.notiapp.capture

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.kth.notiapp.data.CapturePath
import se.kth.notiapp.data.Decision
import se.kth.notiapp.data.Feedback
import se.kth.notiapp.data.NotiRepository
import se.kth.notiapp.notify.Notifier
import se.kth.notiapp.score.ScoreResult

/**
 * Shared decision logic for both capture services.
 *
 * It cannot be a base class: NotificationAssistantService already extends
 * NotificationListenerService, so the assistant and the plain listener sit on different
 * branches of the hierarchy and have to compose this instead of inheriting it.
 */
class CaptureEngine(
    private val context: Context,
    private val repo: NotiRepository,
    private val scope: CoroutineScope,
    private val shade: Shade,
) {
    /** Lets the engine pull something out of the notification shade. */
    fun interface Shade {
        fun cancel(sbnKey: String)
    }

    private val notifier = Notifier(context)
    private val buffer = HoldBuffer(scope) { batch -> flush(batch) }

    // ---------------------------------------------------------------- listener path

    /**
     * Called after the notification has already been posted — and therefore after any
     * sound, vibration or heads-up banner has already fired. The best we can do here is
     * be quick, unless the source app is silenced, in which case nothing has actually
     * reached the user and we can take our time.
     */
    fun onPosted(sbn: StatusBarNotification, systemImportance: Int) {
        if (NotificationMapper.isIgnorable(context, sbn)) return
        val record = NotificationMapper.toRecord(context, sbn, systemImportance)
        val contentIntent = sbn.notification.contentIntent

        scope.launch {
            val result = repo.score(record)
            val silenced = repo.isSourceSilencedCached(record.packageName)

            // Calls, alarms and one-time codes never wait in a buffer.
            if (result.forced && result.decision == Decision.ALERTED) {
                val stored = record.copy(
                    score = result.score,
                    scoreReason = result.reason,
                    decision = Decision.ALERTED,
                    capturePath = if (silenced) CapturePath.QUIET_SOURCE else CapturePath.CANCEL_AFTER,
                )
                val id = repo.persist(stored)
                if (silenced) {
                    // The source is muted, so it arrived without a sound. Raise it ourselves.
                    notifier.raise(HoldBuffer.Held(stored, result, contentIntent), id)
                    shade.cancel(stored.sbnKey)
                }
                return@launch
            }

            val hold = repo.currentSettings().holdWindowMs
            if (silenced && hold > 0) {
                buffer.submit(HoldBuffer.Held(record, result, contentIntent), hold)
            } else {
                // Nothing to gain from waiting: it has already made noise. Act now.
                apply(HoldBuffer.Held(record, result, contentIntent), result, CapturePath.CANCEL_AFTER)
            }
        }
    }

    // --------------------------------------------------------------- assistant path

    /**
     * Called before the notification is shown. Must not suspend or touch disk — the
     * framework holds notification delivery while this runs. Persistence is deferred to
     * the background scope; only the verdict is computed inline.
     */
    fun onEnqueuedFast(sbn: StatusBarNotification, systemImportance: Int): ScoreResult? {
        if (NotificationMapper.isIgnorable(context, sbn)) return null
        val record = NotificationMapper.toRecord(context, sbn, systemImportance)
        val result = repo.scoreFast(record)

        scope.launch {
            repo.persist(
                record.copy(
                    score = result.score,
                    scoreReason = result.reason,
                    decision = result.decision,
                    capturePath = CapturePath.ASSISTANT,
                )
            )
        }
        return result
    }

    // ---------------------------------------------------------------------- removal

    /**
     * Where the learning signal comes from. A tap means the notification was worth
     * showing; a swipe means it probably was not. Our own cancels are excluded, since
     * training on them would just teach the model to agree with itself.
     */
    fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        buffer.withdraw(sbn.key)
        val feedback = when (reason) {
            NotificationListenerService.REASON_CLICK -> Feedback.CLICKED
            NotificationListenerService.REASON_CANCEL -> Feedback.DISMISSED
            else -> return // REASON_CANCEL_ALL, app-initiated, our own listener cancels
        }
        scope.launch { repo.recordFeedbackByKey(sbn.key, feedback) }
    }

    fun flushPending() = buffer.flushNow()

    // ------------------------------------------------------------------- decisioning

    private suspend fun flush(batch: List<HoldBuffer.Held>) {
        // Burst collapsing: a group chat firing eight messages into one window is one
        // interruption. Keep the strongest, file the rest.
        val byApp = batch.groupBy { it.record.packageName }
        for ((_, items) in byApp) {
            if (items.size <= 3) {
                items.forEach { apply(it, it.score, CapturePath.QUIET_SOURCE) }
                continue
            }
            val leader = items.maxByOrNull { it.score.score }
            for (item in items) {
                if (item === leader && item.score.decision == Decision.ALERTED) {
                    apply(item, item.score, CapturePath.QUIET_SOURCE)
                } else {
                    val collapsed = item.score.copy(
                        decision = Decision.SUPPRESSED,
                        reason = item.score.reason +
                            " · collapsed into a burst of ${items.size} from ${item.record.appLabel}",
                    )
                    apply(item, collapsed, CapturePath.QUIET_SOURCE)
                }
            }
        }
    }

    private suspend fun apply(held: HoldBuffer.Held, result: ScoreResult, path: CapturePath) {
        val record = held.record.copy(
            score = result.score,
            scoreReason = result.reason,
            decision = result.decision,
            capturePath = path,
        )
        val id = repo.persist(record)

        when (result.decision) {
            Decision.ALERTED -> {
                if (path == CapturePath.QUIET_SOURCE) {
                    // Arrived silently because the source is muted — we have to make the noise.
                    notifier.raise(held.copy(record = record), id)
                    shade.cancel(record.sbnKey)
                }
                // On CANCEL_AFTER the original already alerted and is in the shade. Leave it.
            }
            Decision.SUPPRESSED -> shade.cancel(record.sbnKey)
            Decision.HELD -> Unit
        }
    }
}
