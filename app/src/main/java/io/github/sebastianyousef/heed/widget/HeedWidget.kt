package io.github.sebastianyousef.heed.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.sebastianyousef.heed.MainActivity
import io.github.sebastianyousef.heed.R
import io.github.sebastianyousef.heed.data.HeedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Today, on the home screen.
 *
 * The point of putting this outside the app is that a screen-time figure you have to
 * open an app to see is one you look at after the evening is gone. On the home screen it
 * is in the way of the thing you were about to open, which is the only moment it can
 * change anything.
 */
class HeedWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refresh(context)
    }

    companion object {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Called by the usage worker, so the widget moves when the numbers do. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HeedWidget::class.java))
            if (ids.isEmpty()) return

            scope.launch {
                val summary = summarise(context)
                val views = RemoteViews(context.packageName, R.layout.widget_heed).apply {
                    setTextViewText(R.id.widget_headline, formatDuration(summary.screenMs))
                    setTextViewText(R.id.widget_caption, "screen time today")
                    setTextViewText(
                        R.id.widget_scrolling,
                        "${summary.scrollingMinutes} min scrolling",
                    )
                    setTextViewText(R.id.widget_filtered, "${summary.filtered} filtered")
                    setTextViewText(
                        R.id.widget_top,
                        summary.topApp?.let { "Most of it: ${it.first} · ${formatDuration(it.second)}" }
                            ?: "Nothing recorded yet today",
                    )
                    setOnClickPendingIntent(
                        R.id.widget_headline,
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
                manager.updateAppWidget(ids, views)
            }
        }

        private data class Summary(
            val screenMs: Long,
            val scrollingMinutes: Int,
            val filtered: Int,
            val topApp: Pair<String, Long>?,
        )

        private suspend fun summarise(context: Context): Summary {
            val repo = HeedRepository.get(context)
            val since = startOfToday()
            val sessions = repo.dao.allSessions(2_000).filter { it.startedAt >= since }
            val byApp = sessions.groupBy { it.packageName }
            val top = byApp
                .map { (_, rows) -> rows.first().appLabel to rows.sumOf { it.durationMs } }
                .maxByOrNull { it.second }

            return Summary(
                screenMs = sessions.sumOf { it.durationMs },
                scrollingMinutes = repo.dao.scrollSecondsSinceAll(since) / 60,
                filtered = repo.dao.suppressedSince(since),
                topApp = top,
            )
        }

        /** "2h 14m", or "14m". Hours matter, seconds do not. */
        fun formatDuration(ms: Long): String {
            val minutes = ms / 60_000
            val hours = minutes / 60
            return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
        }

        private fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
