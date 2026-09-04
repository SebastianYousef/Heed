package io.github.sebastianyousef.heed.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.sebastianyousef.heed.export.Redaction
import io.github.sebastianyousef.heed.score.FeatureExtractor
import io.github.sebastianyousef.heed.score.OnlineClassifier
import io.github.sebastianyousef.heed.score.ScoreResult
import io.github.sebastianyousef.heed.score.ScoringPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import io.github.sebastianyousef.heed.focus.FocusRule
import io.github.sebastianyousef.heed.score.SenderHistory

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
class HeedRepository(private val context: Context) {

    private val db = HeedDatabase.get(context)
    val dao: HeedDao = db.dao()
    val settingsStore = SettingsStore(context)

    private val classifier = OnlineClassifier()
    private val pipeline = ScoringPipeline(classifier)
    private val classifierLock = Any()
    @Volatile private var modelLoaded = false

    // --- warm caches, kept fresh so the assistant path never blocks on IO ---
    @Volatile private var cachedSettings = Settings()
    private val policyCache = ConcurrentHashMap<String, AppPolicy>()
    private val silencedCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Focus rules, in memory.
     *
     * The accessibility service is asked "is there a rule for this app" once per scroll
     * event, which on a fast flick is tens of times a second. Answering that from Room
     * meant a coroutine launch, a query and a DataStore read per event — the single
     * largest thing Heed was doing to the battery, and all of it to discover, almost
     * always, that there is no rule at all. Rules change perhaps once a week, so they
     * live here and the hot path becomes a hash lookup with no allocation.
     */
    private val ruleCache = ConcurrentHashMap<String, FocusRule>()
    private val surfaceCache =
        ConcurrentHashMap<String, List<io.github.sebastianyousef.heed.focus.LearnedSurface>>()
    @Volatile private var rulesWarm = false

    /**
     * conversation -> what we know about it, refreshed when feedback changes.
     *
     * Held in memory for the same reason the rules are: this is read on the capture path,
     * where a notification is being judged inside a two-second hold and there is no room
     * for a query.
     */
    private val senderCache = ConcurrentHashMap<String, SenderHistory>()
    private val senderByHour = ConcurrentHashMap<String, FloatArray>()

    /** package -> notification count, feeding the "how chatty is this app" feature. */
    private val chattiness = ConcurrentHashMap<String, Int>()
    @Volatile private var chattinessMax = 1

    val settings: Flow<Settings> get() = settingsStore.settings

