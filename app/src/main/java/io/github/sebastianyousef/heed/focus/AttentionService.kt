package io.github.sebastianyousef.heed.focus

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.sebastianyousef.heed.MainActivity
import io.github.sebastianyousef.heed.R
import io.github.sebastianyousef.heed.data.HeedRepository
import io.github.sebastianyousef.heed.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Enforcement that does not need an accessibility service.
 *
 * This exists for one concrete reason. Banking apps — Nordea, BankID, Swish and most of
 * their peers — refuse to start while any accessibility service is enabled, because
 * that permission is the standard vector for overlay-and-tap account takeover. Their
 * check cannot be argued with and should not be evaded. It means the version of Heed that
 * put every limit behind the accessibility service was really asking people to choose
 * between their bank and their screen-time rules, and that is not a choice anybody makes
 * twice.
 *
 * So the split is this. Everything that can be done from usage statistics is done here,
 * with no accessibility involved at all: which app is in front, how long it has been
 * there, how many times it was opened today, bedtime, and grayscale. The accessibility
 * service is now only needed for the two things that genuinely require reading the
 * screen's structure — measuring scrolling, and telling Snapchat's Spotlight from
 * Snapchat's chats.
 *
 * A poll rather than a callback because Android offers no foreground-app broadcast to a
 * normal app. One second is the resolution: fast enough that a blocked app closes before
 * you have read anything, slow enough to be invisible on a battery graph next to the
 * screen itself.
 */
class AttentionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastPackage: String? = null
    private var lastBlockAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
        running = true
        scope.launch { loop() }
    }

    private suspend fun loop() {
        val repo = HeedRepository.get(this)
        val usage = getSystemService(UsageStatsManager::class.java)
        while (scope.isActive) {
            runCatching { tick(repo, usage) }
            delay(POLL_MS)
        }
    }

    private suspend fun tick(repo: HeedRepository, usage: UsageStatsManager?) {
        val pkg = foregroundPackage(usage) ?: return
        val settings = repo.settings.first()
        val bedtime = repo.isBedtimeNow()

        // Bedtime grayscale is about the whole phone, so it is checked before anything
        // app-specific and holds even on the launcher.
        val rule = repo.dao.focusRuleFor(pkg)
        val wantGrey = (settings.grayscaleAtBedtime && bedtime) ||
            (rule?.grayscale == true && !CriticalApps.isProtected(pkg))
        Grayscale.apply(this, wantGrey)

        if (pkg == lastPackage) return
        lastPackage = pkg

        val now = System.currentTimeMillis()
        if (now - lastBlockAt < BLOCK_COOLDOWN_MS) return
        if (ScrollWatcherService.isEnabled(this)) return  // that service already handles it

        val verdict = FocusEnforcer.from(repo.dao) { bedtime }.onAppOpened(pkg)
        if (verdict is FocusEnforcer.Verdict.Block) {
            if (!Surfacer.canDrawOverlays(this)) return
            lastBlockAt = now
            FocusOverlay.block(Surfacer.FromContext(this), verdict.headline, verdict.detail)
        }
    }

    /**
     * The app in front right now.
     *
     * Read from the event stream rather than from `queryUsageStats`, whose per-app
     * "last time used" buckets lag by minutes and would let a blocked app stay open long
     * enough to defeat the point.
     */
    private fun foregroundPackage(usage: UsageStatsManager?): String? {
        usage ?: return null
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                latest = event.packageName
            }
        }
        return latest
    }

    private fun startForeground() {
        Notifier(this).ensureChannels()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, Notifier.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_heed)
            .setContentTitle("Heed is keeping to your limits")
            .setContentText("App limits, bedtime and grayscale. No screen reading.")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        Grayscale.release(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set

        private const val NOTIFICATION_ID = 4_100
        private const val POLL_MS = 1_000L

        /** Wide enough that a poll never falls into a gap between events. */
        private const val LOOKBACK_MS = 10_000L
        private const val BLOCK_COOLDOWN_MS = 15_000L

        /**
         * Start it only when it has something to do. A permanent notification for an app
         * with no limits set is noise of exactly the kind this project exists to remove.
         */
        fun syncWith(context: Context, needed: Boolean) {
            val intent = Intent(context, AttentionService::class.java)
            if (needed) {
                runCatching { context.startForegroundService(intent) }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }
}
