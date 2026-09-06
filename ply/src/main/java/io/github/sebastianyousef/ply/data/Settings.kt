package io.github.sebastianyousef.ply.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.sebastianyousef.ply.train.Load
import io.github.sebastianyousef.ply.train.Plates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("ply")

/**
 * Everything the user has chosen, as opposed to everything they have done.
 *
 * In DataStore rather than in the database, and the line between the two is worth stating
 * once: the database holds facts that cannot be regenerated and whose loss is real, and
 * settings hold preferences that could be re-entered in a minute. That is also why the
 * database is never destructively migrated and this file needs no migrations at all — a
 * key that disappears falls back to its default, which is the correct behaviour for a
 * preference and would be data loss for a set.
 */
class Settings(private val context: Context) {

    val unit: Flow<Load.Unit> = context.store.data.map {
        runCatching { Load.Unit.valueOf(it[UNIT] ?: "") }.getOrDefault(Load.Unit.KG)
    }

    /** How much one press of the stepper moves the weight. 2.5 kg by default. */
    val increment: Flow<Int> = context.store.data.map { it[INCREMENT] ?: 2_500 }

    /** The fallback rest, for exercises that have not been given one of their own. */
    val defaultRestSeconds: Flow<Int> = context.store.data.map { it[REST] ?: 150 }

    val restAutoStart: Flow<Boolean> = context.store.data.map { it[REST_AUTO] ?: true }

    val stepGoal: Flow<Int> = context.store.data.map { it[STEP_GOAL] ?: 8_000 }

    val barGrams: Flow<Int> = context.store.data.map { it[BAR] ?: Plates.DEFAULT_BAR_GRAMS }

    val plateStock: Flow<List<Plates.Stock>> = context.store.data.map {
        decodeStock(it[PLATES]) ?: Plates.DEFAULT_STOCK
    }

    suspend fun setUnit(value: Load.Unit) = put(UNIT, value.name)
    suspend fun setIncrement(grams: Int) = put(INCREMENT, grams)
    suspend fun setDefaultRest(seconds: Int) = put(REST, seconds)
    suspend fun setRestAutoStart(on: Boolean) = put(REST_AUTO, on)
    suspend fun setStepGoal(steps: Int) = put(STEP_GOAL, steps)
    suspend fun setBar(grams: Int) = put(BAR, grams)
    suspend fun setPlateStock(stock: List<Plates.Stock>) = put(PLATES, encodeStock(stock))

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.store.edit { it[key] = value }
    }

    companion object {
        private val UNIT = stringPreferencesKey("unit")
        private val INCREMENT = intPreferencesKey("increment_grams")
        private val REST = intPreferencesKey("rest_seconds")
        private val REST_AUTO = booleanPreferencesKey("rest_auto_start")
        private val STEP_GOAL = intPreferencesKey("step_goal")
        private val BAR = intPreferencesKey("bar_grams")
        private val PLATES = stringPreferencesKey("plate_stock")

        /**
         * "25000:4,20000:4,…" — plate size in grams, then how many pairs.
         *
         * A string rather than a table, because this is a setting and not data: it is
         * seven numbers, it is never queried, and giving it a table would mean a schema
         * version, a DAO and a migration to carry something that can be retyped in a
         * minute if it is ever lost.
         */
        fun encodeStock(stock: List<Plates.Stock>): String =
            stock.filter { it.pairs > 0 }
                .sortedByDescending { it.gramsEach }
                .joinToString(",") { "${it.gramsEach}:${it.pairs}" }

        /** Null when there is nothing stored or what is stored cannot be read. */
        fun decodeStock(encoded: String?): List<Plates.Stock>? {
            if (encoded.isNullOrBlank()) return null
            val parsed = encoded.split(',').mapNotNull { entry ->
                val parts = entry.split(':')
                if (parts.size != 2) return@mapNotNull null
                val grams = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val pairs = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                if (grams <= 0 || pairs <= 0) null else Plates.Stock(grams, pairs)
            }
            // A partially readable value is not repaired into a half inventory that would
            // silently calculate the wrong plates; it falls back to the default instead.
            return parsed.takeIf { it.isNotEmpty() && it.size == encoded.split(',').size }
                ?.sortedByDescending { it.gramsEach }
        }
    }
}
