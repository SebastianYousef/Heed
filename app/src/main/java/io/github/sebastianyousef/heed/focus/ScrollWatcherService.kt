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
        val burstMinutes = longestBurst / 60_000.0
        scope.launch {
            val repo = HeedRepository.get(this@ScrollWatcherService)
            val minutes = repo.settings.first().scrollInterventionMinutes
            if (minutes <= 0 || burstMinutes < minutes) return@launch
            interventionShown = true
            FocusOverlay.show(
                service = this@ScrollWatcherService,
                packageName = pkg,
                scrollingMinutes = burstMinutes.toInt(),
                trigger = repo.lastAttributedTriggerFor(pkg),
            )
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

    companion object {
        /** A pause longer than this ends a scroll burst. */
        private const val BURST_GAP_MS = 3_000L

        @Volatile var connected = false
            private set
    }
}
