package io.github.sebastianyousef.ply.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sebastianyousef.ply.data.Exercise
import io.github.sebastianyousef.ply.data.PlyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** The exercise library, held once rather than re-queried by each screen that wants it. */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = PlyRepository.get(app).dao

    val exercises: StateFlow<List<Exercise>> = dao.exercises()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<Exercise>> = dao.recentExercises()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