    /**
     * Call once from a long-lived scope (the capture services do this on connect).
     *
     * Guarded, because three separate services now want warm caches and each call used
     * to start its own set of collectors on the same singleton — three copies of every
     * flow, each rebuilding the same maps on every write.
     */
    private val warmed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun warmCaches(scope: CoroutineScope) {
        if (!warmed.compareAndSet(false, true)) return
        scope.launch { ensureModelLoaded() }
        scope.launch {
            settingsStore.settings.collect { cachedSettings = it }
        }
        scope.launch {
            dao.observeFocusRules().collect { rules ->
                ruleCache.clear()
                for (r in rules) ruleCache[r.packageName] = r
                rulesWarm = true
            }
        }
        scope.launch {
            dao.observeConversationStats().collect { rows ->
                senderCache.clear()
                senderByHour.clear()
                for ((id, group) in rows.groupBy { it.conversationId }) {
                    val seen = group.sumOf { it.seen }
                    val engaged = group.sumOf { it.engaged }
                    val dismissed = group.sumOf { it.dismissed }
                    val reacted = engaged + dismissed
                    senderCache[id] = SenderHistory(
                        seen = seen,
                        // Only feedback the user actually gave counts. Notifications they
                        // never touched say nothing either way, and treating silence as
                        // rejection would bury every thread they simply read on the
                        // lock screen.
                        engagement = if (reacted == 0) 0f else engaged.toFloat() / reacted,
                    )
                    val buckets = FloatArray(HOUR_BUCKETS)
                    for (row in group) {
                        val b = row.hourBucket.coerceIn(0, HOUR_BUCKETS - 1)
                        buckets[b] = if (row.seen == 0) 0f
                        else row.engagedInBucket.toFloat() / row.seen
                    }
                    senderByHour[id] = buckets
                }
            }
        }
        scope.launch {
            dao.observeSurfaces().collect { surfaces ->
                surfaceCache.clear()
                surfaceCache.putAll(surfaces.groupBy { it.packageName })
            }
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

    /**
     * The rule for an app without touching disk.
     *
     * Returns null both when there is no rule and when the cache has not warmed yet. The
     * second case resolves within a second of the service connecting, and the cost of
     * being wrong for that second is one missed scroll measurement — far cheaper than
     * making every caller wait on IO to find out.
     */
    /**
     * What is known about the sender of this notification, at this time of day.
     *
     * Returns [SenderHistory.UNKNOWN] for a thread never seen before, which is the honest
     * answer — a first message from someone new should be judged on its content, not
     * penalised for being unfamiliar.
     */
    fun senderHistoryFor(record: NotificationRecord): SenderHistory {
        val id = record.conversationId ?: return SenderHistory.UNKNOWN
        val base = senderCache[id] ?: return SenderHistory.UNKNOWN
        val hour = Calendar.getInstance().apply { timeInMillis = record.postedAt }
            .get(Calendar.HOUR_OF_DAY)
        val bucket = (hour / 4).coerceIn(0, HOUR_BUCKETS - 1)
        return base.copy(engagementAtHour = senderByHour[id]?.getOrNull(bucket) ?: 0f)
    }

    fun cachedRuleFor(pkg: String): FocusRule? = ruleCache[pkg]

    /**
     * Taught screens for an app, in memory for the same reason the rules are.
     */
    fun cachedSurfacesFor(pkg: String): List<io.github.sebastianyousef.heed.focus.LearnedSurface> =
        surfaceCache[pkg].orEmpty()

    fun rulesAreWarm(): Boolean = rulesWarm

    /**
     * Whether any rule needs to act the instant an app comes to the foreground.
     *
     * Scroll rules do not: they are driven by scroll events, which arrive on their own.
     * Only limits and grayscale care about the moment of entry, so with none of those set
     * the foreground app can be checked far more lazily.
     */
    fun anyRuleNeedsForeground(): Boolean = ruleCache.values.any {
        it.grayscale || it.dailyUsageSeconds > 0 || it.dailyLaunchLimit > 0
    }

    /** True if any app at all has a rule, so a hot path can leave immediately. */
    fun hasAnyRule(): Boolean = ruleCache.isNotEmpty()

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
            pipeline.score(record, policy, chatty, effectiveThreshold(cachedSettings), senderHistoryFor(record))
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
            pipeline.score(record, policy, chatty, effectiveThreshold(settings), senderHistoryFor(record))
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

    /** Result of storing a notification: which row it landed in, and whether it existed. */
    data class PersistOutcome(val id: Long, val wasUpdate: Boolean)

    /**
     * Store a notification, folding in-place updates onto the row that already holds them.
     *
     * Android re-fires onNotificationPosted every time an app rewrites a notification
     * under the same key, which for a chat thread is every message and for a tracker is
     * every few seconds. Inserting each one would turn the inbox into a changelog.
     */
    suspend fun persistOrUpdate(record: NotificationRecord): PersistOutcome {
        val existing = dao.findByKey(record.sbnKey)
        val isLiveEdit = existing != null &&
            existing.feedback == Feedback.NONE &&
            record.postedAt - existing.postedAt <= UPDATE_WINDOW_MS

        if (existing != null && isLiveEdit) {
            if (existing.contentHash == record.contentHash) {
                // Reposted with nothing changed. Count it, show nothing new.
                dao.bumpUpdate(existing.id, record.postedAt)
            } else {
                dao.updateInPlace(
                    id = existing.id,
                    title = record.title,
                    text = record.text,
                    bigText = record.bigText,
                    subText = record.subText,
                    contentHash = record.contentHash,
                    postedAt = record.postedAt,
                    score = record.score,
                    scoreReason = record.scoreReason,
                    decision = record.decision,
                )
            }
            return PersistOutcome(existing.id, wasUpdate = true)
        }
        return PersistOutcome(persist(record), wasUpdate = false)
    }

    /** Settle a notification that was waiting in the hold buffer. */
    suspend fun resolveHeld(id: Long, result: ScoreResult, path: CapturePath) {
        dao.resolveHeld(id, result.decision, result.score, result.reason, path)
    }

    /**
     * Holds orphaned by the process dying mid-window. They never alerted anyone, so
     * filing them is the right resolution — and leaving them HELD forever would keep
     * them out of every digest.
     */
    suspend fun resolveOrphanedHolds(): Int =
        dao.resolveStaleHeld(System.currentTimeMillis() - ORPHAN_HOLD_MS)

    suspend fun knownLiveChannels(): List<Pair<String, String>> =
        dao.liveChannels().map { it.packageName to it.channelId }

    /** Remember a channel that behaves like a live display, and clear what it already left. */
    suspend fun markLiveChannel(record: NotificationRecord, burstSize: Int) {
        val channelId = record.channelId ?: return
        dao.markLiveChannel(
            LiveChannelRecord(
                packageName = record.packageName,
                channelId = channelId,
                appLabel = record.appLabel,
                detectedAt = System.currentTimeMillis(),
                burstSize = burstSize,
            )
        )
        dao.deleteFromChannel(record.packageName, channelId)
    }

    suspend fun unmarkLiveChannel(pkg: String, channelId: String) {
        dao.clearLiveChannel(pkg, channelId)
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
            // Stronger than a reflexive swipe, weaker than you saying it outright.
            Feedback.CLICKED_THEN_SCROLLED -> 0f to 1.5f
            Feedback.NONE -> return
        }
        val chatty = ((chattiness[record.packageName] ?: 0).toFloat() / chattinessMax)
            .coerceIn(0f, 1f)
        val features = FeatureExtractor.extract(record, chatty, senderHistoryFor(record))
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

    fun structuredWeightSnapshot(): Map<String, Float> = synchronized(classifierLock) {
        classifier.structuredWeights()
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

    /**
     * Strip the text from notifications older than the content-retention window.
     *
     * The row stays: app, category, score, decision, your feedback and the shape of the
     * text all survive, so history, statistics and exports keep working. What goes is the
     * only part that is worth reading — which is the entire point.
     *
     * This costs the classifier nothing. Training happens at the moment you react to a
     * notification and is folded straight into the weights, which live in their own row
     * and are never touched here. Scrubbing the text a week later cannot untrain
     * anything, because the text was never what the model was carrying.
     */
    suspend fun scrubOldContent(): Int {
        val days = settingsStore.settings.first().contentRetentionDays
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val stale = dao.scrubbable(cutoff)
        val now = System.currentTimeMillis()
        for (record in stale) {
            dao.scrub(record.id, Redaction.encode(Redaction.shape(record.body)), now)
        }
        return stale.size
    }

    /** Sessions and scroll spans follow the same record-retention window. */
    suspend fun pruneUsageHistory() {
        val days = settingsStore.settings.first().recordRetentionDays
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        dao.deleteSessionsOlderThan(cutoff)
        dao.deleteSpansOlderThan(cutoff)
    }

    /** Drop rows entirely once they are past the longer record-retention window. */
    suspend fun pruneOldRecords(): Int {
        val days = settingsStore.settings.first().recordRetentionDays
        return dao.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L)
    }

    /**
     * A one-line explanation of how the user most likely got into this app, for the
     * focus overlay. Null when there is no recent notification to blame.
     */
    suspend fun lastAttributedTriggerFor(pkg: String): String? {
        val now = System.currentTimeMillis()
        val notification = dao.attributableNotification(pkg, now - 30 * 60 * 1000L, now)
            ?: return null
        return when (notification.feedback) {
            Feedback.MARKED_NOISE ->
                "You got here from a notification you'd already marked as noise."
            Feedback.CLICKED_THEN_SCROLLED ->
                "You got here from a notification that did this to you last time too."
            else -> "You got here from a ${notification.appLabel} notification."
        }
    }

    /**
     * Record the screen the user just pointed at, and switch the app to precise matching —
     * teaching Heed a screen is an unambiguous statement that behaviour alone is not
     * distinguishing enough here.
     */
    suspend fun learnSurface(pkg: String, tokens: Set<String>) {
        val existing = dao.surfacesFor(pkg)
        dao.insertSurface(
            io.github.sebastianyousef.heed.focus.LearnedSurface(
                packageName = pkg,
                label = "Screen ${existing.size + 1}",
                fingerprint = tokens.joinToString("\n"),
                block = true,
                capturedAt = System.currentTimeMillis(),
            )
        )
        val rule = dao.focusRuleFor(pkg)
            ?: io.github.sebastianyousef.heed.focus.FocusRule(pkg, appLabelFor(pkg))
        dao.upsertFocusRule(
            rule.copy(detection = io.github.sebastianyousef.heed.focus.DetectionMode.PRECISE)
        )
    }

    private fun appLabelFor(pkg: String) =
        io.github.sebastianyousef.heed.capture.NotificationMapper.appLabel(context, pkg)

    /**
     * Remove shipped anchors that earlier versions copied into the database.
     *
     * Seeding them as if the user had taught them was a mistake with a visible cost. The
     * service already evaluates [KnownSurfaces] directly, so the rows were redundant; and
     * because they were copies, they went stale the moment an anchor was corrected. The
     * Snapchat rule ended up listing "Spotlight, Discover, Spotlight" — one of them
     * pointing at a view id that no longer exists in the app at all.
     *
     * A taught fingerprint is always many tokens (capture requires at least eight), so a
     * single-token surface can only have come from seeding. That makes them safe to
     * remove without touching anything the user actually pointed at.
     */
    suspend fun removeSeededSurfaces() {
        for (surface in dao.allSurfaces()) {
            if (surface.tokens.size <= 1) dao.deleteSurface(surface.id)
        }
    }

    /**
     * Give an app a rule only if it is one of the handful whose business is the scroll.
     *
     * Seeding every app buries the four that matter — the previous build put a Block rule
     * on an authenticator because it sat near the top of an undifferentiated list, while
     * Snapchat had no rule at all.
     */
    suspend fun ensurePresetFor(pkg: String, fallbackLabel: String) {
        if (!io.github.sebastianyousef.heed.focus.KnownScrollers.isKnown(pkg)) return
        if (dao.focusRuleFor(pkg) != null) return
        io.github.sebastianyousef.heed.focus.KnownScrollers.presetFor(pkg, fallbackLabel)
            ?.let { dao.upsertFocusRule(it) }
    }

    /**
     * Give presets to known scrollers already in your history, not just ones you happen to
     * open next. Otherwise upgrading looks like the feature does nothing.
     */
    suspend fun seedPresetsFromHistory() {
        val seen = dao.allSessions(2000).map { it.packageName to it.appLabel }.distinct()
        for ((pkg, label) in seen) ensurePresetFor(pkg, label)
        repairBehaviouralBlocks()
        removeSeededSurfaces()
    }

    /**
     * Move existing rules off behavioural blocking wherever precise blocking is possible.
     *
     * This repairs a rule that was already saved, which is unusual and deliberate. A
     * Block rule on Snapchat in Automatic mode does not do the thing its own description
     * promises: it cannot see Spotlight, so it fires on the first few scrolls of whatever
     * you happen to be looking at, which in practice meant being thrown out of a
     * conversation with a friend mid-sentence. Leaving that rule as the user saved it
     * would be respecting the letter of a setting while breaking what it was for.
     *
     * Only touched for apps Heed ships anchors for, and only the detection mode — the
     * block itself, which is what the user actually asked for, is preserved and starts
     * working properly.
     */
    private suspend fun repairBehaviouralBlocks() {
        for (rule in dao.allFocusRules()) {
            if (!io.github.sebastianyousef.heed.focus.KnownSurfaces.hasBlockAnchors(rule.packageName)) continue
            if (rule.detection != io.github.sebastianyousef.heed.focus.DetectionMode.BEHAVIOURAL) continue
            if (rule.mode == io.github.sebastianyousef.heed.focus.FocusMode.OFF) continue
            dao.upsertFocusRule(
                rule.copy(detection = io.github.sebastianyousef.heed.focus.DetectionMode.PRECISE)
            )
        }
    }

    /** Whether the clock is inside the user's bedtime window right now. */
    /**
     * Start or stop the foreground enforcement service to match what the rules need.
     *
     * Called whenever a rule changes rather than left running always, because the service
     * costs a permanent notification and a once-a-second poll. An app about reducing the
     * clutter on your phone does not get to add a row to your shade for a feature you are
     * not using.
     */
    suspend fun syncAttentionService() {
        val settings = settingsStore.settings.first()
        val rules = dao.allFocusRules()
        val needed = settings.grayscaleAtBedtime ||
            settings.bedtimeEnabled ||
            rules.any {
                it.grayscale || it.dailyUsageSeconds > 0 || it.dailyLaunchLimit > 0
            } ||
            // Also needed purely to watch for a banking app opening, since the
            // accessibility service cannot be the thing that notices it is in the way.
            (settings.pauseForBanking &&
                io.github.sebastianyousef.heed.focus.ScrollWatcherService.isEnabled(context))
        io.github.sebastianyousef.heed.focus.AttentionService.syncWith(context, needed)
    }

    suspend fun isBedtimeNow(): Boolean {
        val s = settingsStore.settings.first()
        if (!s.bedtimeEnabled) return false
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (s.bedtimeStart <= s.bedtimeEnd) {
            hour in s.bedtimeStart until s.bedtimeEnd
        } else {
            hour >= s.bedtimeStart || hour < s.bedtimeEnd
        }
    }

    /** Rules may be tightened at any time, but not loosened while strict mode holds. */
    suspend fun strictActive(): Boolean =
        settingsStore.settings.first().strictUntil > System.currentTimeMillis()

    // --- listener health ---

    private val _listenerConnected = MutableStateFlow(false)

    /** Drives the "Heed has stopped seeing your notifications" banner. */
    val listenerConnected: StateFlow<Boolean> = _listenerConnected

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    companion object {
        /**
         * How long a notification key stays eligible to be updated in place. Long enough
         * to cover a conversation someone keeps adding to, short enough that a daily
         * reminder reusing the same id becomes a new row.
         */
        private const val UPDATE_WINDOW_MS = 6 * 60 * 60 * 1000L

        /** A hold older than this cannot still be waiting; the process must have died. */
        private const val ORPHAN_HOLD_MS = 60_000L

        @Volatile private var instance: HeedRepository? = null
        /** Four-hour slices: coarse enough to fill within a week of ordinary use. */
        const val HOUR_BUCKETS = 6

        fun get(context: Context): HeedRepository = instance ?: synchronized(this) {
            instance ?: HeedRepository(context.applicationContext).also { instance = it }
        }
    }
}
