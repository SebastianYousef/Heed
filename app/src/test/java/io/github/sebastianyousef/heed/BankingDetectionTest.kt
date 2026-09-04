package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.CriticalApps
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which apps make Heed step out of the way.
 *
 * These are the apps actually on the user's phone that refuse to run while an
 * accessibility service is enabled. Getting this list wrong in one direction costs a
 * scroll measurement; in the other it costs somebody the ability to pay for their lunch,
 * so it errs wide on purpose.
 */
class BankingDetectionTest {

    @Test
    fun `the banks and id apps on this device are recognised`() {
        listOf(
            "se.nordea.mobilebank",
            "com.bankid.bus",
            "se.bankgirot.swish",
            "se.avanzabank.androidapplikation",
            "com.revolut.revolut",
        ).forEach {
            assertTrue("$it must trigger a pause", CriticalApps.isSecuritySensitive(it))
        }
    }

    @Test
    fun `ordinary apps do not trigger it`() {
        listOf(
            "com.snapchat.android",
            "com.linkedin.android",
            "com.google.android.youtube",
            "app.vanadium.browser",
        ).forEach {
            assertFalse("$it must not pause screen access", CriticalApps.isSecuritySensitive(it))
        }
    }

    @Test
    fun `the list is narrow, because a false positive is not recoverable`() {
        // This started as a wide keyword list including "wallet", "seb" and "wise". It
        // matched a crypto wallet, screen access switched itself off, and Android does
        // not let an app turn its own accessibility service back on — so blocking was
        // dead until the user happened to notice. Short and generic words are gone, and
        // anything not listed is offered to the user rather than decided for them.
        assertFalse(CriticalApps.isSecuritySensitive("com.cakewallet.cake_wallet"))
        assertFalse(CriticalApps.isSecuritySensitive("com.sebastianyousef.something"))
        assertFalse(CriticalApps.isSecuritySensitive("io.github.sebastianyousef.heed"))
        // Distinctive names still match, wherever they sit in the package.
        assertTrue(CriticalApps.isSecuritySensitive("se.swedbank.mobil"))
        assertTrue(CriticalApps.isSecuritySensitive("dk.danskebank.mobile"))
    }

    @Test
    fun `stepping aside is separate from never blocking`() {
        // A bank is both: never blocked, and a reason to drop screen access.
        assertTrue(CriticalApps.isProtected("com.bankid.bus"))
        // An alarm clock is only the former — no need to disturb screen access for it.
        assertTrue(CriticalApps.isProtected("com.google.android.deskclock"))
        assertFalse(CriticalApps.isSecuritySensitive("com.google.android.deskclock"))
    }
}
