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

    /**
     * A friend's story, opened after scrolling past the friends' row.
     *
     * Read off the device: the feed underneath stays in the tree and keeps reporting
     * on-screen bounds, so without a second guard this is indistinguishable from being in
     * the Discover feed — and a friend's story would have been blocked.
     */
    private val friendsStoryOpen = setOf(
        "com.snapchat.android:id/opera_viewer",
        "com.snapchat.android:id/base_image_layer_container",
        "com.snapchat.android:id/df_large_story",
    )
    private val chats = setOf(
        "com.snapchat.android:id/ff_item",
        "com.snapchat.android:id/ptr_container",
        "com.snapchat.android:id/ngs_chat_icon_container",
    )

    /**
     * Mirrors how the service evaluates an anchor against a screen: present, and not
     * vetoed. CLICK anchors are excluded because they are never tested against the screen
     * at all — they are matched against the ids of a view the user actually tapped, so
     * "a Discover card exists somewhere" is not what triggers them.
     */
    private fun fires(anchor: KnownSurfaces.Anchor, screen: Set<String>): Boolean =
        anchor.match != KnownSurfaces.Match.CLICK &&
            anchor.viewId in screen &&
            anchor.unless.none { it in screen }

    /** Mirrors checkClick: the tapped view, or one of its parents, carries the id. */
    private fun firesOnTap(anchor: KnownSurfaces.Anchor, tappedIds: Set<String>): Boolean =
        anchor.match == KnownSurfaces.Match.CLICK && anchor.viewId in tappedIds

    private fun snapchat(idSuffix: String) = KnownSurfaces.forPackage("com.snapchat.android")
        .first { it.viewId.endsWith(idSuffix) && it.match != KnownSurfaces.Match.CLICK }

    @Test
    fun `spotlight is recognised`() {
        assertTrue(fires(snapchat("spotlight_container"), spotlight))
    }

    @Test
    fun `chats are never a feed`() {
        KnownSurfaces.forPackage("com.snapchat.android").forEach {
            assertFalse("${it.label} must not fire on the chat list", fires(it, chats))
        }
    }

    @Test
    fun `friends stories veto the discover block`() {
        val discover = snapchat("df_large_story")
        // Opening the Community tab shows friends along the top with Discover beneath.
        assertFalse("friends visible must not be blocked", fires(discover, communityTop))
        // Scroll them away and only the feed is left.
        assertTrue("the discover feed alone should block", fires(discover, communityScrolled))
    }

    @Test
    fun `a friends story is never blocked, by any anchor`() {
        val snapchat = KnownSurfaces.forPackage("com.snapchat.android")
        snapchat.forEach {
            assertFalse(
                "${it.label} must not fire while a friend's story is open",
                fires(it, friendsStoryOpen),
            )
        }
        // And tapping a friend's card is not tapping a Discover card.
        val tappedFriendCard = setOf(
            "com.snapchat.android:id/friend_card_frame",
            "com.snapchat.android:id/ptr_container",
        )
        snapchat.forEach {
            assertFalse(
                "${it.label} must not fire on tapping a friend",
                firesOnTap(it, tappedFriendCard),
            )
        }
    }

    @Test
    fun `tapping a discover card does fire the click anchor`() {
        val click = KnownSurfaces.forPackage("com.snapchat.android")
            .first { it.match == KnownSurfaces.Match.CLICK }
        assertTrue(firesOnTap(click, setOf("com.snapchat.android:id/df_large_story")))
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
        assertTrue(reddit.unless.isEmpty())
        assertNotNull(reddit.viewId)
    }
}

/**
 * The friends' carve-out, as something the user owns rather than something Heed decided.
 *
 * "Block the feed but not my friends" and "block all of it" are both legitimate, and
 * which one is right is not a judgement this app is in a position to make permanently.
 */
class ExceptionToggleTest {

    private val rule = io.github.sebastianyousef.heed.focus.FocusRule(
        packageName = "com.snapchat.android",
        appLabel = "Snapchat",
        mode = io.github.sebastianyousef.heed.focus.FocusMode.BLOCK,
        detection = io.github.sebastianyousef.heed.focus.DetectionMode.PRECISE,
    )

    @Test
    fun `carve-outs are on unless switched off`() {
        assertTrue(rule.isExceptionEnabled("friends_stories"))
        // Unknown keys default to on too, so an exception added in a later version
        // protects existing users rather than silently blocking something new.
        assertTrue(rule.isExceptionEnabled("something_added_later"))
    }

    @Test
    fun `switching one off does not disturb the others`() {
        val off = rule.withException("friends_stories", false)
            .withException("other", false)
            .withException("friends_stories", true)
        assertTrue(off.isExceptionEnabled("friends_stories"))
        assertFalse(off.isExceptionEnabled("other"))
    }

    @Test
    fun `the discover anchor is the one the toggle governs`() {
        val discover = KnownSurfaces.forPackage("com.snapchat.android")
            .first { it.viewId.endsWith("df_large_story") && it.match == KnownSurfaces.Match.WINDOW }
        assertEquals("friends_stories", discover.exceptionKey)
        assertTrue(discover.unless.isNotEmpty())

        // The recommendations anchor is unguarded: turning the carve-out off cannot make
        // Spotlight *less* blocked, and turning it on cannot let it through.
        val recommendations = KnownSurfaces.forPackage("com.snapchat.android")
            .first { it.viewId.endsWith("spotlight_container") }
        assertNull(recommendations.exceptionKey)
    }

    @Test
    fun `the switch is offered for snapchat and nothing else`() {
        assertEquals(1, KnownSurfaces.exceptionsFor("com.snapchat.android").size)
        assertTrue(KnownSurfaces.exceptionsFor("com.linkedin.android").isEmpty())
    }
}

/**
 * Shipped anchors are read, never copied.
 *
 * They used to be seeded into the database as if the user had taught them, which made
 * them go stale the moment one was corrected: the Snapchat rule ended up listing
 * "Spotlight, Discover, Spotlight", one naming a view id that no longer exists in the app.
 */
class SeededSurfaceTest {

    /** Capture refuses a fingerprint under eight tokens, so anything shorter was seeded. */
    @Test
    fun `a taught fingerprint is always many tokens`() {
        val taught = io.github.sebastianyousef.heed.focus.LearnedSurface(
            packageName = "com.snapchat.android",
            label = "Something I pointed at",
            fingerprint = (1..12).joinToString("\n") { "id:view$it" },
            block = true,
            capturedAt = 0L,
        )
        assertTrue(taught.tokens.size > 1)

        val seeded = io.github.sebastianyousef.heed.focus.LearnedSurface(
            packageName = "com.snapchat.android",
            label = "Spotlight",
            fingerprint = "com.snapchat.android:id/spotlight_card_static_thumbnail",
            block = true,
            capturedAt = 0L,
        )
        assertEquals(1, seeded.tokens.size)
    }

    @Test
    fun `no shipped anchor names the id that went stale`() {
        assertTrue(
            KnownSurfaces.anchors.none {
                it.viewId.endsWith("spotlight_card_static_thumbnail")
            }
        )
    }
}
