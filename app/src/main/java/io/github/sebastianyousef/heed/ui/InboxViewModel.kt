package io.github.sebastianyousef.heed.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.sebastianyousef.heed.data.AppPolicy
import io.github.sebastianyousef.heed.data.AppPolicyRecord
import io.github.sebastianyousef.heed.data.Decision
import io.github.sebastianyousef.heed.data.DigestRecord
import io.github.sebastianyousef.heed.data.Feedback
import io.github.sebastianyousef.heed.data.LiveChannelRecord
import io.github.sebastianyousef.heed.data.HeedRepository
import io.github.sebastianyousef.heed.data.NotificationRecord
import io.github.sebastianyousef.heed.data.Settings
import io.github.sebastianyousef.heed.digest.DigestWorker
import io.github.sebastianyousef.heed.export.Exporter
import io.github.sebastianyousef.heed.export.RedactionLevel

/** One calendar day's screen time, for the chart. */
data class DayTotal(val startOfDay: Long, val totalMs: Long)

/**
 * What the Attention screen is currently showing.
 *
 * A day index of 6 is today and 0 is six days ago; null means the whole week. Keeping
 * this as one value rather than a pair of booleans is what lets the chart, the headline
 * and the app list all be driven from a single tap on a bar.
 */
data class UsageRange(val dayIndex: Int?) {
    val isWeek: Boolean get() = dayIndex == null
}

