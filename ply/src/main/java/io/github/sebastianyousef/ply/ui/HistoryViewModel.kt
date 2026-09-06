package io.github.sebastianyousef.ply.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sebastianyousef.keel.core.Time
import io.github.sebastianyousef.ply.data.PlyRepository
import io.github.sebastianyousef.ply.data.SessionSummary
import io.github.sebastianyousef.ply.train.Load
import io.github.sebastianyousef.ply.train.MuscleVolume
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlyRepository.get(app)

    val history: StateFlow<List<SessionSummary>> = repository.dao.sessionHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unit: StateFlow<Load.Unit> = repository.settings.unit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Unit.KG)

    /** This week's work per muscle, by the rules in [io.github.sebastianyousef.ply.train.Volume]. */
    val weekVolume: StateFlow<List<MuscleVolume>> =
        repository.volumeForWeek(Time.startOfWeekFor(System.currentTimeMillis()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
