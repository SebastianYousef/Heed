package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.FocusEnforcer
import io.github.sebastianyousef.heed.focus.FocusMode
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.KnownScrollers
import io.github.sebastianyousef.heed.focus.KnownSurfaces
import io.github.sebastianyousef.heed.focus.LearnedSurface
import io.github.sebastianyousef.heed.focus.SurfaceMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun surface(label: String, tokens: List<String>, block: Boolean) = LearnedSurface(
    packageName = "com.snapchat.android",
    label = label,
    fingerprint = tokens.joinToString("\n"),
    block = block,
    capturedAt = 0,
)

/** Stand-ins for two different Snapchat screens, as view ids rather than content. */
private val DISCOVERY = listOf(
    "id:com.snapchat.android:id/discover_feed", "id:com.snapchat.android:id/tile_grid",
    "cls:androidx.recyclerview.widget.RecyclerView@4", "id:com.snapchat.android:id/subscribe",
    "id:com.snapchat.android:id/thumbnail", "cls:android.widget.FrameLayout@2",
    "id:com.snapchat.android:id/nav_bar", "id:com.snapchat.android:id/publisher_name",
)
private val STORIES = listOf(
    "id:com.snapchat.android:id/friend_story_row", "id:com.snapchat.android:id/avatar",
    "cls:androidx.recyclerview.widget.RecyclerView@4", "id:com.snapchat.android:id/username",
    "id:com.snapchat.android:id/timestamp", "cls:android.widget.FrameLayout@2",
    "id:com.snapchat.android:id/nav_bar", "id:com.snapchat.android:id/story_ring",
)

class SurfaceMatcherTest {

    private val learned = listOf(
        surface("Discovery", DISCOVERY, block = true),
        surface("Friends' stories", STORIES, block = false),
    )

    @Test
    fun `the screen you taught it to block is recognised again`() {
        val hit = SurfaceMatcher.match(DISCOVERY.toSet(), learned)
        assertEquals("Discovery", hit?.label)
        assertTrue(hit!!.block)
    }

    @Test
    fun `friends stories are not mistaken for discovery`() {
        val hit = SurfaceMatcher.match(STORIES.toSet(), learned)
        assertEquals("Friends' stories", hit?.label)
        assertFalse("this one must be left alone", hit!!.block)
    }

    @Test
    fun `a layout that shifted a little still matches`() {
        // A banner gone, an extra tile: real screens are never identical between visits.
        val shifted = (DISCOVERY.drop(1) + "id:com.snapchat.android:id/promo_banner").toSet()
        assertEquals("Discovery", SurfaceMatcher.match(shifted, learned)?.label)
    }

    @Test
    fun `an unrelated screen matches nothing`() {
        val settings = setOf("id:com.snapchat.android:id/settings_list", "cls:android.widget.ListView@3")
        assertNull(SurfaceMatcher.match(settings, learned))
    }

    @Test
    fun `an explicit allow beats a block when both match equally`() {
        val ambiguous = listOf(
            surface("Feed", DISCOVERY, block = true),
            surface("Carve-out", DISCOVERY, block = false),
        )
        assertFalse(SurfaceMatcher.match(DISCOVERY.toSet(), ambiguous)!!.block)
    }

    @Test
    fun `an empty capture never matches`() {
        assertNull(SurfaceMatcher.match(emptySet(), learned))
    }
}

class KnownScrollersTest {

    @Test
    fun `the apps whose business is the scroll get a preset`() {
        listOf("com.snapchat.android", "com.instagram.android", "com.google.android.youtube",
            "com.linkedin.android").forEach {
            assertTrue("$it should be known", KnownScrollers.isKnown(it))
        }
    }

    @Test
    fun `an authenticator does not`() {
        // The bug this exists to prevent: a Block rule landing on the wrong app entirely.
        assertFalse(KnownScrollers.isKnown("com.azure.authenticator"))
        assertNull(KnownScrollers.presetFor("com.azure.authenticator", "Authenticator"))
    }

    @Test
    fun `presets nudge rather than block, so nothing is blocked unasked`() {
        val preset = KnownScrollers.presetFor("com.snapchat.android", "Snapchat")!!
        assertEquals(FocusMode.NUDGE, preset.mode)
        assertTrue(preset.fromPreset)
        assertEquals(0, preset.dailyUsageSeconds)
    }
}

