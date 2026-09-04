package io.github.sebastianyousef.heed.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("heed_settings")

data class Settings(
    /** Score above which a notification is allowed to interrupt. */
    val threshold: Float = 0.55f,

    /**
     * How long to sit on a notification before deciding, in ms. Only safe to raise
     * above zero once the source app is silenced — otherwise the alert has already
     * fired and holding just delays our cancel.
     */
    val holdWindowMs: Long = 2_000L,

    val digestIntervalHours: Int = 4,
    val quietHoursStart: Int = 22,
    val quietHoursEnd: Int = 7,

    /** During quiet hours, only rule-forced notifications (calls, alarms, OTP) get through. */
    val quietHoursStrict: Boolean = true,

    val onboardingComplete: Boolean = false,

    /**
     * Days before a notification's text is scrubbed. The row survives — app, score,
     * decision, your feedback and the shape of the text all remain — so history and
     * statistics are intact and the model is entirely unaffected. Only the words go.
     */
    val contentRetentionDays: Int = 7,

    /** Days before the row itself is deleted. Must be at least [contentRetentionDays]. */
    val recordRetentionDays: Int = 90,

    /**
     * Minutes of unbroken scrolling before Heed interrupts. 0 turns it off.
     *
     * Unbroken is the operative word: pausing to actually read something resets it, so
     * this measures the trance rather than the time.
     */
    val scrollInterventionMinutes: Int = 10,

    /**
     * Drain the colour out of the whole screen during bedtime hours.
     *
     * Gentler than the block and, for most people, more effective. A blocked app is a
     * fight you can win by turning the rule off; a grey screen is simply not worth
     * staying up for.
     */
    val grayscaleAtBedtime: Boolean = false,


    /** Blocks every app that has a rule, between these hours. */
    val bedtimeEnabled: Boolean = false,
    val bedtimeStart: Int = 23,
    val bedtimeEnd: Int = 7,

    /**
     * Until this timestamp, rules can be tightened but not loosened.
     *
     * The point of a strict mode is that the version of you who set the rule and the
     * version who wants to break it are not the same person, and only one of them was
     * thinking clearly. Turning it on is instant; turning it off waits.
     */
    val strictUntil: Long = 0L,

    // --- focus sessions ---
    //
    // Held in settings rather than read from the sessions table because the enforcement
    // path asks "is a session running" on every foreground poll, and [HeedRepository]
    // already caches settings for exactly that reason. The table is the history; this is
    // the live state, and it survives a reboot because DataStore does.
    /** When the running session began, or 0 for none. */
    val focusStartedAt: Long = 0L,
    /** How long it was meant to last. 0 for an open-ended stopwatch. */
    val focusPlannedMs: Long = 0L,
    val focusLabel: String = "",
    /** Row in `focus_sessions` this is the live half of. */
    val focusSessionId: Long = 0L,
    /** When the user asked to stop, or 0. Starting is instant; stopping waits. */
    val focusEndRequestedAt: Long = 0L,
    /** Packages that stay open during a session, comma separated. */
    val focusAllowed: String = "",
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val THRESHOLD = floatPreferencesKey("threshold")
        val HOLD_MS = longPreferencesKey("hold_ms")
        val DIGEST_HOURS = intPreferencesKey("digest_hours")
        val QUIET_START = intPreferencesKey("quiet_start")
        val QUIET_END = intPreferencesKey("quiet_end")
        val QUIET_STRICT = booleanPreferencesKey("quiet_strict")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val CONTENT_RETENTION = intPreferencesKey("content_retention_days")
        val RECORD_RETENTION = intPreferencesKey("record_retention_days")
        val SCROLL_MINUTES = intPreferencesKey("scroll_intervention_minutes")
        val GREY_BEDTIME = booleanPreferencesKey("grayscale_bedtime")
        val BEDTIME_ON = booleanPreferencesKey("bedtime_enabled")
        val BEDTIME_START = intPreferencesKey("bedtime_start")
        val BEDTIME_END = intPreferencesKey("bedtime_end")
        val STRICT_UNTIL = longPreferencesKey("strict_until")
        val FOCUS_STARTED = longPreferencesKey("focus_started_at")
        val FOCUS_PLANNED = longPreferencesKey("focus_planned_ms")
        val FOCUS_LABEL = stringPreferencesKey("focus_label")
        val FOCUS_SESSION_ID = longPreferencesKey("focus_session_id")
        val FOCUS_END_REQUESTED = longPreferencesKey("focus_end_requested_at")
        val FOCUS_ALLOWED = stringPreferencesKey("focus_allowed")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            threshold = p[Keys.THRESHOLD] ?: d.threshold,
            holdWindowMs = p[Keys.HOLD_MS] ?: d.holdWindowMs,
            digestIntervalHours = p[Keys.DIGEST_HOURS] ?: d.digestIntervalHours,
            quietHoursStart = p[Keys.QUIET_START] ?: d.quietHoursStart,
            quietHoursEnd = p[Keys.QUIET_END] ?: d.quietHoursEnd,
            quietHoursStrict = p[Keys.QUIET_STRICT] ?: d.quietHoursStrict,
            onboardingComplete = p[Keys.ONBOARDED] ?: d.onboardingComplete,
            contentRetentionDays = p[Keys.CONTENT_RETENTION] ?: d.contentRetentionDays,
            recordRetentionDays = p[Keys.RECORD_RETENTION] ?: d.recordRetentionDays,
            scrollInterventionMinutes = p[Keys.SCROLL_MINUTES] ?: d.scrollInterventionMinutes,
            grayscaleAtBedtime = p[Keys.GREY_BEDTIME] ?: d.grayscaleAtBedtime,
            bedtimeEnabled = p[Keys.BEDTIME_ON] ?: d.bedtimeEnabled,
            bedtimeStart = p[Keys.BEDTIME_START] ?: d.bedtimeStart,
            bedtimeEnd = p[Keys.BEDTIME_END] ?: d.bedtimeEnd,
            strictUntil = p[Keys.STRICT_UNTIL] ?: d.strictUntil,
            focusStartedAt = p[Keys.FOCUS_STARTED] ?: d.focusStartedAt,
            focusPlannedMs = p[Keys.FOCUS_PLANNED] ?: d.focusPlannedMs,
            focusLabel = p[Keys.FOCUS_LABEL] ?: d.focusLabel,
            focusSessionId = p[Keys.FOCUS_SESSION_ID] ?: d.focusSessionId,
            focusEndRequestedAt = p[Keys.FOCUS_END_REQUESTED] ?: d.focusEndRequestedAt,
            focusAllowed = p[Keys.FOCUS_ALLOWED] ?: d.focusAllowed,
        )
    }

    suspend fun setThreshold(v: Float) = context.dataStore.edit { it[Keys.THRESHOLD] = v }
    suspend fun setHoldWindow(v: Long) = context.dataStore.edit { it[Keys.HOLD_MS] = v }
    suspend fun setDigestInterval(v: Int) = context.dataStore.edit { it[Keys.DIGEST_HOURS] = v }
    suspend fun setQuietHours(start: Int, end: Int) = context.dataStore.edit {
        it[Keys.QUIET_START] = start; it[Keys.QUIET_END] = end
    }
    suspend fun setQuietStrict(v: Boolean) = context.dataStore.edit { it[Keys.QUIET_STRICT] = v }
    suspend fun setOnboardingComplete(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDED] = v }
    suspend fun setContentRetentionDays(v: Int) =
        context.dataStore.edit { it[Keys.CONTENT_RETENTION] = v }

    suspend fun setRecordRetentionDays(v: Int) =
        context.dataStore.edit { it[Keys.RECORD_RETENTION] = v }

    suspend fun setScrollInterventionMinutes(v: Int) =
        context.dataStore.edit { it[Keys.SCROLL_MINUTES] = v }

    suspend fun setBedtime(enabled: Boolean, start: Int, end: Int) = context.dataStore.edit {
        it[Keys.BEDTIME_ON] = enabled
        it[Keys.BEDTIME_START] = start
        it[Keys.BEDTIME_END] = end
    }


    suspend fun setGrayscaleAtBedtime(v: Boolean) =
        context.dataStore.edit { it[Keys.GREY_BEDTIME] = v }

    suspend fun setStrictUntil(until: Long) =
        context.dataStore.edit { it[Keys.STRICT_UNTIL] = until }

    suspend fun startFocus(label: String, plannedMs: Long, sessionId: Long, at: Long) =
        context.dataStore.edit {
            it[Keys.FOCUS_STARTED] = at
            it[Keys.FOCUS_PLANNED] = plannedMs
            it[Keys.FOCUS_LABEL] = label
            it[Keys.FOCUS_SESSION_ID] = sessionId
            it[Keys.FOCUS_END_REQUESTED] = 0L
        }

    suspend fun requestFocusEnd(at: Long) =
        context.dataStore.edit { it[Keys.FOCUS_END_REQUESTED] = at }

    suspend fun clearFocus() = context.dataStore.edit {
        it[Keys.FOCUS_STARTED] = 0L
        it[Keys.FOCUS_PLANNED] = 0L
        it[Keys.FOCUS_LABEL] = ""
        it[Keys.FOCUS_SESSION_ID] = 0L
        it[Keys.FOCUS_END_REQUESTED] = 0L
    }

    suspend fun setFocusAllowed(packages: Set<String>) =
        context.dataStore.edit { it[Keys.FOCUS_ALLOWED] = packages.sorted().joinToString(",") }
}
