package io.github.sebastianyousef.ply.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sebastianyousef.ply.data.Exercise
import io.github.sebastianyousef.ply.data.PlyRepository
import io.github.sebastianyousef.ply.data.PlannedRow
import io.github.sebastianyousef.ply.data.Routine
import io.github.sebastianyousef.ply.data.RoutineItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RoutineViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlyRepository.get(app)

    val routines: StateFlow<List<Routine>> = repository.dao.routines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val editing = MutableStateFlow<Long?>(null)
    val editingId: StateFlow<Long?> = editing.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<PlannedRow>> = editing
        .flatMapLatest { id ->
            id?.let { repository.dao.plannedFor(it) } ?: flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun edit(routineId: Long?) { editing.value = routineId }

    fun create(name: String, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val id = repository.dao.insertRoutine(
            Routine(name = name.ifBlank { "New routine" }, position = routines.value.size)
        )
        editing.value = id
        onCreated(id)
    }

    fun rename(routine: Routine, name: String) = viewModelScope.launch {
        repository.dao.updateRoutine(routine.copy(name = name))
    }

    fun delete(routine: Routine) = viewModelScope.launch {
        // The items go with it through the foreign key's cascade. Sessions that were
        // started from it keep their routineId pointing at nothing, which is deliberate:
        // a session is a record of what happened and must not be edited by deleting a plan.
        repository.dao.deleteRoutine(routine)
        if (editing.value == routine.id) editing.value = null
    }

    fun add(exercise: Exercise) = viewModelScope.launch {
        val routineId = editing.value ?: return@launch
        repository.dao.upsertRoutineItem(
            RoutineItem(
                routineId = routineId,
                exerciseId = exercise.id,
                position = items.value.size,
                // Three sets of eight is the shape most programs are written in, and it is
                // a starting point rather than a claim — every target is editable and every
                // one of them is optional.
                targetSets = 3,
                targetReps = 8,
            )
        )
    }

    fun update(item: RoutineItem) = viewModelScope.launch {
        repository.dao.upsertRoutineItem(item)
    }

    fun remove(item: RoutineItem) = viewModelScope.launch {
        repository.dao.deleteRoutineItem(item.id)
    }

    /**
     * Moves an item up or down, rewriting only the two positions that changed.
     *
     * Positions are rewritten rather than recomputed from the list order, because the list
     * on screen is a snapshot of a flow and reordering from it would race a concurrent
     * edit into writing the wrong order for every row rather than for one.
     */
    fun move(item: RoutineItem, up: Boolean) = viewModelScope.launch {
        val ordered = items.value.map { it.item }
        val index = ordered.indexOfFirst { it.id == item.id }
        val other = ordered.getOrNull(if (up) index - 1 else index + 1) ?: return@launch
        repository.dao.upsertRoutineItem(item.copy(position = other.position))
        repository.dao.upsertRoutineItem(other.copy(position = item.position))
    }
}
