package io.github.sebastianyousef.heed.focus

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * How Heed draws over another app, and how it sends you home.
 *
 * There are two ways to do both, and which one is available depends on a decision the
 * user makes for reasons that have nothing to do with Heed:
 *
 *  - An accessibility service can draw TYPE_ACCESSIBILITY_OVERLAY and call
 *    GLOBAL_ACTION_HOME, needing no extra permission at all. This is the better path.
 *  - Anything else needs SYSTEM_ALERT_WINDOW ("Display over other apps") for the overlay,
 *    and uses a HOME intent to leave the app. Holding that permission also exempts the
 *    process from background-activity-start restrictions, which is what makes the intent
 *    land.
 *
 * The second path exists because of banking apps. Nordea, BankID and their peers refuse
 * to run while *any* accessibility service is enabled — a reasonable defence against
 * overlay-and-tap fraud, and one Heed cannot argue with or work around. Faced with the
 * choice between their bank and their screen-time limits, everyone picks the bank, and an
 * app that makes them choose is an app they uninstall. So limits, launch counts, bedtime
 * and grayscale all run through [AttentionService] on usage statistics alone, and this
 * gives them something to draw with.
 */
sealed interface Surfacer {

    fun windowManager(): WindowManager?
    fun context(): Context
    fun overlayType(): Int
    fun goHome()

    /** The accessibility path: no extra permission, and a proper home action. */
    class FromService(private val service: AccessibilityService) : Surfacer {
        override fun windowManager() = service.getSystemService(WindowManager::class.java)
        override fun context(): Context = service
        override fun overlayType() = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        override fun goHome() {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    /** The fallback path, for when the accessibility service is off. */
    class FromContext(private val context: Context) : Surfacer {
        override fun windowManager() = context.getSystemService(WindowManager::class.java)
        override fun context(): Context = context
        override fun overlayType() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

        override fun goHome() {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    companion object {
        /** Whether the fallback path can actually draw. */
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}

/**
 * The interruption itself.
 *
 * The design is friction rather than a wall. It does not lock you out or make you justify
 * yourself; it puts a few seconds and one honest sentence between you and the next flick,
 * which is usually enough to break the trance. If you meant to be there, you carry on and
 * it costs you five seconds. Apps that hard-block get uninstalled by the end of the week.
 */
object FocusOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null

    fun block(service: AccessibilityService, headline: String, detail: String) =
        block(Surfacer.FromService(service), headline, detail)

    /**
     * Back out of one screen, without leaving the app.
     *
     * The right response to "you opened Spotlight" is not the home screen. You were in
     * Snapchat to message someone; throwing you out of the whole app to stop you seeing a
     * feed punishes the thing you actually came for, and it is why the first version of
     * this felt like a fight. Pressing Back drops you out of the feed and leaves you
     * exactly where you were, which is what you asked it to do.
     *
     * The message is a short banner rather than a full-screen wall for the same reason:
     * a wall you have to wait out is friction applied to an action that has already been
     * undone.
     */
    fun bounce(service: AccessibilityService, headline: String, detail: String) {
        main.post {
            val wm = service.getSystemService(WindowManager::class.java) ?: return@post
            dismiss(wm)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

            val root = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#EE16161C"))
                setPadding(56, 40, 56, 40)
                gravity = Gravity.CENTER
            }
            root.addView(TextView(service).apply {
                text = headline
                textSize = 19f
                setTextColor(Color.WHITE)
            })
            root.addView(TextView(service).apply {
                text = detail
                textSize = 13f
                setTextColor(Color.parseColor("#B8C4DC"))
                setPadding(0, 8, 0, 0)
            })

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP }

            if (!runCatching { wm.addView(root, params) }.onSuccess { current = root }.isSuccess) return@post
            main.postDelayed({ dismiss(wm) }, BANNER_VISIBLE_MS)
        }
    }

    /**
     * A hard stop. No continue button, because the point of BLOCK mode is that you
     * already decided, calmly, that you did not want to be here — and the version of you
     * that meets this screen is not the one who should get to overrule that.
     */
    fun block(surfacer: Surfacer, headline: String, detail: String) {
        main.post {
            val wm = surfacer.windowManager() ?: return@post
            val ctx = surfacer.context()
            dismiss(wm)
            surfacer.goHome()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F7101014"))
                setPadding(72, 0, 72, 0)
                gravity = Gravity.CENTER
            }
            root.addView(TextView(ctx).apply {
                text = headline
                textSize = 26f
                setTextColor(Color.WHITE)
            })
            root.addView(TextView(ctx).apply {
                text = detail
                textSize = 16f
                setTextColor(Color.parseColor("#B8C4DC"))
                setPadding(0, 24, 0, 0)
            })

            if (!add(wm, root, surfacer.overlayType())) return@post
            main.postDelayed({ dismiss(wm) }, BLOCK_VISIBLE_MS)
        }
    }

    fun show(
        service: AccessibilityService,
        packageName: String,
        scrollingMinutes: Int,
        trigger: String?,
    ) = show(Surfacer.FromService(service), scrollingMinutes, trigger)

    fun show(surfacer: Surfacer, scrollingMinutes: Int, trigger: String?) {
        main.post {
            if (current != null) return@post
            val wm = surfacer.windowManager() ?: return@post
            val ctx = surfacer.context()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F2101014"))
                setPadding(72, 0, 72, 0)
                gravity = Gravity.CENTER
            }

            root.addView(TextView(ctx).apply {
                text = "You've been scrolling for $scrollingMinutes minutes."
                textSize = 26f
                setTextColor(Color.WHITE)
            })

            // The line that makes this different from every other screen-time nag: it can
            // say *how you got here*, because the same app saw the notification.
            trigger?.let {
                root.addView(TextView(ctx).apply {
                    text = it
                    textSize = 16f
                    setTextColor(Color.parseColor("#B8C4DC"))
                    setPadding(0, 28, 0, 0)
                })
            }

            val continueButton = Button(ctx).apply {
                text = "Keep scrolling"
                isEnabled = false
                alpha = 0.4f
            }
            root.addView(Button(ctx).apply {
                text = "Close this app"
                setOnClickListener {
                    dismiss(wm)
                    surfacer.goHome()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 56 })

            root.addView(continueButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 16 })

            if (!add(wm, root, surfacer.overlayType())) return@post

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

    private fun add(wm: WindowManager, view: View, type: Int): Boolean {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        // Without the overlay permission this throws rather than returning; a block that
        // cannot draw must still not take the process down.
        return runCatching { wm.addView(view, params) }
            .onSuccess { current = view }
            .isSuccess
    }

    private fun dismiss(wm: WindowManager) {
        current?.let { runCatching { wm.removeView(it) } }
        current = null
    }

    private const val DELAY_SECONDS = 5

    /** Long enough to register why you were stopped, short enough not to trap you. */
    private const val BLOCK_VISIBLE_MS = 3_500L

    /** Long enough to read one line. The action is already undone; this only explains it. */
    private const val BANNER_VISIBLE_MS = 2_000L
}
