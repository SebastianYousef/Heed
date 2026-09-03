package io.github.sebastianyousef.heed.focus

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The interruption itself.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY, which an accessibility service may draw without the
 * SYSTEM_ALERT_WINDOW permission — one fewer sensitive permission to ask for, for exactly
 * the same result.
 *
 * The design is friction rather than a wall. It does not lock you out or make you justify
 * yourself; it puts a few seconds and one honest sentence between you and the next flick,
 * which is usually enough to break the trance. If you meant to be there, you carry on and
 * it costs you five seconds. Apps that hard-block get uninstalled by the end of the week.
 */
object FocusOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null

    fun show(
        service: AccessibilityService,
        packageName: String,
        scrollingMinutes: Int,
        trigger: String?,
    ) {
        main.post {
            if (current != null) return@post
            val wm = service.getSystemService(WindowManager::class.java) ?: return@post

            val root = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F2101014"))
                setPadding(72, 0, 72, 0)
                gravity = Gravity.CENTER
            }

            root.addView(TextView(service).apply {
                text = "You've been scrolling for $scrollingMinutes minutes."
                textSize = 26f
                setTextColor(Color.WHITE)
            })

            // The line that makes this different from every other screen-time nag: it can
            // say *how you got here*, because the same app saw the notification.
            trigger?.let {
                root.addView(TextView(service).apply {
                    text = it
                    textSize = 16f
                    setTextColor(Color.parseColor("#B8C4DC"))
                    setPadding(0, 28, 0, 0)
                })
            }

            val continueButton = Button(service).apply {
                text = "Keep scrolling"
                isEnabled = false
                alpha = 0.4f
            }
            root.addView(Button(service).apply {
                text = "Close this app"
                setOnClickListener {
                    dismiss(wm)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 56 })

            root.addView(continueButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 16 })

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )

            runCatching { wm.addView(root, params) }.onFailure { return@post }
            current = root

            // A short delay before "keep scrolling" works. Long enough to interrupt the
            // reflex, short enough not to be punishment.
            var remaining = DELAY_SECONDS
            val tick = object : Runnable {
                override fun run() {
                    remaining--
                    if (remaining <= 0) {
                        continueButton.text = "Keep scrolling"
                        continueButton.isEnabled = true
                        continueButton.alpha = 1f
                        continueButton.setOnClickListener { dismiss(wm) }
                    } else {
                        continueButton.text = "Keep scrolling ($remaining)"
                        main.postDelayed(this, 1_000)
                    }
                }
            }
            main.postDelayed(tick, 1_000)
            continueButton.text = "Keep scrolling ($remaining)"
        }
    }

    private fun dismiss(wm: WindowManager) {
        current?.let { runCatching { wm.removeView(it) } }
        current = null
    }

    private const val DELAY_SECONDS = 5
}
