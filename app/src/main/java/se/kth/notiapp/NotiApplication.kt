package se.kth.notiapp

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import se.kth.notiapp.data.NotiRepository
import se.kth.notiapp.digest.DigestWorker
import se.kth.notiapp.notify.Notifier

class NotiApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifier(this).ensureChannels()
        scope.launch {
            val settings = NotiRepository.get(this@NotiApplication).settings.first()
            DigestWorker.schedule(this@NotiApplication, settings.digestIntervalHours)
        }
    }
}
