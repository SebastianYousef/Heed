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
        // Strict mode lets you tighten a rule at any time; loosening waits.
        if (repo.strictActive()) {
            val existing = repo.dao.focusRuleFor(rule.packageName)
            if (existing != null && loosens(existing, rule)) return@launch
        }
        repo.dao.upsertFocusRule(rule)
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
