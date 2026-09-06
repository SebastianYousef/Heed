package io.github.sebastianyousef.heed.focus

/**
 * Which app is in front, published by whoever already knows.
 *
 * There is no cheap way for an ordinary app to ask Android what is in the foreground.
 * `UsageStatsManager.queryEvents` is the only route, and it is not free: it crosses a
 * binder, allocates an event stream and iterates it, and doing that once a second was
 * most of what Heed cost while sitting in the background doing nothing.
 *
 * But when the accessibility service is running it is *told* — a window state change is
 * delivered the moment it happens, for nothing. So the service publishes here and the
 * poller reads it instead of asking the system, which removes the poll entirely in the
 * configuration most people end up with. The poll remains for when screen access is off,
 * where there is genuinely no other way to know.
 */
object ForegroundApp {

    @Volatile private var value: String? = null
    @Volatile private var at = 0L

    fun publish(packageName: String) {
        value = packageName
        at = System.currentTimeMillis()
    }

    fun clear() {
        value = null
    }

    /**
     * The current app, or null if nothing has published recently enough to trust.
     *
     * Staleness matters: the accessibility service can be killed and rebound, and a
     * package name from ten minutes ago is worse than no answer, because it would let a
     * limit be enforced against an app the user has long since left.
     */
    fun current(maxAgeMs: Long = 5 * 60_000L): String? =
        value?.takeIf { System.currentTimeMillis() - at <= maxAgeMs }
}
