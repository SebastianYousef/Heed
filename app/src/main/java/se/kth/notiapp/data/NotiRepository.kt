package se.kth.notiapp.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import se.kth.notiapp.score.FeatureExtractor
import se.kth.notiapp.score.OnlineClassifier
import se.kth.notiapp.score.ScoreResult
import se.kth.notiapp.score.ScoringPipeline
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Single owner of the database, the settings and the classifier.
 *
 * Two scoring entry points on purpose:
 *
 *  - [score] is the comfortable one. Hits the database for the app policy, suspends
 *    freely. Used by the listener path, where we are already too late to matter.
 *  - [scoreFast] never suspends and never touches disk, reading only the warm caches.
 *    NotificationAssistantService.onNotificationEnqueued blocks notification delivery
 *    system-wide while it runs and the framework will drop us if we dawdle, so that
 *    path gets a hard in-memory budget.
 *
 * The classifier is guarded by a plain monitor rather than a coroutine Mutex precisely
 * so the fast path can take it without suspending. Every critical section is a few
 * thousand float operations, so contention is not a concern.
 */
class NotiRepository(private val context: Context) {

    private val db = NotiDatabase.get(context)
    val dao: NotiDao = db.dao()
    val settingsStore = SettingsStore(context)

    private val classifier = OnlineClassifier()
    private val pipeline = ScoringPipeline(classifier)
    private val classifierLock = Any()
    @Volatile private var modelLoaded = false

    // --- warm caches, kept fresh so the assistant path never blocks on IO ---
    @Volatile private var cachedSettings = Settings()
    private val policyCache = ConcurrentHashMap<String, AppPolicy>()
    private val silencedCache = ConcurrentHashMap<String, Boolean>()

    /** package -> notification count, feeding the "how chatty is this app" feature. */
    private val chattiness = ConcurrentHashMap<String, Int>()
    @Volatile private var chattinessMax = 1

    val settings: Flow<Settings> get() = settingsStore.settings

    /** Call once from a long-lived scope (the capture services do this on connect). */
    fun warmCaches(scope: CoroutineScope) {
        scope.launch { ensureModelLoaded() }
        scope.launch {
            settingsStore.settings.collect { cachedSettings = it }
        }
        scope.launch {
            dao.observePolicies().collect { policies ->
                for (p in policies) {
                    policyCache[p.packageName] = p.policy
                    silencedCache[p.packageName] = p.sourceSilenced
                    val total = p.alertedCount + p.suppressedCount
                    chattiness[p.packageName] = total
                    if (total > chattinessMax) chattinessMax = total
                }
            }
        }
    }

    suspend fun ensureModelLoaded() {
        if (modelLoaded) return
        val stored = dao.loadModel()
        synchronized(classifierLock) {
            if (!modelLoaded) {
                stored?.let { classifier.load(it.weights, it.bias, it.examplesSeen) }
                modelLoaded = true
            }
        }
    }

    /** Non-suspending, cache-only. Safe to call from onNotificationEnqueued. */
    fun scoreFast(record: NotificationRecord): ScoreResult {
        val policy = policyCache[record.packageName] ?: AppPolicy.LEARN
        val chatty = ((chattiness[record.packageName] ?: 0).toFloat() / chattinessMax)
            .coerceIn(0f, 1f)
        return synchronized(classifierLock) {
            pipeline.score(record, policy, chatty, effectiveThreshold(cachedSettings))
        }
    }

    /** Disk-backed, authoritative. Used by the listener path. */
    suspend fun score(record: NotificationRecord): ScoreResult {
        ensureModelLoaded()
        val policy = dao.policyFor(record.packageName)?.policy ?: AppPolicy.LEARN
        policyCache[record.packageName] = policy
        val settings = settingsStore.settings.first().also { cachedSettings = it }
        val chatty = ((chattiness[record.packageName] ?: 0).toFloat() / chattinessMax)
            .coerceIn(0f, 1f)
        return synchronized(classifierLock) {
            pipeline.score(record, policy, chatty, effectiveThreshold(settings))
        }
    }

    fun currentSettings(): Settings = cachedSettings

    fun isSourceSilencedCached(pkg: String): Boolean = silencedCache[pkg] ?: false

