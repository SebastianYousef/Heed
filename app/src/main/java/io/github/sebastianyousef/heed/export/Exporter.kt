package io.github.sebastianyousef.heed.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import io.github.sebastianyousef.heed.BuildConfig
import io.github.sebastianyousef.heed.data.HeedRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gathers everything Heed knows, redacts it to the requested level, and hands back a file
 * you can send somewhere.
 *
 * Written to the cache directory rather than shared storage: the export is meant to be
 * passed straight to a share sheet and then forgotten, not left lying around where some
 * other app can read it. [prune] clears the older ones each time.
 */
class Exporter(private val context: Context) {

    private val repo = HeedRepository.get(context)

    suspend fun export(level: RedactionLevel): Uri {
        val now = System.currentTimeMillis()
        val settings = repo.settings.first()
        val (examples, confidence) = repo.modelStats()

        val input = ExportBuilder.Input(
            level = level,
            generatedAt = now,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            device = mapOf(
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "androidRelease" to Build.VERSION.RELEASE,
                "sdkInt" to Build.VERSION.SDK_INT.toString(),
            ),
            settings = mapOf(
                "threshold" to settings.threshold,
                "holdWindowMs" to settings.holdWindowMs,
                "digestIntervalHours" to settings.digestIntervalHours,
                "quietHoursStart" to settings.quietHoursStart,
                "quietHoursEnd" to settings.quietHoursEnd,
                "quietHoursStrict" to settings.quietHoursStrict,
                "retentionDays" to settings.retentionDays,
                "onboardingComplete" to settings.onboardingComplete,
            ),
            records = repo.dao.allRecords(MAX_ROWS),
            policies = repo.dao.allPolicies(),
            liveChannels = repo.dao.liveChannels(),
            digests = repo.dao.allDigests(50),
            modelExamples = examples,
            modelConfidence = confidence,
            structuredWeights = repo.structuredWeightSnapshot(),
        )

        val json = ExportBuilder.build(input)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        prune(dir)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val file = File(dir, "heed-$stamp-${level.name.lowercase()}.json")
        file.writeText(json)

        return FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }

    fun shareIntent(uri: Uri, level: RedactionLevel): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "Heed export (${level.name.lowercase().replace('_', ' ')})",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun prune(dir: File) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
    }

    private companion object {
        /** Enough history to see patterns, small enough to stay readable and sendable. */
        const val MAX_ROWS = 5000
    }
}
