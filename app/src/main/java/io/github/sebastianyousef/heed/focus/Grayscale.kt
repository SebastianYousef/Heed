package io.github.sebastianyousef.heed.focus

import android.content.Context
import android.provider.Settings

/**
 * Drains the colour out of the screen.
 *
 * Colour is the cheapest thing an app buys your attention with. Red dots, thumbnails
 * chosen by a model that knows which shade of orange keeps you watching — all of it
 * stops working in monochrome, and unlike a block screen there is nothing to fight. You
 * can still use the phone. It is simply boring, which is the point.
 *
 * Android has exactly one way to do this without root: the display daltonizer, a
 * colour-blindness correction filter that in mode 0 renders the whole framebuffer
 * greyscale. It is a secure setting, so writing it needs WRITE_SECURE_SETTINGS — a
 * permission the system will not hand to an app through a prompt. It has to be granted
 * once over adb (see [ADB_COMMAND]).
 *
 * That is a real cost, and it is worth being honest about why it is worth paying: the
 * alternative is an overlay, and an overlay cannot desaturate what is underneath it. It
 * can only tint. Every "grayscale" app that does not ask for this permission is drawing a
 * grey film over your screen, which dims it without removing a single colour cue. This
 * either works properly or it stays off.
 */
object Grayscale {

    const val ADB_COMMAND =
        "adb shell pm grant io.github.sebastianyousef.heed android.permission.WRITE_SECURE_SETTINGS"

    /** Daltonizer mode 0 is MONOCHROMACY. The others shift hues; only this one removes them. */
    private const val MONOCHROMACY = 0

    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"

    /**
     * Whether Heed can actually do this. Checked by trying a write rather than by
     * inspecting the permission, because a granted-but-restricted permission looks
     * identical to a granted one until you use it.
     */
    fun isAvailable(context: Context): Boolean = runCatching {
        val current = Settings.Secure.getInt(context.contentResolver, ENABLED, 0)
        Settings.Secure.putInt(context.contentResolver, ENABLED, current)
        true
    }.getOrDefault(false)

    fun isOn(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, ENABLED, 0) == 1 &&
            Settings.Secure.getInt(context.contentResolver, MODE, -1) == MONOCHROMACY
    }.getOrDefault(false)

    /**
     * Turn the filter on or off.
     *
     * Returns false if the permission is missing, so callers can surface the one-time
     * setup rather than silently doing nothing — the failure mode that makes a feature
     * look broken instead of unconfigured.
     */
    fun set(context: Context, on: Boolean): Boolean = runCatching {
        if (on) Settings.Secure.putInt(context.contentResolver, MODE, MONOCHROMACY)
        Settings.Secure.putInt(context.contentResolver, ENABLED, if (on) 1 else 0)
        true
    }.getOrDefault(false)

    /**
     * Apply a desired state, but only ever undo what we did ourselves.
     *
     * Someone who keeps their phone permanently grey for their own reasons should not
     * find it in colour because they opened LinkedIn and then closed it. Heed tracks
     * whether the filter is on because of a rule of its own, and leaves it alone
     * otherwise.
     */
    @Volatile private var ownedByHeed = false

    @Synchronized
    fun apply(context: Context, wanted: Boolean) {
        val on = isOn(context)
        when {
            wanted && !on -> if (set(context, true)) ownedByHeed = true
            !wanted && on && ownedByHeed -> if (set(context, false)) ownedByHeed = false
            wanted && on -> Unit  // already grey, possibly not ours; leave the flag alone
            else -> Unit
        }
    }

    /** Release the screen back to colour on the way out, if we were the ones holding it. */
    @Synchronized
    fun release(context: Context) {
        if (ownedByHeed && set(context, false)) ownedByHeed = false
    }
}
