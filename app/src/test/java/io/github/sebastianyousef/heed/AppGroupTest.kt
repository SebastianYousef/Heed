package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.focus.AppGroup
import io.github.sebastianyousef.heed.focus.FocusEnforcer
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.focus.ScrollDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val INSTA = "com.instagram.android"
private const val TIKTOK = "com.zhiliaoapp.musically"
private const val AUTH = "com.azure.authenticator"

/**
 * The shared budget, which exists because per-app limits have one specific hole.
 *
 * The test that matters most here is [switching apps spends the same budget]: it is the
 * behaviour the whole feature is for, and the one a per-app limit gets wrong while
 * reporting success.
 */
class AppGroupTest {

    private class Fake(
        private val rule: FocusRule? = null,
        private val group: AppGroup? = null,
        private val groupUsage: Int = 0,
        private val groupLaunches: Int = 0,
    ) : FocusEnforcer.Data {
        override suspend fun rule(pkg: String) = rule
        override suspend fun usageSecondsToday(pkg: String) = 0
        override suspend fun launchesToday(pkg: String) = 0
        override suspend fun isBedtime() = false
        override suspend fun group(pkg: String) =
            group?.takeIf { pkg in it.members && it.hasLimits }
        override suspend fun groupUsageSecondsToday(group: AppGroup) = groupUsage
        override suspend fun groupLaunchesToday(group: AppGroup) = groupLaunches
    }

    private val feeds = AppGroup(
        id = 1,
        name = "Feeds",
        packages = "$INSTA,$TIKTOK",
        dailyUsageSeconds = 1_800,
    )

    @Test
    fun `membership is a set, sorted, and survives a round trip`() {
        val empty = AppGroup(name = "Feeds")
        assertEquals(emptyList<String>(), empty.members)

        val one = empty.withMember(INSTA, true)
        assertEquals(listOf(INSTA), one.members)
        // Adding twice is not two memberships.
        assertEquals(listOf(INSTA), one.withMember(INSTA, true).members)

        val two = one.withMember(TIKTOK, true)
        assertEquals(listOf(INSTA, TIKTOK).sorted(), two.members)
        assertEquals(listOf(TIKTOK), two.withMember(INSTA, false).members)
    }

    @Test
    fun `a group with no limits is nothing to enforce`() {
        assertFalse(AppGroup(name = "Feeds", packages = INSTA).hasLimits)
        assertTrue(feeds.hasLimits)
    }

    @Test
    fun `a group limit applies to a member with no rule of its own`() = runBlocking {
        // The point of a group is that its members are interchangeable, so most of them
        // will never be worth a rule. If membership alone did not block, the budget would
        // only bind the apps that needed it least.
        assertTrue(
            FocusEnforcer(Fake(rule = null, group = feeds, groupUsage = 1_800))
                .onAppOpened(INSTA) is FocusEnforcer.Verdict.Block
        )
    }

    @Test
    fun `switching apps spends the same budget`() = runBlocking {
        // Twenty-five minutes in one, five in the other: under a per-app limit both are
        // fine, and this is exactly the case the group exists to catch.
        val data = Fake(group = feeds, groupUsage = 1_800)
        assertTrue(data.let { FocusEnforcer(it).onAppOpened(INSTA) } is FocusEnforcer.Verdict.Block)
        assertTrue(data.let { FocusEnforcer(it).onAppOpened(TIKTOK) } is FocusEnforcer.Verdict.Block)
    }

    @Test
    fun `under the shared limit nothing is stopped`() = runBlocking {
        assertEquals(
            FocusEnforcer.Verdict.Allow,
            FocusEnforcer(Fake(group = feeds, groupUsage = 1_799)).onAppOpened(INSTA),
        )
    }

    @Test
    fun `a shared limit on opens is counted across the group`() = runBlocking {
        val group = feeds.copy(dailyUsageSeconds = 0, dailyLaunchLimit = 10)
        assertEquals(
            FocusEnforcer.Verdict.Allow,
            FocusEnforcer(Fake(group = group, groupLaunches = 9)).onAppOpened(TIKTOK),
        )
        assertTrue(
            FocusEnforcer(Fake(group = group, groupLaunches = 10)).onAppOpened(TIKTOK)
                is FocusEnforcer.Verdict.Block
        )
    }

    @Test
    fun `a non-member is untouched by the group`() = runBlocking {
        assertEquals(
            FocusEnforcer.Verdict.Allow,
            FocusEnforcer(Fake(group = feeds, groupUsage = 999_999)).onAppOpened(LINKEDIN),
        )
    }

    @Test
    fun `a critical app in a group is still never blocked`() = runBlocking {
        // Someone will put their bank or their authenticator in a group by accident. The
        // guard is at the top of the enforcer, and this is the test that keeps it there.
        val group = feeds.copy(packages = "$INSTA,$AUTH")
        assertEquals(
            FocusEnforcer.Verdict.Allow,
            FocusEnforcer(Fake(group = group, groupUsage = 999_999)).onAppOpened(AUTH),
        )
    }

    @Test
    fun `a shared scrolling budget asks for the total, and only when it is set`() {
        val budgeted = feeds.copy(dailyScrollSeconds = 600)
        assertEquals(
            ScrollDecision.Outcome.NeedsBudgetCheck,
            ScrollDecision.groupOutcome(INSTA, budgeted),
        )
        // No budget, no disk read — this runs on every scroll event.
        assertEquals(ScrollDecision.Outcome.Continue, ScrollDecision.groupOutcome(INSTA, feeds))
        assertEquals(ScrollDecision.Outcome.Continue, ScrollDecision.groupOutcome(INSTA, null))
        assertEquals(
            ScrollDecision.Outcome.Continue,
            ScrollDecision.groupOutcome(AUTH, budgeted),
        )
    }

    @Test
    fun `the exhausted message names the group, not the app you happened to open`() {
        val stop = ScrollDecision.groupBudgetExhausted(feeds.copy(dailyScrollSeconds = 600))
        assertTrue(stop.headline.contains("Feeds"))
        assertTrue(stop.detail.contains("10 minutes"))
    }

    private companion object {
        const val LINKEDIN = "com.linkedin.android"
    }
}
