package io.github.sebastianyousef.ply.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.sebastianyousef.keel.core.Time
import io.github.sebastianyousef.ply.MainActivity
import io.github.sebastianyousef.ply.data.PlyRepository
import kotlinx.coroutines.flow.first

/**
 * Today, on the home screen.
 *
 * The point of putting a figure outside the app is that a number you have to open
 * something to see is one you look at after the day is over. On the home screen it is in
 * the way of whatever you were about to open instead, which is the only moment it can
 * change anything.
 *
 * Glance rather than RemoteViews: the same declarative code as the rest of the app, and it
 * takes the wallpaper palette through [GlanceTheme] without a second theme having to be
 * defined and then kept in step with the first.
 */
class PlyWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = PlyRepository.get(context)
        val steps = repository.dao.stepsOn(Time.startOfToday())
        val goal = repository.settings.stepGoal.first()
        val training = repository.dao.openSessionNow() != null

        provideContent { GlanceTheme { Body(steps, goal, training) } }
    }

    @Composable
    private fun Body(steps: Int, goal: Int, training: Boolean) {
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                "%,d".format(steps),
                style = TextStyle(
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
            )
            Text(
                if (goal > 0) "of ${"%,d".format(goal)} steps" else "steps today",
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            if (training) {
                Spacer(GlanceModifier.height(8.dp))
                Text(
                    "Session in progress",
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.primary,
                    ),
                )
            }
        }
    }

    companion object {
        /**
         * Redraws every placed widget.
         *
         * Suspending rather than fire-and-forget, so the caller's own scope owns it — the
         * step worker is already a coroutine and can simply wait, and nothing here needs a
         * scope of its own to leak.
         */
        suspend fun refresh(context: Context) = PlyWidget().updateAll(context)
    }
}

class PlyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlyWidget()
}
