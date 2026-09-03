package io.github.sebastianyousef.heed.capture

import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.sebastianyousef.heed.data.CapturePath
import io.github.sebastianyousef.heed.data.Decision
import io.github.sebastianyousef.heed.data.Feedback
import io.github.sebastianyousef.heed.data.HeedRepository
import io.github.sebastianyousef.heed.notify.Notifier
import io.github.sebastianyousef.heed.score.ScoreResult

/**
 * Decision logic for the capture service, kept out of the service class so it can be
 * exercised without a bound listener.
 */
class CaptureEngine(
    private val context: Context,
    private val repo: HeedRepository,
    private val scope: CoroutineScope,
    private val shade: Shade,
) {
    /** Lets the engine pull something out of the notification shade. */
    fun interface Shade {
        fun cancel(sbnKey: String)
    }

    private val notifier = Notifier(context)
    private val buffer = HoldBuffer(scope) { batch -> flush(batch) }
    private val liveDetector = LiveUpdateDetector()

    /** Keys we have already raised an alert for, so an update does not re-play the sound. */
    private val alreadyRaised = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    fun start() {
        scope.launch {
            liveDetector.seed(repo.knownLiveChannels())
            // Anything left mid-flight when the process last died never reached the user.
            repo.resolveOrphanedHolds()
        }
    }

    // ---------------------------------------------------------------- listener path

    /**
     * Called after the notification has already been posted — and therefore after any
     * sound, vibration or heads-up banner has already fired. The best we can do here is
     * be quick, unless the source app is silenced, in which case nothing has actually
     * reached the user and we can take our time.
     */
    fun onPosted(sbn: StatusBarNotification, systemImportance: Int) {
        if (NotificationMapper.isIgnorable(context, sbn)) return

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sbn.notification.channelId
        } else null

        // Known live display — a step counter, a progress bar. Leave it alone entirely.
        if (liveDetector.isLive(sbn.packageName, channelId)) return

        val record = NotificationMapper.toRecord(context, sbn, systemImportance)
        val contentIntent = sbn.notification.contentIntent

        scope.launch {
            // Behavioural detection, for live displays that set none of the usual flags.
            liveDetector.record(sbn.key, record.packageName, channelId, record.postedAt)
                ?.let { burst ->
                    repo.markLiveChannel(record, burst)
                    return@launch
                }

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
                val outcome = repo.persistOrUpdate(stored)
                if (silenced) {
                    // The source is muted, so it arrived without a sound. Raise it ourselves.
                    raise(HoldBuffer.Held(stored, result, outcome.id, outcome.wasUpdate, contentIntent))
                    shade.cancel(stored.sbnKey)
                }
                return@launch
            }

            val hold = repo.currentSettings().holdWindowMs
            if (silenced && hold > 0) {
                // Persist as HELD *before* waiting, so a process death inside the window
                // leaves a record rather than a silently dropped notification.
                val outcome = repo.persistOrUpdate(
                    record.copy(
                        score = result.score,
                        scoreReason = result.reason,
                        decision = Decision.HELD,
                        capturePath = CapturePath.QUIET_SOURCE,
                    )
                )
                buffer.submit(
                    HoldBuffer.Held(record, result, outcome.id, outcome.wasUpdate, contentIntent),
                    hold,
                )
            } else {
                // Nothing to gain from waiting: it has already made noise. Act now.
                val outcome = repo.persistOrUpdate(
                    record.copy(
                        score = result.score,
                        scoreReason = result.reason,
                        decision = result.decision,
                        capturePath = CapturePath.CANCEL_AFTER,
                    )
                )
                act(
                    HoldBuffer.Held(record, result, outcome.id, outcome.wasUpdate, contentIntent),
                    result,
                    CapturePath.CANCEL_AFTER,
                )
            }
        }
    }

    // ---------------------------------------------------------------------- removal

    /**
     * Where the learning signal comes from. A tap means the notification was worth
     * showing; a swipe means it probably was not. Our own cancels are excluded, since
     * training on them would just teach the model to agree with itself.
     */
    fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        buffer.withdraw(sbn.key)
        alreadyRaised.remove(sbn.key)
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
                items.forEach { act(it, it.score, CapturePath.QUIET_SOURCE) }
                continue
            }
            val leader = items.maxByOrNull { it.score.score }
            for (item in items) {
                if (item === leader && item.score.decision == Decision.ALERTED) {
                    act(item, item.score, CapturePath.QUIET_SOURCE)
                } else {
                    val collapsed = item.score.copy(
                        decision = Decision.SUPPRESSED,
                        reason = item.score.reason +
                            " · collapsed into a burst of ${items.size} from ${item.record.appLabel}",
                    )
                    act(item, collapsed, CapturePath.QUIET_SOURCE)
                }
            }
        }
    }

    private suspend fun act(held: HoldBuffer.Held, result: ScoreResult, path: CapturePath) {
        repo.resolveHeld(held.recordId, result, path)

        when (result.decision) {
            Decision.ALERTED -> {
                if (path == CapturePath.QUIET_SOURCE) {
                    // Arrived silently because the source is muted — we make the noise.
                    raise(held)
                    shade.cancel(held.record.sbnKey)
                }
                // On CANCEL_AFTER the original already alerted and is in the shade. Leave it.
            }
            Decision.SUPPRESSED -> shade.cancel(held.record.sbnKey)
            Decision.HELD -> Unit
        }
    }

    /**
     * An updated notification should refresh the alert already on screen, not ring a
     * second time — the whole point is to interrupt once per thing, not once per edit.
     */
    private fun raise(held: HoldBuffer.Held) {
        val firstTime = alreadyRaised.add(held.record.sbnKey)
        notifier.raise(held, held.recordId, alertAgain = firstTime)
    }
}
