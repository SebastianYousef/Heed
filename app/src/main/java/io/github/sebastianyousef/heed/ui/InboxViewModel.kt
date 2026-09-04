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

private fun startOfDaysAgo(days: Int): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
    add(java.util.Calendar.DAY_OF_YEAR, -days)
}.timeInMillis

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

    /** The join across both halves of the app: what each app's interruptions cost you. */
    val attention: StateFlow<List<io.github.sebastianyousef.heed.usage.AttentionStat>> =
        combine(repo.dao.observeAll(2000), repo.dao.observeSessions(2000)) { notifications, sessions ->
            io.github.sebastianyousef.heed.usage.AttentionStats.build(notifications, sessions)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The last seven days, one bucket per calendar day, oldest first.
     *
     * A single day's figure has no meaning on its own — nobody knows whether four hours
     * is a lot for them. Seven of them next to each other does, and it is the only view
     * that shows whether a rule you set on Tuesday actually changed anything.
     */
    val usageDays: StateFlow<List<DayTotal>> =
        repo.dao.observeSessionsSince(startOfDaysAgo(6)).map { sessions ->
            (6 downTo 0).map { back ->
                val from = startOfDaysAgo(back)
                val to = startOfDaysAgo(back - 1)
                DayTotal(
                    startOfDay = from,
                    totalMs = sessions.filter { it.startedAt in from until to }
                        .sumOf { it.durationMs },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-app totals over the same week, for the "last 7 days" view of the list. */
    val weekByApp: StateFlow<List<io.github.sebastianyousef.heed.data.AppUsageRow>> =
        repo.dao.observeSessionsSince(startOfDaysAgo(6)).map { sessions ->
            sessions.groupBy { it.packageName }
                .map { (pkg, rows) ->
                    io.github.sebastianyousef.heed.data.AppUsageRow(
                        packageName = pkg,
                        appLabel = rows.first().appLabel,
                        totalMs = rows.sumOf { it.durationMs },
                        launches = rows.size,
                    )
                }
                .sortedByDescending { it.totalMs }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        // Turning on precise matching should just work for Snapchat and friends, rather
        // than presenting an empty list and an instruction to go teach it something.
        if (rule.detection == io.github.sebastianyousef.heed.focus.DetectionMode.PRECISE) {
            repo.seedKnownSurfaces(rule.packageName)
        }
    }

    private fun loosens(
        old: io.github.sebastianyousef.heed.focus.FocusRule,
        new: io.github.sebastianyousef.heed.focus.FocusRule,
    ): Boolean {
        fun limitLoosened(o: Int, n: Int) = o > 0 && (n == 0 || n > o)
        return new.mode.ordinal < old.mode.ordinal ||
            limitLoosened(old.dailyScrollSeconds, new.dailyScrollSeconds) ||
            limitLoosened(old.dailyUsageSeconds, new.dailyUsageSeconds) ||
            limitLoosened(old.dailyLaunchLimit, new.dailyLaunchLimit)
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
        val until = System.currentTimeMillis() + days * 86_400_000L
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

    fun resetModel() = viewModelScope.launch {
        repo.resetModel()
        _modelStats.value = repo.modelStats()
    }
}
