package io.github.sebastianyousef.heed.focus

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import io.github.sebastianyousef.heed.core.Time
import io.github.sebastianyousef.heed.data.HeedRepository
import io.github.sebastianyousef.heed.usage.ScrollSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The only part of Heed that looks at the screen.
 *
 * It is worth being exact about what that means, because an earlier version of this
 * comment claimed the service ran *without* `canRetrieveWindowContent` and therefore
 * could never be given the screen's text. That stopped being true the moment precise
 * matching was added, and a false reassurance about a privacy boundary is worse than no
 * comment at all. The truth is:
 *
 *  - It **does** hold `canRetrieveWindowContent`, and could read the text on screen.
 *  - It never does. The only things read from a node are `viewIdResourceName` and
 *    `className` — the structural skeleton of a layout. Grep `focus/` for `.text` and
 *    `contentDescription`: there are no reads.
 *  - Android is told which apps may generate events at all (see [restrictToInterestingApps]),
 *    so for everything else the tree is never even offered.
 *
 * That is a narrower thing than "reads your screen", and the narrowness is the whole
 * reason this permission is defensible. It buys exactly one capability that behaviour
 * cannot provide: telling Snapchat's Spotlight from Snapchat's chats, which are the same
 * scroll events in the same app and can only be separated by knowing which screen you
 * are on.
 *
 * Everything else Heed enforces — time limits, launch counts, bedtime, grayscale — runs
 * in [AttentionService] on usage statistics, and keeps working with this service off.
 */
