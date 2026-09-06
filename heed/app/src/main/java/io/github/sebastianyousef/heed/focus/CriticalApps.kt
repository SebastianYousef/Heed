package io.github.sebastianyousef.heed.focus

/**
 * Apps Heed will never block, whatever rule you set on them.
 *
 * A focus app that stands between you and a one-time code, an alarm, or a phone call has
 * stopped being useful and started being a hazard — and the rule that does it is almost
 * never one you meant to set. This exists because a stray Block rule landed on an
 * authenticator during testing: two taps in a list, and the app that guards every login
 * would have been sending the user to the home screen.
 *
 * The same reasoning as the never-filter rules on the notification side. Some categories
 * are simply not the user's to get wrong at 3am.
 */
object CriticalApps {

    private val packages = setOf(
        "com.azure.authenticator",
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
        "org.shadowice.flocke.andotp",
        "com.beemdevelopment.aegis",
        "com.bitwarden.authenticator",
        "proton.android.pass",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.settings",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.android.emergency",
    )

    /**
     * Matched loosely as well, because the long tail of authenticator, banking and dialler
     * apps cannot be enumerated and getting it wrong is much worse than being cautious.
     */
    private val keywords = listOf(
        "authenticator", "otp", "2fa", "twofactor", "bankid", "dialer", "dialler",
        "emergency", "deskclock", "alarm", "password", "vault", "keepass",
    )

    /**
     * Memoised because this is now asked on the scrolling hot path, where the answer is
     * consulted tens of times a second and cannot change: a package name is fixed for the
     * life of an install, so the keyword scan is worth doing exactly once per app.
     */
    private val protectedCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isProtected(packageName: String): Boolean = protectedCache.getOrPut(packageName) {
        if (packageName in packages) return@getOrPut true
        val lower = packageName.lowercase()
        keywords.any { lower.contains(it) }
    }

}
