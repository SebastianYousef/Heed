package io.github.sebastianyousef.ply.move

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads the hardware step counter, once, and lets go.
 *
 * Deliberately not a listener that stays registered. `TYPE_STEP_COUNTER` is *cumulative
 * since boot*, which means its whole value can be recovered at any moment — so there is no
 * reason to hold a registration open, keep a process alive, or show a permanent
 * notification to be told about steps that the sensor is counting for free in hardware
 * whether anyone is listening or not.
 *
 * That is the entire battery argument for this app's step counting. The sensor hub counts
 * with the main CPU asleep; a periodic read wakes nothing that was not going to wake
 * anyway; and the only thing the app pays for is one sensor callback every fifteen minutes.
 * A registered listener with `maxReportLatencyUs` would be cheap too, but it needs a
 * process to deliver into, and keeping one alive costs more than the reading does.
 *
 * The cost of this choice is resolution, and it is stated rather than hidden: steps are
 * attributed to the hour they were *read* in, not the hour they were walked in, so a gap
 * between reads smears across an hour boundary. Day totals stay exact as long as a read
 * lands near midnight. See docs/movement.md.
 */
object StepSensor {

    fun available(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)
            ?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One reading, or null if the sensor is missing, forbidden, or does not answer.
     *
     * The timeout is not defensive padding: a step counter that has never been read since
     * boot legitimately has nothing to report until the next step is taken, and a caller
     * that waited forever for that would be a background job that never finishes.
     */
    suspend fun read(context: Context): StepReading? {
        if (!permitted(context)) return null
        val manager = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        return withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (continuation.isActive) {
                            continuation.resume(
                                StepReading(
                                    counter = event.values.firstOrNull()?.toLong() ?: 0L,
                                    elapsedNanos = event.timestamp,
                                    atMillis = wallClockOf(event.timestamp),
                                )
                            )
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                continuation.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        }
    }

    /**
     * Turns a sensor timestamp into a wall-clock moment.
     *
     * `SensorEvent.timestamp` is nanoseconds on the elapsed-realtime clock, which is what
     * makes it useful for detecting a reboot and useless for deciding which day something
     * belongs to. The offset between the two clocks is computed per reading rather than
     * cached, so a clock correction affects only the reading it happened during.
     */
    private fun wallClockOf(elapsedNanos: Long): Long {
        val offset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return offset + elapsedNanos / 1_000_000L
    }

    private const val READ_TIMEOUT_MS = 4_000L
}
