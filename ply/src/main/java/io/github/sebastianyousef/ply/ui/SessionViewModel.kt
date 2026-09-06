package io.github.sebastianyousef.ply.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sebastianyousef.ply.data.Exercise
import io.github.sebastianyousef.ply.data.PlyRepository
import io.github.sebastianyousef.ply.data.PlannedRow
import io.github.sebastianyousef.ply.data.RoutineItem
import io.github.sebastianyousef.ply.data.Session
import io.github.sebastianyousef.ply.data.SetKind
import io.github.sebastianyousef.ply.data.SetWithExercise
import io.github.sebastianyousef.ply.train.Load
import io.github.sebastianyousef.ply.train.RecordKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the logging screen is currently offering to record.
 *
 * Held as one object rather than as four fields because the whole thing is replaced
 * together whenever the exercise changes, and four separate states is how a screen ends up
 * showing last Tuesday's weight beside this exercise's rep count.
 */
data class Pending(
    val exercise: Exercise? = null,
    val weightGrams: Int = 0,
    val reps: Int = 8,
    val kind: SetKind = SetKind.WORKING,
    /** What this exercise was last done at, for the line under the steppers. */
    val previous: String? = null,
    /** The routine's target for this exercise, where there is one. */
    val target: RoutineItem? = null,
)

/** A record just broken, shown briefly and then gone. */
data class RecordFlash(val setId: Long, val records: Set<RecordKind>)

class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlyRepository.get(app)

    val session: StateFlow<Session?> =
        repository.openSession.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val sets: StateFlow<List<SetWithExercise>> = session
        .flatMapLatest { open -> open?.let { repository.sets(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _pending = MutableStateFlow(Pending())
    val pending: StateFlow<Pending> = _pending.asStateFlow()

    private val _flash = MutableStateFlow<RecordFlash?>(null)
    val flash: StateFlow<RecordFlash?> = _flash.asStateFlow()

    val unit: StateFlow<Load.Unit> = repository.settings.unit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Unit.KG)

    val increment: StateFlow<Int> = repository.settings.increment
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2_500)

    /**
     * The routine this session was started from, if any, with its exercises resolved.
     *
     * Empty for a freeform session, which is the common case and costs nothing to
     * represent — the screen simply has no plan strip.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val plan: StateFlow<List<PlannedRow>> = session
        .flatMapLatest { open ->
            open?.routineId?.let { repository.dao.plannedFor(it) } ?: flowOf(emptyList<PlannedRow>())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sets already logged for whatever exercise is on screen, in order. */
    val setsForCurrent: StateFlow<List<SetWithExercise>> =
        combine(sets, _pending) { all, current ->
            all.filter { it.exerciseId == current.exercise?.id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun start(routineId: Long? = null) = viewModelScope.launch {
        repository.startSession(routineId)
    }

    fun end() = viewModelScope.launch { repository.endSession() }

    fun repeat(sessionId: Long) = viewModelScope.launch { repository.repeatSession(sessionId) }

    /**
     * Puts an exercise on the screen, with the steppers already holding a plausible answer.
     *
     * This is the whole trick. The cost of logging a set is the cost of correcting the
     * prefill, so the prefill is chosen to be right as often as possible — and the order
     * below is that judgement, from most specific to least:
     *
     *  1. What you have already done for this exercise *in this session*. You are almost
     *     always repeating the last set, so this is right more often than anything else.
     *  2. The routine's target, if this session came from one.
     *  3. What you did last time you trained this exercise.
     *  4. An empty bar and eight reps, which is only reached for an exercise you have
     *     never done and no routine asked for.
     */
    fun select(exercise: Exercise) = viewModelScope.launch {
        val open = session.value
        val here = sets.value.lastOrNull { it.exerciseId == exercise.id && it.kind == SetKind.WORKING }
        val last = open?.let { repository.lastWorkingSet(exercise.id, it.id) }
        val target = plan.value.firstOrNull { it.item.exerciseId == exercise.id }?.item

        // A record announced against the previous exercise has nothing to do with this
        // one, and leaving it set would attach it to whichever row happened to share an id.
        _flash.value = null
        _pending.value = Pending(
            exercise = exercise,
            // Most specific first. What you have already done here beats the plan, because
            // the plan was written before you found out what today felt like.
            weightGrams = here?.weightGrams ?: target?.targetWeightGrams ?: last?.weightGrams ?: 0,
            reps = here?.reps ?: target?.targetReps ?: last?.reps ?: 8,
            previous = last?.let { "${Load.format(it.weightGrams, unit.value)} × ${it.reps}" },
            target = target,
        )
    }

    /** Selects a planned exercise from the plan strip, resolving the row to its exercise. */
    fun selectPlanned(row: PlannedRow) = viewModelScope.launch {
        repository.dao.exercise(row.item.exerciseId)?.let { select(it) }
    }

    fun stepWeight(up: Boolean) = _pending.update {
        it.copy(weightGrams = Load.step(it.weightGrams, increment.value, up))
    }

    fun stepReps(up: Boolean) = _pending.update {
        it.copy(reps = (it.reps + if (up) 1 else -1).coerceIn(1, 100))
    }

    fun setWeight(grams: Int) = _pending.update { it.copy(weightGrams = grams.coerceAtLeast(0)) }

    fun setKind(kind: SetKind) = _pending.update { it.copy(kind = kind) }

    /**
     * Records the set on screen. One tap, and this is the only thing that tap does.
     *
     * The RPE is not asked for here and the rest timer is not confirmed here, because both
     * would sit between the person and the record. The timer starts itself; the RPE is
     * offered on the row afterwards, where ignoring it costs nothing.
     *
     * @param onRest called with the rest to start, and **not called at all** when the user
     *        has turned automatic rest off. Deciding that here rather than in the screen is
     *        deliberate: the screen would have to read the setting to know, and a caller
     *        that starts a timer the setting says not to is exactly how a switch comes to
     *        be displayed, stored, and ignored.
     */
    fun log(onRest: (Int) -> Unit = {}) = viewModelScope.launch {
        val open = session.value ?: repository.startSession()
        val current = _pending.value
        val exercise = current.exercise ?: return@launch

        val logged = repository.logSet(
            sessionId = open.id,
            exerciseId = exercise.id,
            weightGrams = current.weightGrams,
            reps = current.reps,
            kind = current.kind,
        )
        if (logged.records.isNotEmpty()) {
            _flash.value = RecordFlash(logged.set.id, logged.records)
        }
        // Back to a working set: a warm-up is a thing you do once on the way up, and
        // leaving the switch on is how the first three working sets get logged as warm-ups.
        _pending.update { it.copy(kind = SetKind.WORKING) }

        if (repository.settings.restAutoStart.first()) {
            onRest(exercise.restSeconds ?: repository.settings.defaultRestSeconds.first())
        }
    }

    fun rate(set: SetWithExercise, rpe: Float?) = viewModelScope.launch {
        repository.dao.set(set.id)?.let { existing ->
            repository.editSet(existing, existing.weightGrams, existing.reps, existing.kind, rpe)
        }
    }

    fun delete(set: SetWithExercise) = viewModelScope.launch { repository.deleteSet(set.id) }

    private fun MutableStateFlow<Pending>.update(block: (Pending) -> Pending) {
        value = block(value)
    }
}
