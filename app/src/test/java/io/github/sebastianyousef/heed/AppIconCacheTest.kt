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
