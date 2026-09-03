package se.kth.notiapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.kth.notiapp.data.AppPolicy
import se.kth.notiapp.data.AppPolicyRecord
import se.kth.notiapp.data.Decision
import se.kth.notiapp.data.DigestRecord
import se.kth.notiapp.data.Feedback
import se.kth.notiapp.data.NotiRepository
import se.kth.notiapp.data.NotificationRecord
import se.kth.notiapp.data.Settings
import se.kth.notiapp.digest.DigestWorker

enum class InboxTab(val label: String) {
    NEEDED("Needed"),
    FILTERED("Filtered"),
    ALL("Everything"),
}

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NotiRepository.get(app)

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

    val settings: StateFlow<Settings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

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

    fun resetModel() = viewModelScope.launch {
        repo.resetModel()
        _modelStats.value = repo.modelStats()
    }
}
