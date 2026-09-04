package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.data.Settings
import io.github.sebastianyousef.heed.focus.CriticalApps
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which apps make Heed offer to step out of the way — and, more importantly, which do not.
 *
 * The first version of this list matched a crypto wallet on the word "wallet", switched
 * screen access off unasked, and because Android will not let an app re-enable its own
 * accessibility service, every block stopped working permanently with nothing on screen
 * to explain it. A false positive here is not a small cost: it silently disables the
 * feature the app exists for.
 */
class StepAsideTest {

    @Test
    fun `the banks that actually refuse accessibility are recognised`() {
        listOf(
            "se.nordea.mobilebank",
            "com.bankid.bus",
            "se.bankgirot.swish",
            "se.avanzabank.androidapplikation",
            "com.revolut.revolut",
        ).forEach {
            assertTrue("$it should be offered a step-aside", CriticalApps.isSecuritySensitive(it))
        }
    }

    @Test
    fun `a crypto wallet is not a bank`() {
        // The exact package that broke it: opened once, and blocking never worked again.
        assertFalse(CriticalApps.isSecuritySensitive("com.cakewallet.cake_wallet"))
        assertFalse(CriticalApps.isSecuritySensitive("com.google.android.apps.walletnfcrel"))
    }

    @Test
    fun `ordinary apps are untouched`() {
        listOf(
            "com.snapchat.android",
            "com.linkedin.android",
            "app.vanadium.browser",
            "com.kunzisoft.keepass.free",
            "io.github.sebastianyousef.heed",
        ).forEach {
            assertFalse("$it must not trigger a step-aside", CriticalApps.isSecuritySensitive(it))
        }
    }

    @Test
    fun `automatic pausing is off by default`() {
        // An irreversible action taken on a guess needs consent, not a default.
        assertFalse(Settings().pauseForBanking)
    }

    @Test
    fun `a password manager is protected but not a reason to drop screen access`() {
        assertTrue(CriticalApps.isProtected("com.kunzisoft.keepass.free"))
        assertFalse(CriticalApps.isSecuritySensitive("com.kunzisoft.keepass.free"))
    }
}
