package io.github.sebastianyousef.ply

import android.app.Application
import io.github.sebastianyousef.ply.data.ExerciseSeed
import io.github.sebastianyousef.ply.data.PlyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlyApplication : Application() {

    /**
     * A scope with the lifetime of the process, owned here rather than borrowed from
     * whichever component happens to start first. See [PlyRepository] for what borrowing
     * one cost the last time.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val repository = PlyRepository.get(this@PlyApplication)
            ExerciseSeed.seedIfEmpty(this@PlyApplication, repository.dao)
        }
    }
}
