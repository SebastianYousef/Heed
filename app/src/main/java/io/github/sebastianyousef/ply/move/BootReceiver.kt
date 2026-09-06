package io.github.sebastianyousef.ply.move

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reads the step counter as soon as possible after a restart.
 *
 * `TYPE_STEP_COUNTER` resets to zero at boot, and the steps between the last read and the
 * shutdown are unrecoverable — nothing holds them once the device is off. That loss is
 * bounded by how recently the counter was read, which is the periodic worker's job; this
 * is the other half, and it is about the *next* reading rather than the lost one.
 *
 * Without a read here, the reset is not noticed until the next scheduled run, which under
 * doze can be hours. The reconciler would still get it right when it eventually ran — the
 * elapsed clock going backwards is not a signal that decays — but every step walked in
 * those hours would be attributed to the moment of that late read rather than to the
 * hours they happened in.
 *
 * Also listens for MY_PACKAGE_REPLACED, because an update restarts the process and cancels
 * nothing else worth restarting; the periodic work survives, and this makes the first
 * reading after an update prompt rather than up to fifteen minutes late.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                StepWorker.schedule(context)
                StepWorker.readNow(context)
            }
        }
    }
}
