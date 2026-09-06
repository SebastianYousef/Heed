package io.github.sebastianyousef.ply.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.sebastianyousef.keel.core.Time
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Everything, as one JSON file you can read.
 *
 * This is not a convenience. Ply has no network permission and `allowBackup` is off, which
 * together mean **export is the only way data ever leaves a phone** — a factory reset
 * without one loses a training history that cannot be regenerated. Refusing cloud backup
 * without providing this would not be privacy, it would be data loss with a principle
 * attached.
 *
 * JSON rather than the raw `.db`, because the point is that it can be read, checked and
 * parsed by something other than this app. A database file is a backup you have to trust
 * Ply to restore; a document is one you can open.
 *
 * Weights are written in grams — the exact stored value — with a `units` field saying so,
 * rather than converted to kilograms on the way out. A converted export cannot be
 * re-imported without rounding, and rounding a record is how a record changes.
 */
object Export {

    /** Raised when the shape changes, so an importer can tell what it is reading. */
    const val FORMAT = 1

    private val json = Json { prettyPrint = true }

    suspend fun build(dao: PlyDao): String {
        val document = buildJsonObject {
            put("format", FORMAT)
            put("app", "Ply")
            put("exportedAt", System.currentTimeMillis())
            put("units", "grams, millimetres, seconds, milliseconds since epoch")

            put("sessions", buildJsonArray {
                for (summary in dao.allSessions()) {
                    add(
                        buildJsonObject {
                            put("id", summary.id)
                            put("startedAt", summary.startedAt)
                            summary.endedAt?.let { put("endedAt", it) }
                            put("title", summary.title)
                            summary.note?.let { put("note", it) }
                            put("sets", buildJsonArray {
                                for (set in dao.setsIn(summary.id)) add(set.toJson())
                            })
                        }
                    )
                }
            })

            put("bodyweight", buildJsonArray {
                for (entry in dao.allBodyweight()) {
                    add(
                        buildJsonObject {
                            put("day", entry.day)
                            put("grams", entry.grams)
                            put("recordedAt", entry.recordedAt)
                        }
                    )
                }
            })

            put("measurements", buildJsonArray {
                for (entry in dao.allMeasurements()) {
                    add(
                        buildJsonObject {
                            put("day", entry.day)
                            put("site", entry.site)
                            put("millimetres", entry.millimetres)
                        }
                    )
                }
            })

            put("steps", buildJsonArray {
                for (bucket in dao.allStepBuckets()) {
                    add(
                        buildJsonObject {
                            put("day", bucket.day)
                            put("hour", bucket.hour)
                            put("steps", bucket.steps)
                        }
                    )
                }
            })

            // Only the exercises the user made. The shipped 876 are public domain and in
            // the APK; copying them into every export would make a 900 KB file out of a
            // 20 KB one and say nothing that is not already on disk.
            put("customExercises", buildJsonArray {
                for (exercise in dao.customExercises()) {
                    add(
                        buildJsonObject {
                            put("id", exercise.id)
                            put("name", exercise.name)
                            put("primaryMuscles", exercise.primaryMuscles)
                            put("secondaryMuscles", exercise.secondaryMuscles)
                            exercise.equipment?.let { put("equipment", it) }
                            put("bodyweightLoaded", exercise.bodyweightLoaded)
                        }
                    )
                }
            })
        }
        return json.encodeToString(JsonObject.serializer(), document)
    }

    private fun WorkSet.toJson(): JsonObject = buildJsonObject {
        put("exerciseId", exerciseId)
        put("position", position)
        put("weightGrams", weightGrams)
        put("reps", reps)
        put("kind", kind.name)
        bodyweightGrams?.let { put("bodyweightGrams", it) }
        put("effectiveGrams", effectiveGrams)
        e1rmGrams?.let { put("estimatedOneRepMaxGrams", it) }
        rpe?.let { put("rpe", it) }
        holdSeconds?.let { put("holdSeconds", it) }
        put("completedAt", completedAt)
        note?.let { put("note", it) }
    }

    /**
     * Writes the document somewhere the share sheet can reach and returns an intent.
     *
     * Through a FileProvider rather than to shared storage, so the file is not readable by
     * anything on the device until the moment you pick a target — access is a per-intent
     * grant to one app, not a file left lying in Downloads.
     */
    fun share(context: Context, document: String): Intent {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(directory, "ply-${Time.dateOf(System.currentTimeMillis())}.json")
        file.writeText(document)

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.exports",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
