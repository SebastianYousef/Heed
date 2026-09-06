package io.github.sebastianyousef.ply.train

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import io.github.sebastianyousef.ply.MainActivity
import io.github.sebastianyousef.ply.R

/**
 * The rest between sets, running in the shade rather than in a composable.
 *
 * A coroutine in the UI dies with the screen, and a rest timer that only runs while you are
 * looking at it is not a timer — the entire case for it is that you put the phone in your
 * pocket. So it is a foreground service, and its notification is the timer rather than a
 * badge saying one exists.
 *
 * ### Two clocks, on purpose
 *
 * The countdown on screen is drawn by Android's own chronometer, from a target time. That
 * costs nothing: the system renders it, so there is no per-second update, no wakeup, and no
 * work at all while it runs down.
 *
 * The alert at zero is a separate exact alarm. A foreground service keeps the *process*
 * alive but does not keep the CPU awake, so a `delay()` counting down through a doze can
 * fire late — and a rest timer that is forty seconds late is worse than none, because you
 * trusted it. `setExactAndAllowWhileIdle` is the only thing that actually promises the
 * moment.
 *
 * ### It keeps counting after zero
 *
 * Because the useful question thirty seconds later is not "did it finish" — you heard it —
 * but "how long have I actually been standing here". A timer that stops at zero and
 * disappears answers the question nobody has.
 */
class RestTimerService : Service() {

    private val alarms by lazy { getSystemService(AlarmManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    /** When the rest is due to end, on the elapsed clock, so a clock change cannot move it. */
    private var dueAtElapsed = 0L
    private var exerciseName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                seconds = intent.getIntExtra(EXTRA_SECONDS, 150),
                name = intent.getStringExtra(EXTRA_EXERCISE).orEmpty(),
            )
            ACTION_EXTEND -> extend(intent.getIntExtra(EXTRA_SECONDS, 30))
            ACTION_ELAPSED -> showElapsed()
            ACTION_STOP -> stop()
            else -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(seconds: Int, name: String) {
        exerciseName = name
        dueAtElapsed = SystemClock.elapsedRealtime() + seconds * 1_000L
        ensureChannels()
        startForeground(NOTIFICATION_ID, countdown())
        scheduleAlarm()
    }

    private fun extend(seconds: Int) {
        // From now rather than from the original target, so pressing +30 on a timer that
        // already ran out gives thirty more seconds rather than a target in the past.
        val base = maxOf(dueAtElapsed, SystemClock.elapsedRealtime())
        dueAtElapsed = base + seconds * 1_000L
        notifications.notify(NOTIFICATION_ID, countdown())
        scheduleAlarm()
    }

    private fun scheduleAlarm() {
        alarms.cancel(alarmIntent(this))
        alarms.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            dueAtElapsed,
            alarmIntent(this),
        )
    }

    /** Called by the alarm: make a noise once, then keep counting upwards. */
    private fun showElapsed() {
        vibrate()
        notifications.notify(NOTIFICATION_ID, elapsed())
    }

    private fun stop() {
        alarms.cancel(alarmIntent(this))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun countdown(): android.app.Notification =
        base(CHANNEL_RUNNING)
            .setContentTitle("Resting")
            .setContentText(exerciseName.ifBlank { "Next set" })
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(System.currentTimeMillis() + (dueAtElapsed - SystemClock.elapsedRealtime()))
            .setSilent(true)
            .addAction(0, "+30s", servicePending(ACTION_EXTEND, 30))
            .addAction(0, "Skip", servicePending(ACTION_STOP, 0))
            .build()

    private fun elapsed(): android.app.Notification =
        base(CHANNEL_DONE)
            .setContentTitle("Rest is up")
            .setContentText(exerciseName.ifBlank { "Next set" })
            .setUsesChronometer(true)
            .setChronometerCountDown(false)
            .setWhen(System.currentTimeMillis())
            .addAction(0, "+30s", servicePending(ACTION_EXTEND, 30))
            .addAction(0, "Done", servicePending(ACTION_STOP, 0))
            .build()

    private fun base(channel: String) = NotificationCompat.Builder(this, channel)
        .setSmallIcon(R.drawable.ic_ply)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )

    private fun servicePending(action: String, seconds: Int): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, RestTimerService::class.java)
                .setAction(action)
                .putExtra(EXTRA_SECONDS, seconds),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        // Two short pulses rather than one long one: distinguishable from every other
        // buzz on the phone without being louder than any of them, which matters when the
        // phone is in a pocket in a room full of noise.
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1))
    }

    /**
     * Two channels, because the two states are not the same interruption.
     *
     * A running timer must never make a sound — it would go off every time it was updated.
     * The one at zero must, and it is the only notification this app ever raises.
     */
    private fun ensureChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Rest running", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Rest finished", NotificationManager.IMPORTANCE_HIGH)
                .apply { setShowBadge(false) }
        )
    }

    companion object {
        const val ACTION_START = "io.github.sebastianyousef.ply.REST_START"
        const val ACTION_EXTEND = "io.github.sebastianyousef.ply.REST_EXTEND"
        const val ACTION_ELAPSED = "io.github.sebastianyousef.ply.REST_ELAPSED"
        const val ACTION_STOP = "io.github.sebastianyousef.ply.REST_STOP"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_EXERCISE = "exercise"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_RUNNING = "rest_running"
        private const val CHANNEL_DONE = "rest_done"

        fun start(context: Context, seconds: Int, exercise: String) {
            if (seconds <= 0) return
            context.startForegroundService(
                Intent(context, RestTimerService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SECONDS, seconds)
                    .putExtra(EXTRA_EXERCISE, exercise)
            )
        }

        internal fun alarmIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, RestTimerReceiver::class.java).setAction(ACTION_ELAPSED),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/**
 * Wakes the service when the rest is up.
 *
 * A receiver rather than the alarm targeting the service directly, because an alarm that
 * starts a foreground service is subject to background-start restrictions the moment the
 * app is not visible — which is exactly when a rest timer matters.
 */
class RestTimerReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startForegroundService(
            Intent(context, RestTimerService::class.java).setAction(RestTimerService.ACTION_ELAPSED)
        )
    }
}
