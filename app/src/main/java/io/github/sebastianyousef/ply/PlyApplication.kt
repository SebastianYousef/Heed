package io.github.sebastianyousef.ply

import android.app.Application
import io.github.sebastianyousef.ply.data.ExerciseSeed
import io.github.sebastianyousef.ply.data.PlyRepository
import io.github.sebastianyousef.ply.move.StepSensor
import io.github.sebastianyousef.ply.move.StepWorker
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

        // Without this, step collection began only after a reboot or an app update — the
        // boot receiver was the sole caller, so a fresh install counted nothing at all
        // until the phone was next restarted, and it would have looked exactly like a
        // sensor that does not work. Enqueued as unique work with KEEP, so calling it on
        // every launch does not reset the period and postpone the next read forever.
        if (StepSensor.permitted(this)) StepWorker.schedule(this)
    }
}
