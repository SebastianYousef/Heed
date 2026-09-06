package io.github.sebastianyousef.ply.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sebastianyousef.keel.core.Time
import io.github.sebastianyousef.ply.data.PlyRepository
import io.github.sebastianyousef.ply.move.StepSensor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.format.TextStyle
import java.util.Locale

/** One day of the week strip. */
data class DayBar(val day: Long, val steps: Int, val label: String, val initial: String)

class StepsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlyRepository.get(app)

    private val _permitted = MutableStateFlow(StepSensor.permitted(app))
    val permitted: StateFlow<Boolean> = _permitted.asStateFlow()

    val goal: StateFlow<Int> = repository.settings.stepGoal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * The last seven days, including the ones with no rows.
     *
     * Filled in here rather than in SQL, because a day with no steps has no bucket to
     * group and would otherwise simply be missing from the chart — which reads as the week
     * being six days long rather than as a day spent sitting down.
     */
    val week: StateFlow<List<DayBar>> = repository.dao
        .stepDays(Time.startOfDaysAgo(6), Time.startOfToday())
        .map { rows ->
            val byDay = rows.associate { it.day to it.steps }
            (6 downTo 0).map { back ->
                val day = Time.startOfDaysAgo(back)
                val date = Time.dateOf(day)
                DayBar(
                    day = day,
                    steps = byDay[day] ?: 0,
                    label = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    initial = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val today: StateFlow<Int> = week
        .map { it.lastOrNull()?.steps ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun refreshPermission() {
        _permitted.value = StepSensor.permitted(getApplication())
    }
}