enum class InboxTab(val label: String) {
    NEEDED("Needed"),
    FILTERED("Filtered"),
    ALL("Everything"),
}

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HeedRepository.get(app)

    private val _tab = MutableStateFlow(InboxTab.NEEDED)
    val tab: StateFlow<InboxTab> = _tab

    val records: StateFlow<List<NotificationRecord>> = _tab
        .flatMapLatest { tab ->
            when (tab) {
                InboxTab.NEEDED -> repo.dao.observeByDecision(Decision.ALERTED)
                InboxTab.FILTERED -> repo.dao.observeByDecision(Decision.SUPPRESSED)
                InboxTab.ALL -> repo.dao.observeAll()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCount: StateFlow<Int> = repo.dao.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val digests: StateFlow<List<DigestRecord>> = repo.dao.observeDigests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val policies: StateFlow<List<AppPolicyRecord>> = repo.dao.observePolicies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Channels recognised as live displays — step counters, progress bars, timers. */
    val liveChannels: StateFlow<List<LiveChannelRecord>> = repo.dao.observeLiveChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** False when Android has unbound the listener and Heed is seeing nothing. */
    val listenerConnected: StateFlow<Boolean> = repo.listenerConnected

    val settings: StateFlow<Settings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    private val exporter = Exporter(app)

    /** Set when an export is ready to hand to the share sheet; cleared once consumed. */
    private val _exportReady = MutableStateFlow<Pair<Uri, RedactionLevel>?>(null)
    val exportReady: StateFlow<Pair<Uri, RedactionLevel>?> = _exportReady

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    val scrubbedCount: StateFlow<Int> = repo.dao.observeScrubbedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val readableCount: StateFlow<Int> = repo.dao.observeReadableCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * The join across both halves of the app: what each app's interruptions cost you.
     *
     * Both sides are aggregated by SQLite and combined here as two short lists — one row
     * per app rather than four thousand rows of raw history. The previous version loaded
     * every notification and every session on each database change and joined them in
     * Kotlin, which is what made opening the Attention tab feel slow and kept the whole
     * corpus resident.
     */
    val attention: StateFlow<List<io.github.sebastianyousef.heed.usage.AttentionStat>> =
        combine(
            repo.dao.observeAttention(io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(29), io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(0)),
            repo.dao.observeAlertCounts(io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(29)),
        ) { usage, alerts ->
            val byPackage = alerts.associateBy { it.packageName }
            usage.map { row ->
                val alert = byPackage[row.packageName]
                io.github.sebastianyousef.heed.usage.AttentionStat(
                    packageName = row.packageName,
                    appLabel = row.appLabel,
                    alerts = alert?.alerts ?: 0,
                    openedFromAlert = row.openedFromAlert,
                    msFromAlerts = row.msFromAlerts,
                    totalMs = row.totalMs,
                    markedNoise = alert?.markedNoise ?: 0,
                    todayMs = row.todayMs,
                    launchesToday = row.launchesToday,
                )
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The last seven days, one bucket per calendar day, oldest first.
     *
     * A single day's figure has no meaning on its own — nobody knows whether four hours
     * is a lot for them. Seven of them next to each other does, and it is the only view
     * that shows whether a rule you set on Tuesday actually changed anything.
     */
    val usageDays: StateFlow<List<DayTotal>> =
        repo.dao.observeDayTotals(io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(6)).map { rows ->
            val origin = io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(6)
            val byIndex = rows.associate { it.dayIndex to it.totalMs }
            (0..6).map { i ->
                DayTotal(startOfDay = origin + i * io.github.sebastianyousef.heed.core.Time.DAY_MS, totalMs = byIndex[i] ?: 0L)
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _range = MutableStateFlow(UsageRange(6))
    val range: StateFlow<UsageRange> = _range

    fun selectRange(range: UsageRange) { _range.value = range }

    /**
     * The apps in whatever the chart currently has selected.
     *
     * Driven straight off the selection so that tapping Tuesday re-queries for Tuesday
     * rather than filtering a list already in memory — which is both less code and the
     * difference between holding one day of sessions and holding thirty.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val rangeApps: StateFlow<List<io.github.sebastianyousef.heed.data.AppUsageRow>> =
        _range.flatMapLatest { r ->
            val from = if (r.isWeek) io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(6) else io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(6 - r.dayIndex!!)
            val to = if (r.isWeek) Long.MAX_VALUE else from + io.github.sebastianyousef.heed.core.Time.DAY_MS
            repo.dao.observeUsageBetween(from, to)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-app totals over the same week, for the "last 7 days" view of the list. */
    val weekByApp: StateFlow<List<io.github.sebastianyousef.heed.data.AppUsageRow>> =
        repo.dao.observeAttention(io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(6), io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(0)).map { rows ->
            rows.map {
                io.github.sebastianyousef.heed.data.AppUsageRow(
                    packageName = it.packageName,
                    appLabel = it.appLabel,
                    totalMs = it.totalMs,
                    launches = it.launches,
                )
            }.sortedByDescending { it.totalMs }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One app's week, as its own series.
     *
     * A per-app chart is the thing that makes a limit feel worth setting: "Snapchat, two
     * hours yesterday" lands in a way that a share of a total never does. Queried per app
     * rather than sliced out of a whole-phone list so the screen holds one app's data and
     * not everything.
     */
    fun appDays(pkg: String): kotlinx.coroutines.flow.Flow<List<DayTotal>> {
        val origin = io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(6)
        return repo.dao.observeDayTotalsForApp(pkg, origin).map { rows ->
            val byIndex = rows.associate { it.dayIndex to it.totalMs }
            (0..6).map { DayTotal(origin + it * io.github.sebastianyousef.heed.core.Time.DAY_MS, byIndex[it] ?: 0L) }
        }.flowOn(Dispatchers.Default)
    }

    fun appOpens(pkg: String): kotlinx.coroutines.flow.Flow<List<DayTotal>> {
        val origin = io.github.sebastianyousef.heed.core.Time.startOfDaysAgo(6)
        return repo.dao.observeOpensForApp(pkg, origin).map { rows ->
            val byIndex = rows.associate { it.dayIndex to it.totalMs }
            (0..6).map { DayTotal(origin + it * io.github.sebastianyousef.heed.core.Time.DAY_MS, byIndex[it] ?: 0L) }
        }.flowOn(Dispatchers.Default)
    }

    /** Turn one of an app's shipped carve-outs on or off. */
    fun setException(rule: io.github.sebastianyousef.heed.focus.FocusRule, key: String, on: Boolean) =
        setFocusRule(rule.withException(key, on))

    fun setGrayscale(pkg: String, label: String, on: Boolean) = viewModelScope.launch {
        val existing = repo.dao.focusRuleFor(pkg)
            ?: io.github.sebastianyousef.heed.focus.FocusRule(pkg, label)
        repo.dao.upsertFocusRule(existing.copy(grayscale = on))
        repo.syncAttentionService()
    }

    fun setPauseForBanking(on: Boolean) = viewModelScope.launch {
        repo.settingsStore.setPauseForBanking(on)
        repo.syncAttentionService()
    }

    fun setGrayscaleAtBedtime(on: Boolean) = viewModelScope.launch {
        repo.settingsStore.setGrayscaleAtBedtime(on)
        repo.syncAttentionService()
    }

    private val _modelStats = MutableStateFlow(0 to 0f)
    val modelStats: StateFlow<Pair<Int, Float>> = _modelStats

    init {
        viewModelScope.launch {
            repo.ensureModelLoaded()
            _modelStats.value = repo.modelStats()
        }
    }

    fun observe(id: Long) = repo.dao.observeOne(id)
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectTab(tab: InboxTab) { _tab.value = tab }

    fun mark(id: Long, feedback: Feedback) = viewModelScope.launch {
        repo.recordFeedback(id, feedback)
        _modelStats.value = repo.modelStats()
    }

    fun markSeen(id: Long) = viewModelScope.launch { repo.dao.markSeen(id) }

    /**
     * Erase one notification from the app entirely.
     *
     * For the ones you would rather were not sitting in a list at all — a medical result,
     * a message from a person you are not out to, a code you would rather no screenshot
     * ever catches. The retention scrub already removes the words on a schedule, but a
     * schedule is not much comfort in the hour after something arrives, and the fact that
     * a notification came at all can be the sensitive part.
     */
    fun forget(id: Long) = viewModelScope.launch { repo.forget(id) }

    fun setPolicy(pkg: String, label: String, policy: AppPolicy) = viewModelScope.launch {
        repo.setPolicy(pkg, label, policy)
    }

    fun setSourceSilenced(pkg: String, silenced: Boolean) = viewModelScope.launch {
        repo.setSourceSilenced(pkg, silenced)
    }

    fun setThreshold(v: Float) = viewModelScope.launch { repo.settingsStore.setThreshold(v) }
    fun setHoldWindow(ms: Long) = viewModelScope.launch { repo.settingsStore.setHoldWindow(ms) }
    fun setQuietStrict(v: Boolean) = viewModelScope.launch { repo.settingsStore.setQuietStrict(v) }
    fun setQuietHours(start: Int, end: Int) = viewModelScope.launch {
        repo.settingsStore.setQuietHours(start, end)
    }

    fun setDigestInterval(hours: Int) = viewModelScope.launch {
        repo.settingsStore.setDigestInterval(hours)
        DigestWorker.schedule(getApplication(), hours)
    }

    fun completeOnboarding() = viewModelScope.launch {
        repo.settingsStore.setOnboardingComplete(true)
    }

    val focusRules: StateFlow<Map<String, io.github.sebastianyousef.heed.focus.FocusRule>> =
        repo.dao.observeFocusRules()
            .map { rules -> rules.associateBy { it.packageName } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val surfaces: StateFlow<Map<String, List<io.github.sebastianyousef.heed.focus.LearnedSurface>>> =
        repo.dao.observeSurfaces()
            .map { list -> list.groupBy { it.packageName } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _strict = MutableStateFlow(false)
    val strict: StateFlow<Boolean> = _strict

    fun setFocusRule(rule: io.github.sebastianyousef.heed.focus.FocusRule) = viewModelScope.launch {
        repo.syncAttentionService()
        // Strict mode lets you tighten a rule at any time; loosening waits.
        if (repo.strictActive()) {
            val existing = repo.dao.focusRuleFor(rule.packageName)
            if (existing != null && loosens(existing, rule)) return@launch
        }
        repo.dao.upsertFocusRule(rule)
        // Nothing to seed. Precise matching reads the shipped anchors directly, and
        // copying them into the database as if the user had taught them is what left
        // Snapchat listing "Spotlight, Discover, Spotlight" — three rows, one of them
        // naming a view that no longer exists. The screen states what it already knows
        // from the anchors themselves.
    }

    private fun loosens(
        old: io.github.sebastianyousef.heed.focus.FocusRule,
        new: io.github.sebastianyousef.heed.focus.FocusRule,
    ): Boolean {
        fun limitLoosened(o: Int, n: Int) = o > 0 && (n == 0 || n > o)
        return new.mode.ordinal < old.mode.ordinal ||
            limitLoosened(old.dailyScrollSeconds, new.dailyScrollSeconds) ||
            limitLoosened(old.dailyUsageSeconds, new.dailyUsageSeconds) ||
            limitLoosened(old.dailyLaunchLimit, new.dailyLaunchLimit) ||
            // More posts between seams, or a shorter pause at each one, are both ways of
            // asking for less friction — which is the thing strict mode exists to make
            // you wait for.
            limitLoosened(old.scrollBreakEvents, new.scrollBreakEvents) ||
            (old.scrollBreakEvents > 0 && new.breakSeconds < old.breakSeconds)
    }

    fun armSurfaceCapture() {
        io.github.sebastianyousef.heed.focus.SurfaceCapture.arm()
    }

    fun setSurfaceBlock(surface: io.github.sebastianyousef.heed.focus.LearnedSurface, block: Boolean) =
        viewModelScope.launch {
            repo.dao.deleteSurface(surface.id)
            repo.dao.insertSurface(surface.copy(id = 0, block = block))
        }

    fun deleteSurface(id: Long) = viewModelScope.launch { repo.dao.deleteSurface(id) }

    /**
     * Turns off Heed's own accessibility service.
     *
     * Here because banking apps refuse to run while any accessibility service is enabled,
     * and the alternative — telling people to go and find it in system settings — is how
     * you get uninstalled by someone who just wanted to pay for lunch. Android has no way
     * to switch it back on from inside an app, so this is deliberately one-way, and the
     * UI says so.
     */
    fun pauseScreenAccess() {
        io.github.sebastianyousef.heed.focus.ScrollWatcherService.pause()
    }

    fun setBedtime(enabled: Boolean, start: Int, end: Int) = viewModelScope.launch {
        repo.settingsStore.setBedtime(enabled, start, end)
    }

    /** Locks rules for [days]. Cannot be shortened once set — that is the point. */
    fun enableStrict(days: Int) = viewModelScope.launch {
        val until = System.currentTimeMillis() + days * io.github.sebastianyousef.heed.core.Time.DAY_MS
        if (until > repo.settings.first().strictUntil) {
            repo.settingsStore.setStrictUntil(until)
        }
        _strict.value = repo.strictActive()
    }

    fun refreshStrict() = viewModelScope.launch { _strict.value = repo.strictActive() }

    private val _usageRefreshing = MutableStateFlow(false)
    val usageRefreshing: StateFlow<Boolean> = _usageRefreshing

    /**
     * Pull in sessions right now rather than waiting for the periodic worker. Opening the
     * screen and seeing nothing — because the schedule has not come round yet — reads as
     * the feature being broken.
     */
    fun refreshUsage() = viewModelScope.launch {
        _usageRefreshing.value = true
        try {
            io.github.sebastianyousef.heed.usage.UsageTracker(getApplication(), repo).ingest()
            repo.seedPresetsFromHistory()
        } finally {
            _usageRefreshing.value = false
        }
    }

    fun setScrollIntervention(minutes: Int) = viewModelScope.launch {
        repo.settingsStore.setScrollInterventionMinutes(minutes)
    }

    fun setContentRetention(days: Int) = viewModelScope.launch {
        repo.settingsStore.setContentRetentionDays(days)
    }

    fun setRecordRetention(days: Int) = viewModelScope.launch {
        repo.settingsStore.setRecordRetentionDays(days)
    }

    fun scrubNow() = viewModelScope.launch {
        repo.scrubOldContent()
        repo.pruneOldRecords()
    }

    fun export(level: RedactionLevel) = viewModelScope.launch {
        _exporting.value = true
        try {
            _exportReady.value = exporter.export(level) to level
        } finally {
            _exporting.value = false
        }
    }

    fun exportConsumed() { _exportReady.value = null }

    fun shareIntent(uri: Uri, level: RedactionLevel) = exporter.shareIntent(uri, level)

    fun forgetLiveChannel(pkg: String, channelId: String) = viewModelScope.launch {
        repo.unmarkLiveChannel(pkg, channelId)
    }

    private val _retrained = MutableStateFlow<Int?>(null)
    val retrained: StateFlow<Int?> = _retrained

    fun retrain() = viewModelScope.launch {
        _retrained.value = repo.retrainFromHistory()
        _modelStats.value = repo.modelStats()
    }

    fun retrainConsumed() { _retrained.value = null }

    fun resetModel() = viewModelScope.launch {
        repo.resetModel()
        _modelStats.value = repo.modelStats()
    }
}
