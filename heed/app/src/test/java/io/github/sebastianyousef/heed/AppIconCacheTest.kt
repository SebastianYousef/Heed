package io.github.sebastianyousef.heed

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Pins the shape of the icon cache.
 *
 * The first version cached `ImageBitmap?` straight into a ConcurrentHashMap, which
 * rejects null values — so the first app whose icon could not be loaded (an uninstalled
 * one still present in the session history) crashed the process while drawing the list.
 * The cache has to be able to remember a miss, or it will retry the failing lookup on
 * every frame even if it stops crashing.
 */
class AppIconCacheTest {

    private class Cached(val value: String?)

    @Test
    fun `a concurrent map cannot hold a null value`() {
        val raw = ConcurrentHashMap<String, String>()
        var threw = false
        try {
            @Suppress("UNCHECKED_CAST")
            (raw as ConcurrentHashMap<String, String?>)["pkg"] = null
        } catch (_: NullPointerException) {
            threw = true
        }
        assertTrue("ConcurrentHashMap must still reject nulls", threw)
    }

    @Test
    fun `wrapping lets a miss be cached instead of retried`() {
        val cache = ConcurrentHashMap<String, Cached>()
        val looked = mutableListOf<String>()
        fun get(pkg: String): String? = cache.getOrPut(pkg) {
            looked += pkg
            Cached(null)
        }.value

        assertNull(get("com.gone.app"))
        assertNull(get("com.gone.app"))
        assertTrue("the failing lookup must happen once", looked.size == 1)
    }
}

/**
 * Names for apps the system will not name.
 *
 * A screen-time list that says `com.zhiliaoapp.musically` has not answered the question it
 * was asked. Package visibility fixes most of this, but not apps that are uninstalled, in
 * a private space, or otherwise across a profile boundary — so there has to be a decent
 * last resort.
 */
class PrettyNameTest {

    private fun pretty(pkg: String) =
        io.github.sebastianyousef.heed.ui.AppIcons.prettify(pkg)

    @Test
    fun `platform and vendor noise is dropped`() {
        org.junit.Assert.assertEquals("Snapchat", pretty("com.snapchat.android"))
        org.junit.Assert.assertEquals("Musically", pretty("com.zhiliaoapp.musically"))
        org.junit.Assert.assertEquals("Frontpage", pretty("com.reddit.frontpage"))
    }

    @Test
    fun `camel case is split, single words are left alone`() {
        org.junit.Assert.assertEquals("Desk Clock", pretty("com.google.android.deskClock"))
        org.junit.Assert.assertEquals("Linkedin", pretty("com.linkedin.android"))
    }

    @Test
    fun `a package with nothing but noise still returns something`() {
        org.junit.Assert.assertEquals("com.android", pretty("com.android"))
    }
}