private class Fake(
    private val rule: FocusRule?,
    private val launches: Int = 0,
    private val bedtime: Boolean = false,
    private val usage: Int = 0,
) : FocusEnforcer.Data {
    override suspend fun rule(pkg: String) = rule
    override suspend fun scrollSecondsToday(pkg: String) = 0
    override suspend fun usageSecondsToday(pkg: String) = usage
    override suspend fun launchesToday(pkg: String) = launches
    override suspend fun isBedtime() = bedtime
}

class LaunchAndBedtimeTest {

    private val rule = FocusRule("com.instagram.android", "Instagram", dailyLaunchLimit = 5)

    @Test
    fun `opening it too many times is blocked even under the time limit`() = runBlocking {
        assertEquals(FocusEnforcer.Verdict.Allow, FocusEnforcer(Fake(rule, launches = 4)).onAppOpened("x"))
        assertTrue(FocusEnforcer(Fake(rule, launches = 5)).onAppOpened("x") is FocusEnforcer.Verdict.Block)
    }

    @Test
    fun `bedtime closes anything with a rule`() = runBlocking {
        val verdict = FocusEnforcer(Fake(rule, bedtime = true)).onAppOpened("x")
        assertTrue(verdict is FocusEnforcer.Verdict.Block)
        assertTrue((verdict as FocusEnforcer.Verdict.Block).headline.contains("bedtime"))
    }

    @Test
    fun `bedtime leaves apps with no rule alone`() = runBlocking {
        assertEquals(
            FocusEnforcer.Verdict.Allow,
            FocusEnforcer(Fake(null, bedtime = true)).onAppOpened("com.android.dialer"),
        )
    }
}

class KnownSurfaceAnchorTest {

    private fun anchor(label: String, viewId: String, block: Boolean = true) = LearnedSurface(
        packageName = "com.snapchat.android",
        label = label,
        fingerprint = viewId,
        block = block,
        capturedAt = 0,
    )

    private val spotlight = anchor("Spotlight", "com.snapchat.android:id/spotlight_card_static_thumbnail")
    private val discover = anchor("Discover", "com.snapchat.android:id/df_large_story")

    @Test
    fun `one distinctive id is enough to name the screen`() {
        val screen = setOf(
            "cls:android.widget.FrameLayout@0",
            "id:com.snapchat.android:id/nav_bar",
            "com.snapchat.android:id/spotlight_card_static_thumbnail",
        )
        assertEquals("Spotlight", SurfaceMatcher.match(screen, listOf(spotlight, discover))?.label)
    }

    @Test
    fun `chats are not blocked just because they are in the same app`() {
        val chats = setOf("id:com.snapchat.android:id/chat_list", "id:com.snapchat.android:id/nav_bar")
        assertNull(SurfaceMatcher.match(chats, listOf(spotlight, discover)))
    }

    @Test
    fun `an anchor wins over a whole-layout fingerprint`() {
        // A taught fingerprint of the chat list should not override an exact Spotlight hit.
        val taught = surface("Something I taught", STORIES, block = false)
        val screen = STORIES.toSet() + "com.snapchat.android:id/spotlight_card_static_thumbnail"
        assertEquals("Spotlight", SurfaceMatcher.match(screen, listOf(taught, spotlight))?.label)
    }

    @Test
    fun `an explicit allow on the same anchor carves out an exception`() {
        val allowed = anchor("Discover", "com.snapchat.android:id/df_large_story", block = false)
        val screen = setOf("com.snapchat.android:id/df_large_story")
        assertFalse(SurfaceMatcher.match(screen, listOf(discover, allowed))!!.block)
    }

    @Test
    fun `every shipped anchor names a real package and a non-empty id`() {
        assertTrue(KnownSurfaces.anchors.isNotEmpty())
        KnownSurfaces.anchors.forEach {
            assertTrue(it.packageName.contains('.'))
            assertTrue(it.viewId.isNotBlank())
            assertTrue(it.label.isNotBlank())
        }
        assertEquals(2, KnownSurfaces.forPackage("com.snapchat.android").size)
    }
}
