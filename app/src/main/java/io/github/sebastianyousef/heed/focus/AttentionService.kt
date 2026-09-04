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
    private var bedtimeNow = false
    private var bedtimeCheckedAt = 0L
    private var greyApplied = false

    @Volatile private var screenOn = true

    /**
     * The screen's state, from the system rather than polled.
     *
     * Registering a receiver costs nothing while nothing happens, which is the opposite
     * of asking every second whether anything has.
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
            if (!screenOn) lastPackage = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
        running = true
        screenOn = getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
        registerReceiver(
            screenReceiver,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        scope.launch { loop() }
    }

    private suspend fun loop() {
        val repo = HeedRepository.get(this)
        repo.warmCaches(scope)
        val usage = getSystemService(UsageStatsManager::class.java)
        while (scope.isActive) {
            // Nothing can be opened while the screen is off, so there is nothing to
            // enforce. This is most of the day, and it used to be a UsageStatsManager
            // query, two DataStore reads and two secure-setting reads every second of it.
            if (screenOn) runCatching { tick(repo, usage) }
            delay(
                when {
                    !screenOn -> IDLE_POLL_MS
                    // A second and a half matters when something is actually going to
                    // happen the moment an app opens — a limit closing it, colour
                    // draining out of it. With nothing configured this service exists
                    // only as a backstop for banking apps the scroll watcher cannot see,
                    // and checking that eight times a minute is plenty.
                    needsPromptness(repo) -> POLL_MS
                    else -> LAZY_POLL_MS
                }
            )
        }
    }

    /** Whether anything configured needs the foreground app known quickly. */
    private fun needsPromptness(repo: HeedRepository): Boolean {
        val settings = repo.currentSettings()
        if (settings.bedtimeEnabled || settings.grayscaleAtBedtime) return true
        return repo.anyRuleNeedsForeground()
    }

    private suspend fun tick(repo: HeedRepository, usage: UsageStatsManager?) {
        // The accessibility service publishes the moment it sees a window change, which
        // is faster than any poll — but only for apps it is allowed to see, so the poll
        // remains the authority for noticing that you have *left* one.
        val pkg = ForegroundApp.current(PUSH_TRUST_MS) ?: foregroundPackage(usage) ?: return
        val settings = repo.currentSettings()

        // Bedtime is a comparison against the clock, so it can only change on the minute.
        val now = System.currentTimeMillis()
        if (now - bedtimeCheckedAt > BEDTIME_TTL_MS) {
            bedtimeCheckedAt = now
            bedtimeNow = repo.isBedtimeNow()
        }

        val rule = repo.cachedRuleFor(pkg)
        val wantGrey = (settings.grayscaleAtBedtime && bedtimeNow) ||
            (rule?.grayscale == true && !CriticalApps.isProtected(pkg))
        // Only touched when the answer changes, so the common case reads nothing at all.
        if (wantGrey != greyApplied) {
            Grayscale.apply(this, wantGrey)
            greyApplied = wantGrey
        }

        if (pkg == lastPackage) return
        lastPackage = pkg

        // A backstop only. The scroll watcher disables itself from its own callback,
        // which is both faster and the only path that cannot be defeated by a stale
        // instance reference. This stays quiet unless it really did switch something off.
        if (settings.pauseForBanking && CriticalApps.isSecuritySensitive(pkg) &&
            ScrollWatcherService.isEnabled(this)
        ) {
            if (ScrollWatcherService.pause()) Notifier(this).screenAccessPaused(pkg)
            return
        }

        if (now - lastBlockAt < BLOCK_COOLDOWN_MS) return
        if (ScrollWatcherService.isEnabled(this)) return  // that service already handles it

        // Only an app with a limit can be blocked on opening, and that is a hash lookup.
        if (rule == null || (rule.dailyUsageSeconds <= 0 && rule.dailyLaunchLimit <= 0 && !bedtimeNow)) return

        val verdict = FocusEnforcer.from(repo.dao) { bedtimeNow }.onAppOpened(pkg)
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
        runCatching { unregisterReceiver(screenReceiver) }
        Grayscale.release(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set

        private const val NOTIFICATION_ID = 4_100
        private const val POLL_MS = 1_500L

        /** With the screen off there is nothing to see; this only keeps the loop alive. */
        private const val IDLE_POLL_MS = 30_000L

        /** Nothing to enforce; this is only watching for a bank to step aside from. */
        private const val LAZY_POLL_MS = 8_000L

        /**
         * How recent a pushed foreground app has to be to be believed over a fresh poll.
         * Short, because the push only ever arrives for apps the service can see.
         */
        private const val PUSH_TRUST_MS = 2_000L

        /** Bedtime can only change on the minute, so asking more often is waste. */
        private const val BEDTIME_TTL_MS = 60_000L

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
