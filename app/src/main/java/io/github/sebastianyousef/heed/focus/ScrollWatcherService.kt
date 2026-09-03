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

    /** Whether we have already interrupted during this stretch, so we do it once. */
    private var interventionShown = false

    override fun onServiceConnected() {
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != currentPackage) {
                    flush(now)
                    currentPackage = pkg
                    spanStart = now
                    interventionShown = false
                    checkOnOpen(pkg)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (currentPackage != pkg) {
                    flush(now)
                    currentPackage = pkg
                    spanStart = now
                    interventionShown = false
                }
                recordScroll(now)
                maybeIntervene(pkg, now)
            }
        }
    }

    private fun recordScroll(now: Long) {
        scrollCount++
        // A burst is scrolling with no meaningful pause. Pausing to read breaks it, which
        // is exactly the distinction we care about.
        if (lastScrollAt == 0L || now - lastScrollAt > BURST_GAP_MS) {
            burstStart = now
        }
        longestBurst = maxOf(longestBurst, now - burstStart)
        lastScrollAt = now
    }

    private fun maybeIntervene(pkg: String, now: Long) {
        if (interventionShown) return
        if (now - lastBlockAt < BLOCK_COOLDOWN_MS) return

        val burstMs = longestBurst
        val events = scrollCount
        scope.launch {
            val repo = HeedRepository.get(this@ScrollWatcherService)
            val enforcer = FocusEnforcer.from(repo.dao)

            when (val verdict = enforcer.onScroll(pkg, events, burstMs)) {
                is FocusEnforcer.Verdict.Block -> {
                    interventionShown = true
                    lastBlockAt = System.currentTimeMillis()
                    flush(System.currentTimeMillis())
                    FocusOverlay.block(this@ScrollWatcherService, verdict.headline, verdict.detail)
                }

                is FocusEnforcer.Verdict.Nudge -> {
                    // The global threshold still governs how long is too long; the rule
                    // only says this app is eligible to be nudged at all.
                    val threshold = repo.settings.first().scrollInterventionMinutes
                    if (threshold <= 0 || verdict.minutes < threshold) return@launch
                    interventionShown = true
                    FocusOverlay.show(
                        service = this@ScrollWatcherService,
                        packageName = pkg,
                        scrollingMinutes = verdict.minutes,
                        trigger = repo.lastAttributedTriggerFor(pkg),
                    )
                }

                FocusEnforcer.Verdict.Allow -> Unit
            }
        }
    }

    /** Daily usage limits are checked on entry, before a single scroll happens. */
    private fun checkOnOpen(pkg: String) {
        scope.launch {
            val repo = HeedRepository.get(this@ScrollWatcherService)
            val verdict = FocusEnforcer.from(repo.dao).onAppOpened(pkg)
            if (verdict is FocusEnforcer.Verdict.Block) {
                lastBlockAt = System.currentTimeMillis()
                FocusOverlay.block(this@ScrollWatcherService, verdict.headline, verdict.detail)
            }
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

    override fun onInterrupt() {
        flush(System.currentTimeMillis())
    }

    override fun onDestroy() {
        connected = false
        flush(System.currentTimeMillis())
        scope.cancel()
        super.onDestroy()
    }

    private var lastBlockAt = 0L

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

        @Volatile var connected = false
            private set
    }
}
