package io.github.sebastianyousef.heed.capture

import java.util.concurrent.ConcurrentHashMap

/**
 * Spots notifications that are really a live display rather than an event.
 *
 * A step counter is the clearest case: it posts one notification and then rewrites it
 * every few steps, all day. Android re-fires onNotificationPosted on every one of those
 * rewrites, so a naive listener sees thousands of "new" notifications a day from a single
 * thing the user thinks of as a status line. Download progress, navigation, media timers,
 * sync indicators and workout trackers all behave the same way.
 *
 * The flag checks in [NotificationMapper] catch most of these, because a live display
 * usually rides on a foreground service. This exists for the ones that do not set any of
 * the obvious flags — detection by behaviour rather than by declaration. Once a channel
 * has shown the pattern it is remembered, so the cost is paid once.
 *
 * Detection is per (package, channel) rather than per notification key: keys change when
 * an app re-posts, but the channel is stable, and a channel that carries one live display
 * carries only live displays.
 */
class LiveUpdateDetector(
    /** Updates to a single key within [windowMs] before the channel is judged live. */
    private val threshold: Int = 5,
    private val windowMs: Long = 120_000L,
) {
    private class Window(var count: Int, val startedAt: Long)

    private val windows = ConcurrentHashMap<String, Window>()
    private val known = ConcurrentHashMap.newKeySet<String>()

    /** Restore previously detected channels so the pattern is not re-learned each boot. */
    fun seed(channels: Collection<Pair<String, String>>) {
        channels.forEach { (pkg, channel) -> known += channelKey(pkg, channel) }
    }

    fun isLive(pkg: String, channelId: String?): Boolean =
        known.contains(channelKey(pkg, channelId))

    /**
     * Records one posting. Returns true exactly once — on the update that tips this
     * channel over into being treated as a live display — so the caller can persist it.
     */
    fun record(sbnKey: String, pkg: String, channelId: String?, now: Long): Int? {
        val channel = channelKey(pkg, channelId)
        if (known.contains(channel)) return null

        val window = windows.compute(sbnKey) { _, existing ->
            if (existing == null || now - existing.startedAt > windowMs) Window(1, now)
            else existing.also { it.count++ }
        }!!

        if (window.count < threshold) {
            if (windows.size > 512) pruneStaleWindows(now)
            return null
        }

        windows.remove(sbnKey)
        return if (known.add(channel)) window.count else null
    }

    /** Manual override from the UI, when the user disagrees with the detection. */
    fun forget(pkg: String, channelId: String?) {
        known.remove(channelKey(pkg, channelId))
    }

    private fun pruneStaleWindows(now: Long) {
        windows.entries.removeAll { now - it.value.startedAt > windowMs }
    }

    private fun channelKey(pkg: String, channelId: String?) = "$pkg|${channelId ?: "-"}"
}
