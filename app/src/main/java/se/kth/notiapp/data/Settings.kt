package se.kth.notiapp.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("noti_settings")

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
    val retentionDays: Int = 30,
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
        val RETENTION = intPreferencesKey("retention_days")
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
            retentionDays = p[Keys.RETENTION] ?: d.retentionDays,
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
    suspend fun setRetentionDays(v: Int) = context.dataStore.edit { it[Keys.RETENTION] = v }
}
