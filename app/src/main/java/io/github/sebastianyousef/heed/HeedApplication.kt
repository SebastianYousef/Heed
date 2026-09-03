package io.github.sebastianyousef.heed

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.sebastianyousef.heed.data.HeedRepository
import io.github.sebastianyousef.heed.data.RetentionWorker
import io.github.sebastianyousef.heed.usage.UsageWorker
import io.github.sebastianyousef.heed.capture.ListenerWatchdogWorker
import io.github.sebastianyousef.heed.digest.DigestWorker
import io.github.sebastianyousef.heed.notify.Notifier

class HeedApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifier(this).ensureChannels()
        scope.launch {
            val settings = HeedRepository.get(this@HeedApplication).settings.first()
            DigestWorker.schedule(this@HeedApplication, settings.digestIntervalHours)
            ListenerWatchdogWorker.schedule(this@HeedApplication)
            RetentionWorker.schedule(this@HeedApplication)
            UsageWorker.schedule(this@HeedApplication)
            // Deterministic: presets should exist before any screen is opened, not as a
            // side effect of visiting one.
            HeedRepository.get(this@HeedApplication).seedPresetsFromHistory()
        }
    }
}
