package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.KnownSurfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchors, checked against what Snapchat 14.20.0.50 actually renders.
 *
 * Every id here was read off a running device with uiautomator. That matters because the
 * previous set was copied from another project's list and one of them —
 * `spotlight_card_static_thumbnail` — no longer exists in Snapchat at all, so the block it
 * was supposed to drive could never have fired.
 */
class AnchorTest {

    /** Ids observed on each tab, from the real dumps. */
    private val spotlight = setOf(
        "com.snapchat.android:id/spotlight_container",
        "com.snapchat.android:id/opera_viewer",
        "com.snapchat.android:id/ngs_spotlight_icon_container",
    )
    private val communityTop = setOf(
        "com.snapchat.android:id/df_large_story",
        "com.snapchat.android:id/friend_card_frame",
        "com.snapchat.android:id/ptr_container",
    )
    private val communityScrolled = setOf(
        "com.snapchat.android:id/df_large_story",
        "com.snapchat.android:id/ptr_container",
    )
    private val chats = setOf(
        "com.snapchat.android:id/ff_item",
        "com.snapchat.android:id/ptr_container",
        "com.snapchat.android:id/ngs_chat_icon_container",
    )

    /** Mirrors the service's test: present, and not vetoed. */
    private fun fires(anchor: KnownSurfaces.Anchor, screen: Set<String>): Boolean =
        anchor.viewId in screen && (anchor.unless?.let { it !in screen } ?: true)

    private fun snapchat(label: String) =
        KnownSurfaces.forPackage("com.snapchat.android").first { it.label == label }

    @Test
    fun `spotlight is recognised`() {
        assertTrue(fires(snapchat("Spotlight"), spotlight))
    }

    @Test
    fun `chats are never a feed`() {
        KnownSurfaces.forPackage("com.snapchat.android").forEach {
            assertFalse("${it.label} must not fire on the chat list", fires(it, chats))
        }
    }

    @Test
    fun `friends stories veto the discover block`() {
        val discover = snapchat("Discover")
        // Opening the Community tab shows friends along the top with Discover beneath.
        assertFalse("friends visible must not be blocked", fires(discover, communityTop))
        // Scroll them away and only the feed is left.
        assertTrue("the discover feed alone should block", fires(discover, communityScrolled))
    }

    @Test
    fun `unofficial youtube clients inherit the anchor, rewritten`() {
        val fork = KnownSurfaces.forPackage("app.revanced.android.youtube")
        assertEquals(1, fork.size)
        assertEquals("app.revanced.android.youtube:id/reel_player_underlay", fork.first().viewId)
        assertTrue(KnownSurfaces.hasBlockAnchors("app.revanced.android.youtube"))
    }

    @Test
    fun `an app with no anchors offers none`() {
        assertTrue(KnownSurfaces.forPackage("com.linkedin.android").isEmpty())
        assertFalse(KnownSurfaces.hasBlockAnchors("com.linkedin.android"))
    }

    @Test
    fun `reddit matches on the scrolled view, not the window`() {
        val reddit = KnownSurfaces.forPackage("com.reddit.frontpage").first()
        assertEquals(KnownSurfaces.Match.SOURCE, reddit.match)
        assertNull(reddit.unless)
        assertNotNull(reddit.viewId)
    }
}
