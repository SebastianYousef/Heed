package io.github.sebastianyousef.ply.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sebastianyousef.ply.data.PlyRepository
import io.github.sebastianyousef.ply.train.Load
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlyRepository.get(app)
    private val settings = repository.settings

    val unit: StateFlow<Load.Unit> = settings.unit.state(Load.Unit.KG)
    val increment: StateFlow<Int> = settings.increment.state(2_500)
    val rest: StateFlow<Int> = settings.defaultRestSeconds.state(150)
    val autoRest: StateFlow<Boolean> = settings.restAutoStart.state(true)
    val goal: StateFlow<Int> = settings.stepGoal.state(8_000)

    fun setUnit(value: Load.Unit) = viewModelScope.launch { settings.setUnit(value) }
    fun setIncrement(grams: Int) = viewModelScope.launch { settings.setIncrement(grams) }
    fun setRest(seconds: Int) = viewModelScope.launch { settings.setDefaultRest(seconds) }
    fun setAutoRest(on: Boolean) = viewModelScope.launch { settings.setRestAutoStart(on) }
    fun setGoal(steps: Int) = viewModelScope.launch { settings.setStepGoal(steps) }

    /**
     * Builds the export and hands back an intent to share it.
     *
     * The document is built off the main thread and the callback fires on it, because the
     * caller is a composable that needs to start an activity — and because an export of a
     * year of training is not a thing to serialise while the UI waits.
     */
    fun export(onReady: (android.content.Intent) -> Unit) = viewModelScope.launch {
        val document = io.github.sebastianyousef.ply.data.Export.build(repository.dao)
        onReady(io.github.sebastianyousef.ply.data.Export.share(getApplication(), document))
    }

    val bodyweight: StateFlow<Int?> = repository.dao.latestBodyweight()
        .map { it?.grams }
        .state(null)

    fun setBodyweight(grams: Int) = viewModelScope.launch {
        repository.dao.upsertBodyweight(
            io.github.sebastianyousef.ply.data.Bodyweight(
                day = io.github.sebastianyousef.keel.core.Time.startOfToday(),
                grams = grams,
            )
        )
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}
