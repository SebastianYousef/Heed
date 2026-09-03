package io.github.sebastianyousef.heed.capture

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import io.github.sebastianyousef.heed.data.HeedRepository
import io.github.sebastianyousef.heed.notify.Notifier

/**
 * The capture path.
 *
 * By the time [onNotificationPosted] fires the notification has already been delivered,
 * so this service can only be clean when the source app has been silenced first — see
 * the onboarding flow. Otherwise it degrades to cancelling after the fact, which the
 * user perceives as a brief flash.
 *
 * There is no way around this from a third-party app. NotificationAssistantService is
 * the only API that runs before display, and it is @SystemApi — not on the public SDK
 * classpath, so an ordinary app cannot even compile against it. Silencing the source and
 * becoming the only thing allowed to make noise is the whole game.
 */
class HeedListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: CaptureEngine

    override fun onCreate() {
        super.onCreate()
        val repo = HeedRepository.get(this)
        repo.warmCaches(scope)
        Notifier(this).ensureChannels()
        engine = CaptureEngine(this, repo, scope) { key -> cancelNotification(key) }
        ListenerWatchdogWorker.schedule(this)
    }

    override fun onListenerConnected() {
        // Nothing is backfilled from activeNotifications on purpose: everything already
        // in the shade has, by definition, already interrupted the user, and importing
        // it would flood the inbox on first launch with a day of history we never judged.
        active = true
        HeedRepository.get(this).setListenerConnected(true)
        engine.start()
    }

    override fun onListenerDisconnected() {
        active = false
        HeedRepository.get(this).setListenerConnected(false)
        engine.flushPending()
        // Ask for the binding back. Without this the app goes quiet permanently and
        // gives the user no sign that it has stopped working.
        ListenerWatchdogWorker.requestRebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        engine.onPosted(sbn, importanceOf(rankingMap, sbn.key))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        engine.onRemoved(sbn, reason)
    }

    override fun onDestroy() {
        engine.flushPending()
        scope.cancel()
        super.onDestroy()
    }

    private fun importanceOf(rankingMap: RankingMap?, key: String): Int {
        val ranking = Ranking()
        return if (rankingMap?.getRanking(key, ranking) == true) ranking.importance else 3
    }

    companion object {
        @Volatile var active = false
            private set

        /** Whether the user has granted notification access in Settings. */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val me = ComponentName(context, HeedListenerService::class.java)
            return flat.split(":").any {
                ComponentName.unflattenFromString(it)?.packageName == me.packageName
            }
        }
    }
}
