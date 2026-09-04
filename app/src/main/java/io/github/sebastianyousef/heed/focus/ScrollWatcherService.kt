package io.github.sebastianyousef.heed.focus

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import io.github.sebastianyousef.heed.data.HeedRepository
import io.github.sebastianyousef.heed.usage.ScrollSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Measures scrolling, and nothing else.
 *
 * This is the most invasive permission Heed asks for, so it is worth being precise about
 * what it can and cannot do. The service is declared without `canRetrieveWindowContent`,
 * which means Android will not give it the screen's text under any circumstances — not
 * messages, not what you type, not passwords. It receives two event types: "something
 * scrolled" and "the foreground window changed". That is the entire surface.
 *
 * That constraint shaped the detection approach, and improved it. Rather than recognising
 * particular feeds by their view ids — which needs content access, breaks whenever an app
 * ships a redesign, and only ever covers apps someone remembered to add — Heed watches for
 * the *behaviour*: fast, sustained, uninterrupted scrolling. That is what doom scrolling
 * is, it works in an app nobody has heard of yet, and it can be measured from event
 * timing alone.
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

    override fun onServiceConnected() {
        connected = true
        instance = this
        // Rules and settings are read on every scroll event; warming them here is what
        // lets that path stay allocation-free.
        repo = HeedRepository.get(this).also { it.warmCaches(scope) }

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
        if (interventionShown) return
        if (now - lastBlockAt < BLOCK_COOLDOWN_MS) return

        // Everything below this line used to be a coroutine launch, a Room query and a
        // DataStore read — per scroll event. Now the common case, which is "this app has
        // no rule", costs one hash lookup and returns.
        val repo = repo ?: return
        val rule = repo.cachedRuleFor(pkg) ?: return
        if (rule.mode == FocusMode.OFF && rule.dailyScrollSeconds <= 0) return
        if (CriticalApps.isProtected(pkg)) return

        // A daily scrolling budget is the only thing here that needs a count from disk,
        // and only for apps that have one set. Checked at most once every few seconds.
        val needsBudget = rule.dailyScrollSeconds > 0
        if (needsBudget && now - lastBudgetCheck < BUDGET_CHECK_MS) return

        val burstMs = longestBurst
        val events = scrollCount
        val cumulative = cumulativeScrollMs + (if (lastScrollAt > 0) now - burstStart else 0)

        if (rule.mode == FocusMode.BLOCK &&
            rule.detection == DetectionMode.BEHAVIOURAL &&
            events >= rule.scrollBudgetEvents
        ) {
            interventionShown = true
            lastBlockAt = now
            flush(now)
            FocusOverlay.bounce(
                this,
                "Not this one",
                "You asked Heed to stop you scrolling ${rule.appLabel}.",
            )
            return
        }

        // Nudging on *cumulative* scrolling in this visit rather than on one unbroken
        // burst. The old threshold asked for ten minutes without a three-second pause,
        // which nobody has ever achieved — reading a single post resets it. It meant the
        // nudge could not fire at all, which is why LinkedIn appeared to do nothing.
        if (rule.mode == FocusMode.NUDGE) {
            val threshold = repo.currentSettings().scrollInterventionMinutes
            if (threshold > 0 && cumulative >= threshold * 60_000L) {
                interventionShown = true
                scope.launch {
                    FocusOverlay.show(
                        service = this@ScrollWatcherService,
                        packageName = pkg,
                        scrollingMinutes = (cumulative / 60_000L).toInt(),
                        trigger = repo.lastAttributedTriggerFor(pkg),
                    )
                }
                return
            }
        }

        if (needsBudget) {
            lastBudgetCheck = now
            scope.launch {
                val scrolled = repo.dao.scrollSecondsSince(pkg, startOfToday())
                if (scrolled < rule.dailyScrollSeconds) return@launch
                interventionShown = true
                lastBlockAt = System.currentTimeMillis()
                FocusOverlay.bounce(
                    this@ScrollWatcherService,
                    "You're out of scrolling in ${rule.appLabel}",
                    "Messages and everything else still work — this is just the feed.",
                )
            }
        }
    }

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

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
        val now = System.currentTimeMillis()
        if (now - lastBlockAt < BLOCK_COOLDOWN_MS) return
        if (CriticalApps.isProtected(pkg)) return

        // Cheap exits first, in the order they are cheap. Almost every event in a normal
        // day dies on one of these three lines without allocating anything or touching
        // the node tree, which is the whole point.
        val repo = repo ?: return
        val rule = repo.cachedRuleFor(pkg) ?: return
        if (rule.detection != DetectionMode.PRECISE || rule.mode != FocusMode.BLOCK) return

        val anchors = KnownSurfaces.forPackage(pkg)
        val taught = repo.cachedSurfacesFor(pkg)
        if (anchors.isEmpty() && taught.isEmpty()) return

        // The node tree is only valid for the length of this callback, so anchors are
        // resolved here rather than handed to a coroutine to read later — a recycled node
        // returns nothing, which is indistinguishable from "the feed is not open".
        val root = rootInActiveWindow
        val hit = anchors.firstOrNull { anchor ->
            val present = when (anchor.match) {
                KnownSurfaces.Match.SOURCE -> SurfaceCapture.sourceHasId(source, anchor.viewId)
                KnownSurfaces.Match.WINDOW, KnownSurfaces.Match.CLICK ->
                    SurfaceCapture.hasAnchor(root, anchor.viewId)
            }
            present && anchor.unless?.let { !SurfaceCapture.hasAnchor(root, it) } ?: true
        }

        // Fingerprinting walks up to four hundred nodes, so it only happens for apps
        // where the user has actually taught a screen and no shipped anchor matched.
        val label = hit?.label ?: run {
            if (taught.isEmpty()) return
            val tokens = SurfaceCapture.fingerprint(root)
            if (tokens.isEmpty()) return
            if (SurfaceMatcher.match(tokens, taught.filter { !it.block }) != null) return
            SurfaceMatcher.match(tokens, taught.filter { it.block })?.label ?: return
        }

        lastBlockAt = now
        interventionShown = true
        bounceOut(label, rule.appLabel)
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

        lastBlockAt = System.currentTimeMillis()
        interventionShown = true
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
        FocusOverlay.bounce(this, "Not $label", "The rest of $appLabel still works.")
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
            val stillThere = anchors.any { a ->
                a.match != KnownSurfaces.Match.SOURCE && SurfaceCapture.hasAnchor(root, a.viewId)
            }
            if (!stillThere) return
            when (escalate) {
                0 -> {
                    escalate = 1
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    main.postDelayed(this, EXIT_RECHECK_MS)
                }
                else -> performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    /** Daily usage limits are checked on entry, before a single scroll happens. */
    private fun checkOnOpen(pkg: String) {
        scope.launch {
            val repo = HeedRepository.get(this@ScrollWatcherService)
            val verdict = FocusEnforcer.from(repo.dao) { repo.isBedtimeNow() }.onAppOpened(pkg)
            if (verdict is FocusEnforcer.Verdict.Block) {
                lastBlockAt = System.currentTimeMillis()
                FocusOverlay.block(this@ScrollWatcherService, verdict.headline, verdict.detail)
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
        scope.launch {
            val repo = HeedRepository.get(this@ScrollWatcherService)
            if (!repo.settings.first().pauseForBanking) return@launch
            flush(System.currentTimeMillis())
            io.github.sebastianyousef.heed.notify.Notifier(this@ScrollWatcherService)
                .screenAccessPaused(pkg)
            runCatching { disableSelf() }
        }
    }

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
    }

    override fun onInterrupt() {
        flush(System.currentTimeMillis())
    }

    override fun onDestroy() {
        connected = false
        ForegroundApp.clear()
        if (instance === this) instance = null
        flush(System.currentTimeMillis())
        scope.cancel()
        super.onDestroy()
    }

    private var lastBlockAt = 0L
    private var lastPreciseCheck = 0L
    private var lastBudgetCheck = 0L

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

        /** How far up from a tapped view to look for the card that owns it. */
        private const val CLICK_ANCESTOR_DEPTH = 6

        /** Long enough for the app to finish animating away from the blocked screen. */
        private const val EXIT_RECHECK_MS = 700L

        @Volatile var connected = false
            private set

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