    /**
     * During quiet hours the bar is raised past anything the blend can produce, so only
     * rule overrides — calls, alarms, one-time codes — survive.
     */
    private fun effectiveThreshold(settings: Settings): Float {
        if (!settings.quietHoursStrict) return settings.threshold
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val inQuiet = if (settings.quietHoursStart <= settings.quietHoursEnd) {
            hour in settings.quietHoursStart until settings.quietHoursEnd
        } else {
            hour >= settings.quietHoursStart || hour < settings.quietHoursEnd
        }
        return if (inQuiet) 1.1f else settings.threshold
    }

    suspend fun persist(record: NotificationRecord): Long {
        val id = dao.insert(record)
        if (dao.policyFor(record.packageName) == null) {
            dao.upsertPolicy(
                AppPolicyRecord(
                    packageName = record.packageName,
                    appLabel = record.appLabel,
                    lastSeenAt = record.postedAt,
                )
            )
        }
        dao.bumpCounts(
            pkg = record.packageName,
            alerted = if (record.decision == Decision.ALERTED) 1 else 0,
            suppressed = if (record.decision == Decision.SUPPRESSED) 1 else 0,
            at = record.postedAt,
        )
        val n = (chattiness[record.packageName] ?: 0) + 1
        chattiness[record.packageName] = n
        if (n > chattinessMax) chattinessMax = n
        return id
    }

    suspend fun recordFeedback(id: Long, feedback: Feedback) {
        ensureModelLoaded()
        dao.setFeedback(id, feedback)
        val record = dao.observeOne(id).first() ?: return
        trainOn(record.copy(feedback = feedback))
    }

    suspend fun recordFeedbackByKey(sbnKey: String, feedback: Feedback) {
        ensureModelLoaded()
        val record = dao.findByKey(sbnKey) ?: return
        if (record.feedback != Feedback.NONE) return
        dao.setFeedbackByKey(sbnKey, feedback)
        trainOn(record.copy(feedback = feedback))
    }

    /**
     * One gradient step, then write the weights back. The blob is ~35 KB, so persisting
     * on every example is cheaper than the bookkeeping that batching would need.
     */
    private suspend fun trainOn(record: NotificationRecord) {
        val (label, weight) = when (record.feedback) {
            Feedback.CLICKED -> 1f to 1f
            Feedback.DISMISSED -> 0f to 0.4f       // weak: people swipe reflexively
            Feedback.MARKED_IMPORTANT -> 1f to 3f  // explicit, so it should move fast
            Feedback.MARKED_NOISE -> 0f to 3f
            Feedback.NONE -> return
        }
        val chatty = ((chattiness[record.packageName] ?: 0).toFloat() / chattinessMax)
            .coerceIn(0f, 1f)
        val features = FeatureExtractor.extract(record, chatty)
        val snapshot = synchronized(classifierLock) {
            classifier.train(features, label, weight)
            ModelState(
                weights = classifier.serialize(),
                bias = classifier.bias,
                examplesSeen = classifier.examplesSeen,
                updatedAt = System.currentTimeMillis(),
            )
        }
        dao.saveModel(snapshot)
    }

    suspend fun setPolicy(pkg: String, label: String, policy: AppPolicy) {
        val existing = dao.policyFor(pkg)
        dao.upsertPolicy(
            existing?.copy(policy = policy)
                ?: AppPolicyRecord(packageName = pkg, appLabel = label, policy = policy)
        )
        policyCache[pkg] = policy
    }

    suspend fun setSourceSilenced(pkg: String, silenced: Boolean) {
        val existing = dao.policyFor(pkg) ?: return
        dao.upsertPolicy(existing.copy(sourceSilenced = silenced))
        silencedCache[pkg] = silenced
    }

    fun modelStats(): Pair<Int, Float> = synchronized(classifierLock) {
        classifier.examplesSeen to classifier.confidence()
    }

    suspend fun resetModel() {
        val snapshot = synchronized(classifierLock) {
            classifier.reset()
            ModelState(
                weights = classifier.serialize(),
                bias = 0f,
                examplesSeen = 0,
                updatedAt = System.currentTimeMillis(),
            )
        }
        dao.saveModel(snapshot)
    }

    suspend fun pruneOldRecords() {
        val days = settingsStore.settings.first().retentionDays
        dao.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L)
    }

    companion object {
        @Volatile private var instance: NotiRepository? = null
        fun get(context: Context): NotiRepository = instance ?: synchronized(this) {
            instance ?: NotiRepository(context.applicationContext).also { instance = it }
        }
    }
}