class ScrollWatcherService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentPackage: String? = null
    private var spanStart = 0L
    private var scrollCount = 0
    private var lastScrollAt = 0L
    private var burstStart = 0L
    private var longestBurst = 0L

    /** Scrolling time accumulated across bursts in this visit to the app. */
    private var cumulativeScrollMs = 0L

    /** Whether we have already interrupted during this stretch, so we do it once. */
    private var interventionShown = false

    /**
     * Scroll events counted towards the next seam, and whether the seam is on screen.
     *
     * Counted separately from [scrollCount] because that one is a *burst* counter and
     * resets after three seconds without a scroll — which is what reading a single post
     * looks like. A seam that reset every time you stopped to read would never arrive.
     */
    private var eventsSinceBreak = 0
    private var breakShowing = false

    /**
     * Whether the screen currently showing is one Heed recognises as a feed.
     *
     * Only ever set in Precise mode, from the same anchor matching that decides a block.
     * It is what keeps the seam out of a conversation: in Automatic mode there is no way
     * to know, so the seam counts every scroll in the app and the settings screen says
     * exactly that.
     */
    private var onFeedSurface = false

    override fun onServiceConnected() {
        instance = this
        // Rules and settings are read on every scroll event; warming them here is what
        // lets that path stay allocation-free.
        repo = HeedRepository.get(this).also { it.warmCaches() }

        // Ask Android to stop telling us about apps we have no rule for.
        //
        // TYPE_WINDOW_CONTENT_CHANGED fires constantly in every app on the phone, and
        // each one is a binder transaction into this process whether or not we do
        // anything with it. Unfiltered, that was the single biggest thing Heed cost while
        // sitting in the background: the launcher alone kept the main thread busy. The
        // system can filter by package for free, so the interesting apps are named and
        // everything else stops arriving at all.
        scope.launch {
            HeedRepository.get(this@ScrollWatcherService).dao.observeFocusRules().collect {
                restrictToInterestingApps(it.map { rule -> rule.packageName })
            }
        }
    }

    private fun restrictToInterestingApps(ruled: List<String>) {
        val info = serviceInfo ?: return
        val wanted = buildSet {
            addAll(ruled)
            // Known scrollers even without a rule, so measurement can start the moment
            // one is installed rather than after a rule is created for it.
            addAll(KnownScrollers.packages.keys)
            // And the apps we exist to get out of the way of.
            addAll(CriticalApps.securityPackages)
        }
        info.packageNames = wanted.toTypedArray()
        runCatching { serviceInfo = info }
    }

    private var repo: HeedRepository? = null

    /** How this service draws and how it leaves a screen. See [Surfacer]. */
    private val surfacer by lazy { Surfacer.FromService(this) }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Step aside for a bank, from inside the service that is in its way.
                //
                // This lives here rather than in AttentionService because only the
                // running service can reliably disable itself: a cross-process static
                // reference is null exactly when the service has been rebound and not yet
                // reconnected, which is the case where the notification fired and nothing
                // actually happened. Here `this` is the instance, so there is nothing to
                // be stale.
                if (pkg != currentPackage && CriticalApps.isSecuritySensitive(pkg)) {
                    stepAsideFor(pkg)
                    return
                }
                ForegroundApp.publish(pkg)
                if (pkg != currentPackage) {
                    flush(now)
                    resetVisit()
                    currentPackage = pkg
                    spanStart = now
                    interventionShown = false
                    lastPreciseCheck = 0L
                    checkOnOpen(pkg)
                }
                checkSurface(pkg, event.source)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Tapping a card is how you enter a recommended video, and it is the only
                // moment where the intent is unambiguous — the feed's own thumbnails are
                // still on screen either side of it. Clicks are rare, so this costs
                // nothing between them.
                checkClick(pkg, event.source)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Snapchat and friends switch surfaces without a window-state change, so
                // in-app navigation is only visible here. Heavily throttled, and skipped
                // entirely unless this app is set to Precise.
                if (now - lastPreciseCheck >= PRECISE_INTERVAL_MS) {
                    lastPreciseCheck = now
                    checkSurface(pkg, event.source)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (currentPackage != pkg) {
                    flush(now)
                    resetVisit()
                    currentPackage = pkg
                    spanStart = now
                    interventionShown = false
                }
                recordScroll(now)
                if (now - lastPreciseCheck >= PRECISE_INTERVAL_MS) {
                    lastPreciseCheck = now
                    checkSurface(pkg, event.source)
                }
                maybeIntervene(pkg, now)
            }
        }
    }

    private fun recordScroll(now: Long) {
        scrollCount++
        eventsSinceBreak++
        // A burst is scrolling with no meaningful pause. Pausing to read breaks it, which
        // is exactly the distinction we care about.
        if (lastScrollAt == 0L || now - lastScrollAt > BURST_GAP_MS) {
            // The burst that just ended is banked before a new one starts, so the total
            // survives the pauses that reading anything necessarily creates.
            if (lastScrollAt != 0L) cumulativeScrollMs += lastScrollAt - burstStart
            burstStart = now
        }
        longestBurst = maxOf(longestBurst, now - burstStart)
        lastScrollAt = now
    }

    private fun maybeIntervene(pkg: String, now: Long) {
        // The seam is allowed to fire more than once in a visit, so it cannot sit behind
        // `interventionShown`. What it does sit behind is its own overlay: while the
        // pause is on screen there is nothing to decide.
        if (breakShowing) return

        // The common case — this app has no rule — is a hash lookup and a return, with no
        // coroutine, no query and no allocation. That matters because this runs on every
        // scroll event, which on a fast flick is tens of times a second.
        val repo = repo ?: return
        val rule = repo.cachedRuleFor(pkg) ?: return

        val spent = interventionShown || now - lastBlockAt < BLOCK_COOLDOWN_MS
        if (spent && rule.scrollBreakEvents <= 0) return

        val cumulative = cumulativeScrollMs + (if (lastScrollAt > 0) now - burstStart else 0)
        val outcome = ScrollDecision.decide(
            packageName = pkg,
            rule = rule,
            eventsThisBurst = scrollCount,
            cumulativeScrollMs = cumulative,
            nudgeThresholdMinutes = repo.currentSettings().scrollInterventionMinutes,
            eventsSinceBreak = eventsSinceBreak,
            // Automatic mode has no idea what it is looking at, and says so in the UI
            // rather than here. Precise mode does, and this is that answer.
            onFeed = rule.detection != DetectionMode.PRECISE || onFeedSurface,
        )

        // Everything except the seam is once per visit; the seam is the exception and
        // must not be swallowed by a nudge that already fired.
        if (spent && outcome !is ScrollDecision.Outcome.Break) return

        when (outcome) {
            ScrollDecision.Outcome.Continue -> Unit

            is ScrollDecision.Outcome.Break -> {
                breakShowing = true
                eventsSinceBreak = 0
                FocusOverlay.pause(
                    surfacer = surfacer,
                    headline = "That is ${outcome.afterEvents} more posts.",
                    detail = "Not a limit — the feed comes straight back. This is only the " +
                        "moment to notice you are still here.",
                    seconds = outcome.pauseSeconds,
                ) {
                    // Restart the count from the far side of the seam, so a long session
                    // meets another one rather than running free after the first.
                    breakShowing = false
                    eventsSinceBreak = 0
                    scrollCount = 0
                }
            }

            is ScrollDecision.Outcome.Stop -> {
                interventionShown = true
                lastBlockAt = now
                flush(now)
                FocusOverlay.bounce(surfacer, outcome.headline, outcome.detail)
            }

            is ScrollDecision.Outcome.Nudge -> {
                interventionShown = true
                scope.launch {
                    FocusOverlay.show(
                        surfacer = surfacer,
                        scrollingMinutes = outcome.minutes,
                        trigger = repo.lastAttributedTriggerFor(pkg),
                    )
                }
            }

            // The one case that needs a number from disk, rate-limited so that a rule
            // with a budget does not turn every flick back into a query.
            ScrollDecision.Outcome.NeedsBudgetCheck -> {
                if (now - lastBudgetCheck < BUDGET_CHECK_MS) return
                lastBudgetCheck = now
                scope.launch {
                    val scrolled = repo.dao.scrollSecondsSince(pkg, Time.startOfToday())
                    if (scrolled < rule.dailyScrollSeconds) return@launch
                    val stop = ScrollDecision.budgetExhausted(rule)
                    interventionShown = true
                    lastBlockAt = System.currentTimeMillis()
                    FocusOverlay.bounce(surfacer, stop.headline, stop.detail)
                }
            }
        }
    }

    /**
     * Precise matching: is this a screen the user has taught Heed to block?
     *
     * Only ever runs for apps explicitly set to Precise, so no tree is walked for anything
     * else. A capture request from the UI is honoured for any app, since that is the user
     * deliberately pointing at a screen.
     */
    private fun checkSurface(pkg: String, source: android.view.accessibility.AccessibilityNodeInfo?) {
        if (SurfaceCapture.armed) {
            val tokens = SurfaceCapture.fingerprint(rootInActiveWindow)
            if (tokens.size >= 8) {
                SurfaceCapture.disarm()
                scope.launch { HeedRepository.get(this@ScrollWatcherService).learnSurface(pkg, tokens) }
            }
            return
        }
        // Never bounce out from under the seam. `bounce` dismisses whatever overlay is
        // on screen, and the seam's flag is cleared by its own continue button — so a
        // surface block landing on top of a pause would take the overlay away and leave
        // `breakShowing` set for the rest of the visit, silently disabling every further
        // intervention. This is the same shape as the bug that left `interventionShown`
        // stuck and made blocking work exactly once per visit.
        if (breakShowing) return

        val now = System.currentTimeMillis()
        // A surface block gets its own, much shorter cooldown. It used to share the
        // fifteen-second one with scroll interventions and to set `interventionShown`,
        // which is a once-per-visit flag cleared only when the *package* changes — so
        // after Spotlight was blocked once, nothing else in Snapchat could be blocked
        // until you left the app entirely. That is what made it look intermittent: it
        // was working exactly once per visit.
        if (now - lastSurfaceBlockAt < SURFACE_COOLDOWN_MS) return
        if (CriticalApps.isProtected(pkg)) return

        // Cheap exits first, in the order they are cheap. Almost every event in a normal
        // day dies on one of these three lines without allocating anything or touching
        // the node tree, which is the whole point.
        val repo = repo ?: return
        val rule = repo.cachedRuleFor(pkg) ?: return
        if (rule.detection != DetectionMode.PRECISE) return

        // Two reasons to be looking at the tree, and an app with neither is not worth a
        // single node read. Blocking removes you from a matched feed; the seam only needs
        // to know whether you are in one, so that it can stay out of your conversations.
        val wantsBlock = rule.mode == FocusMode.BLOCK
        val wantsBreak = rule.scrollBreakEvents > 0
        if (!wantsBlock && !wantsBreak) return

        val anchors = KnownSurfaces.forPackage(pkg)
        val taught = repo.cachedSurfacesFor(pkg)
        if (anchors.isEmpty() && taught.isEmpty()) {
            onFeedSurface = false
            return
        }

        // The node tree is only valid for the length of this callback, so anchors are
        // resolved here rather than handed to a coroutine to read later — a recycled node
        // returns nothing, which is indistinguishable from "the feed is not open".
        val root = rootInActiveWindow
        val height = screenHeight()
        val hit = anchors.firstOrNull { anchor ->
            if (anchor.match == KnownSurfaces.Match.CLICK) return@firstOrNull false
            val present = when (anchor.match) {
                KnownSurfaces.Match.SOURCE -> SurfaceCapture.sourceHasId(source, anchor.viewId)
                else -> SurfaceCapture.hasVisibleAnchor(root, anchor.viewId, height, anchor.minFraction)
            }
            // The veto is judged on what is visible too: a friends' row scrolled off the
            // top is still in the layout, and treating that as "friends are on screen"
            // is why Discover never blocked at all.
            // A carve-out the user has switched off stops vetoing.
            val guarded = anchor.exceptionKey?.let { rule.isExceptionEnabled(it) } ?: true
            present && (!guarded || anchor.unless.none {
                SurfaceCapture.hasVisibleAnchor(root, it, height, VETO_MIN_FRACTION)
            })
        }

        // Fingerprinting walks up to four hundred nodes, so it only happens for apps
        // where the user has actually taught a screen and no shipped anchor matched.
        //
        // Written as a nullable rather than as a series of early returns because the
        // answer is now needed either way: "this is not a feed" is what releases the
        // seam, and a function that returns instead of saying so would leave the flag
        // stuck at whatever the last matched screen set it to.
        val label = hit?.label ?: taughtMatch(root, taught)

        onFeedSurface = label != null
        if (label == null || !wantsBlock) return

        lastSurfaceBlockAt = now
        bounceOut(label, rule.appLabel)
    }

    /** A screen the user taught Heed to block, or null. An explicit allow beats a block. */
    private fun taughtMatch(
        root: android.view.accessibility.AccessibilityNodeInfo?,
        taught: List<LearnedSurface>,
    ): String? {
        if (taught.isEmpty()) return null
        val tokens = SurfaceCapture.fingerprint(root)
        if (tokens.isEmpty()) return null
        if (SurfaceMatcher.match(tokens, taught.filter { !it.block }) != null) return null
        return SurfaceMatcher.match(tokens, taught.filter { it.block })?.label
    }

    /**
     * The usable height of the screen, cached. Read once because it cannot change
     * without the service being reconfigured, and it is needed on every anchor test.
     */
    private var cachedHeight = 0
    private fun screenHeight(): Int {
        if (cachedHeight > 0) return cachedHeight
        val metrics = resources.displayMetrics
        cachedHeight = metrics.heightPixels
        return cachedHeight
    }

    /**
     * A click that opens a recommended video.
     *
     * Blocking the feed's own screen is not enough on Snapchat, because Discover shares
     * its tab with your friends' stories: while the friends' row is on screen the feed is
     * deliberately left alone, and a tap from there goes straight into a recommended
     * video. Catching the tap closes that gap without touching anything a friend posted.
     */
    private fun checkClick(pkg: String, source: android.view.accessibility.AccessibilityNodeInfo?) {
        source ?: return
        val repo = repo ?: return
        val rule = repo.cachedRuleFor(pkg) ?: return
        if (rule.detection != DetectionMode.PRECISE || rule.mode != FocusMode.BLOCK) return
        if (CriticalApps.isProtected(pkg)) return

        val clickAnchors = KnownSurfaces.forPackage(pkg).filter { it.match == KnownSurfaces.Match.CLICK }
        if (clickAnchors.isEmpty()) return

        // Walk up a few parents: the tap lands on a thumbnail or a label inside the card,
        // not on the card itself.
        val ids = SurfaceCapture.selfAndAncestorIds(source, CLICK_ANCESTOR_DEPTH)
        val hit = clickAnchors.firstOrNull { it.viewId in ids } ?: return

        lastSurfaceBlockAt = System.currentTimeMillis()
        bounceOut(hit.label, rule.appLabel)
    }

    /**
     * Leave the screen, and make sure we actually left.
     *
     * A single Back is not always enough — a feed hosted inside a pager can swallow it,
     * and the user is left looking at a banner explaining a block that did not happen.
     * So the exit is verified: if the same surface is still there a moment later, press
     * again, and fall back to Home rather than keep pressing forever.
     */
    private fun bounceOut(label: String, appLabel: String) {
        FocusOverlay.bounce(surfacer, "Not $label", "The rest of $appLabel still works.")
        escalate = 0
        main.postDelayed(exitCheck, EXIT_RECHECK_MS)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var escalate = 0

    private val exitCheck = object : Runnable {
        override fun run() {
            val pkg = currentPackage ?: return
            val anchors = KnownSurfaces.forPackage(pkg)
            val root = rootInActiveWindow ?: return
            // Judged on the same visible-bounds test that decided to block, so the
            // escalation cannot be driven by a card that is merely in the layout.
            val stillThere = anchors.any { a ->
                a.match == KnownSurfaces.Match.WINDOW &&
                    SurfaceCapture.hasVisibleAnchor(root, a.viewId, screenHeight(), a.minFraction)
            }
            if (!stillThere) return
            // Back, and at most once more. Never Home.
            //
            // The first version fell back to the home screen when two Backs had not
            // worked, which threw the user out of Snapchat entirely — the exact thing
            // this feature exists to avoid, and worse than the feed it was trying to
            // prevent. If the app will not leave the screen, the honest outcome is to
            // stop pressing: a feed that was not closed is a small failure, and ejecting
            // someone from a conversation is a large one.
            if (escalate >= MAX_EXIT_ATTEMPTS) return
            escalate++
            performGlobalAction(GLOBAL_ACTION_BACK)
            main.postDelayed(this, EXIT_RECHECK_MS)
        }
    }

    /** Daily usage limits are checked on entry, before a single scroll happens. */
    private fun checkOnOpen(pkg: String) {
        scope.launch {
            val repo = HeedRepository.get(this@ScrollWatcherService)
            val verdict = FocusEnforcer.from(repo.dao) { repo.isBedtimeNow() }.onAppOpened(pkg)
            if (verdict is FocusEnforcer.Verdict.Block) {
                lastBlockAt = System.currentTimeMillis()
                FocusOverlay.block(surfacer, verdict.headline, verdict.detail)
            }
        }
    }

    /**
     * Turn screen access off because a banking or identity app just opened.
     *
     * Checked against the setting each time rather than cached, so switching it off in
     * the UI takes effect on the next app launch rather than the next reboot.
     */
    private fun stepAsideFor(pkg: String) {
        if (pkg == lastOfferedFor) return
        lastOfferedFor = pkg
        scope.launch {
            val repo = HeedRepository.get(this@ScrollWatcherService)
            val notifier = io.github.sebastianyousef.heed.notify.Notifier(this@ScrollWatcherService)
            if (repo.settings.first().pauseForBanking) {
                flush(System.currentTimeMillis())
                notifier.screenAccessPaused(pkg)
                runCatching { disableSelf() }
            } else {
                // The default. Offer the choice instead of taking it, because Heed cannot
                // undo the taking.
                notifier.offerToStepAside(
                    pkg,
                    io.github.sebastianyousef.heed.capture.NotificationMapper
                        .appLabel(this@ScrollWatcherService, pkg),
                )
            }
        }
    }

    /** Offer once per app per connection, so a bank you use daily is not a daily nag. */
    private var lastOfferedFor: String? = null

    /** Persist the stretch just finished so the usage tracker can pair it with a session. */
    private fun flush(now: Long) {
        val pkg = currentPackage ?: return
        if (scrollCount == 0) {
            reset()
            return
        }
        val span = ScrollSpan(
            packageName = pkg,
            startedAt = spanStart,
            endedAt = now,
            events = scrollCount,
            longestBurstMs = longestBurst,
        )
        scope.launch { HeedRepository.get(this@ScrollWatcherService).dao.insertScrollSpan(span) }
        reset()
    }

    private fun reset() {
        scrollCount = 0
        longestBurst = 0
        lastScrollAt = 0
        burstStart = 0
    }

    /** A new app is in front: the cumulative count belongs to the last one. */
    private fun resetVisit() {
        reset()
        cumulativeScrollMs = 0
        lastBudgetCheck = 0
        eventsSinceBreak = 0
        onFeedSurface = false
        breakShowing = false
    }

    override fun onInterrupt() {
        flush(System.currentTimeMillis())
    }

    override fun onDestroy() {
        ForegroundApp.clear()
        if (instance === this) instance = null
        flush(System.currentTimeMillis())
        scope.cancel()
        super.onDestroy()
    }

    private var lastBlockAt = 0L
    private var lastPreciseCheck = 0L
    private var lastBudgetCheck = 0L
    private var lastSurfaceBlockAt = 0L

    companion object {
        /**
         * Whether the user has enabled the watcher, read from the system rather than from
         * a flag we set ourselves. The in-process flag goes stale whenever the service is
         * restarted without the UI, which showed people a "turn this on" card for a
         * service that was already running.
         */
        fun isEnabled(context: android.content.Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any {
                android.content.ComponentName.unflattenFromString(it)?.className ==
                    ScrollWatcherService::class.java.name
            }
        }

        /** A pause longer than this ends a scroll burst. */
        private const val BURST_GAP_MS = 3_000L

        /** Never block twice in quick succession, or leaving the app becomes a fight. */
        private const val BLOCK_COOLDOWN_MS = 15_000L

        /**
         * Content-changed events fire constantly. Walking the tree on each one would be a
         * battery disaster, and a second is fast enough to feel immediate.
         */
        private const val PRECISE_INTERVAL_MS = 1_000L

        /** How often a daily scrolling budget is worth re-reading from disk. */
        private const val BUDGET_CHECK_MS = 5_000L

        /**
         * Long enough not to fight an app mid-animation, short enough that walking back
         * into the same feed is stopped again rather than waved through.
         */
        private const val SURFACE_COOLDOWN_MS = 2_500L

        /**
         * How much of the screen the vetoing view must still occupy to count as visible.
         * A sliver of a friends' row disappearing off the top is not "looking at your
         * friends".
         */
        private const val VETO_MIN_FRACTION = 0.08f

        /** How far up from a tapped view to look for the card that owns it. */
        private const val CLICK_ANCESTOR_DEPTH = 6

        /**
         * Long enough for the app to finish animating away from the blocked screen.
         * Seven hundred milliseconds was not: the check fired while the transition was
         * still running, decided the screen was still there, and pressed again.
         */
        private const val EXIT_RECHECK_MS = 1_200L

        /** Two presses, then leave it alone. */
        private const val MAX_EXIT_ATTEMPTS = 2

        @Volatile private var instance: ScrollWatcherService? = null

        /**
         * Switch the service off from inside the app.
         *
         * Banking apps refuse to run while any accessibility service is enabled — a fair
         * defence against overlay-and-tap fraud, and not one to be worked around. This
         * makes the honest answer a single tap instead of a hunt through system settings.
         * Android offers no matching way to switch it back on, by design, so re-enabling
         * still means a trip to Settings; the UI is explicit about that rather than
         * pretending otherwise.
         */
        fun pause(): Boolean =
            instance?.let { runCatching { it.disableSelf() }.isSuccess } ?: false
    }
}
