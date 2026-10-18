package com.shilapi.xcertplay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/**
 * The app icon as a bubble over the launcher's home screen: a tap comes back to CarPlay, a drag
 * moves it. Holding it sweeps a light across the icon and locks the place; holding it again
 * unlocks. A locked bubble wears a small padlock badge in its top-right corner, and the hold shows
 * a prompt above the bubble.
 *
 * The bubble shows while no DiPlay screen is in front and goes as soon as one is, so it never
 * covers CarPlay itself. It needs "display over other apps" (SYSTEM_ALERT_WINDOW), which the
 * dashboard map card already declares. It is drawn by the app rather than added to the launcher, so
 * it also works on a head unit whose launcher hosts only its own cards and would never list an
 * installed app (see #650 and docs/LAUNCHER_INTEGRATION.md).
 */
internal object LauncherBubble {
    const val TAG = "DiPlay-Bubble"

    private const val SHOW_DELAY_MILLIS = 600L
    private const val LONG_PRESS_MILLIS = 900L
    private const val CORNER_SHARE = 0.12f
    private const val LABEL_GAP_DP = 8

    private const val PREFS = "diplay_launcher_bubble"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_LOCKED = "locked"

    /** The icon the launcher would show for this app. */
    private fun appIcon(context: Context): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(context.packageName)
    }.getOrNull() ?: ContextCompat.getDrawable(context, R.drawable.ic_carplay)

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var root: View? = null
    private val showIfBackground = Runnable { if (!diPlayInFront()) show() }

    /** Remembers the application context, so a stopped screen can still bring the bubble back. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun permitted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Shows the bubble shortly, because a DiPlay screen is going away. Main thread. */
    fun scheduleShow() {
        main.removeCallbacks(showIfBackground)
        main.postDelayed(showIfBackground, SHOW_DELAY_MILLIS)
    }

    /** A DiPlay screen is in front: the bubble goes. Main thread. */
    fun onAppScreenShown() {
        main.removeCallbacks(showIfBackground)
        hide()
    }

    val shown: Boolean get() = root != null

    /** Adds the bubble; false when it is switched off or the permission is missing. Main thread. */
    fun show(): Boolean {
        if (root != null) return true
        // The bubble belongs on the launcher's screen, never over DiPlay's own screens.
        if (diPlayInFront()) return false
        val context = appContext ?: return false
        if (!AirPlayPersistence.loadLauncherBubble(context)) return false
        if (!permitted(context)) return false
        val windows = context.getSystemService(WindowManager::class.java) ?: return false
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val sizePercent = AirPlayPersistence.loadLauncherBubbleSize(context)
        val icon = appIcon(context)
        val iconRatio = icon?.takeIf { it.intrinsicHeight > 0 }
            ?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight }
            ?: LauncherBubbleLayout.DEFAULT_ICON_RATIO
        val initial = LauncherBubbleLayout.initialBox(metrics.widthPixels, metrics.heightPixels, sizePercent, iconRatio, density)
        val width = initial.width
        val height = initial.height
        val gap = (LABEL_GAP_DP * density).roundToInt()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lockPrompt = context.getString(R.string.settings_launcher_bubble_hold_to_lock)
        val unlockPrompt = context.getString(R.string.settings_launcher_bubble_hold_to_unlock)

        var locked = prefs.getBoolean(KEY_LOCKED, false)
        val bubble = BubbleView(context, icon, height)
        bubble.locked = locked

        val params = windowParams(width, height)
        params.gravity = Gravity.TOP or Gravity.START
        val saved = LauncherBubbleLayout.clamp(
            LauncherBubbleLayout.Box(width, height, prefs.getInt(KEY_X, initial.x), prefs.getInt(KEY_Y, initial.y)),
            metrics.widthPixels,
            metrics.heightPixels,
        )
        params.x = saved.x
        params.y = saved.y

        var downRawX = 0f
        var downRawY = 0f
        var originX = 0
        var originY = 0
        var dragged = false
        var longPressFired = false
        var press: ValueAnimator? = null
        var label: BubbleLabel? = null
        val dragSlop = (LauncherBubbleLayout.DRAG_SLOP_DP * density).roundToInt()

        fun hideLabel() {
            label?.let { view -> runCatching { windows.removeViewImmediate(view) } }
            label = null
        }

        fun showLabel(message: String) {
            hideLabel()
            val view = BubbleLabel(context, message, height)
            val place = LauncherBubbleLayout.labelBox(
                LauncherBubbleLayout.Box(width, height, params.x, params.y),
                view.pillWidth,
                view.pillHeight,
                gap,
                metrics.widthPixels,
            )
            val labelParams = windowParams(view.pillWidth, view.pillHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                x = place.x
                y = place.y
            }
            runCatching { windows.addView(view, labelParams) }
                .onSuccess { label = view }
                .onFailure { Log.w(TAG, "bubble label failed", it) }
        }

        fun stopPress() {
            press?.cancel()
            press = null
            bubble.progress = 0f
            hideLabel()
        }

        fun startPress() {
            longPressFired = false
            showLabel(if (locked) unlockPrompt else lockPrompt)
            val animator = ValueAnimator.ofFloat(0f, 1f).setDuration(LONG_PRESS_MILLIS)
            animator.addUpdateListener { bubble.progress = it.animatedValue as Float }
            animator.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    longPressFired = true
                    locked = !locked
                    prefs.edit().putBoolean(KEY_LOCKED, locked).apply()
                    bubble.locked = locked
                    bubble.progress = 0f
                    hideLabel()
                    Toast.makeText(
                        context,
                        context.getString(
                            if (locked) R.string.settings_launcher_bubble_locked
                            else R.string.settings_launcher_bubble_unlocked,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                    Log.i(TAG, if (locked) "bubble locked" else "bubble unlocked")
                }
            })
            press = animator
            animator.start()
        }

        bubble.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragged = false
                    startPress()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (LauncherBubbleLayout.isDrag(dx, dy, dragSlop)) {
                        // A move is never a long press, and a locked bubble stays where it is.
                        stopPress()
                        if (!locked) {
                            dragged = true
                            params.x = originX + dx
                            params.y = originY + dy
                            runCatching { windows.updateViewLayout(view, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopPress()
                    if (dragged) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else if (!longPressFired) {
                        openCarPlay(context)
                    }
                    dragged = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopPress()
                    dragged = false
                    true
                }
                else -> false
            }
        }

        return try {
            windows.addView(bubble, params)
            root = bubble
            Log.i(TAG, "bubble shown ${width}x$height at ${params.x},${params.y} percent=$sizePercent locked=$locked")
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "bubble failed", error)
            false
        }
    }

    /** Removes the bubble. Main thread. */
    fun hide() {
        val view = root ?: return
        root = null
        runCatching { view.context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view) }
        Log.i(TAG, "bubble hidden")
    }

    private fun windowParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

    // A DiPlay activity in front makes the process foreground; the session service alone does not.
    private fun diPlayInFront(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** CarPlay when a session is up, the settings screen otherwise. */
    private fun openCarPlay(context: Context) {
        val target = if (CarPlayGlance.snapshot().connected) {
            CarPlayHostActivity::class.java
        } else {
            DiPlayActivity::class.java
        }
        val intent = Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { Log.i(TAG, "bubble opened ${target.simpleName}") }
            .onFailure { Log.w(TAG, "bubble open failed", it) }
    }

    /** The icon, a left-to-right light sweep while holding, and a padlock badge once locked. */
    private class BubbleView(
        context: Context,
        private val logo: Drawable?,
        private val bubbleHeight: Int,
    ) : View(context) {
        private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xcc10141a.toInt() }
        private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x66ffffff
            strokeWidth = bubbleHeight * 0.02f
        }
        private val lock = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffffff.toInt() }
        private val shape = RectF()

        var progress: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        var locked: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val radius = bubbleHeight * CORNER_SHARE
            logo?.setBounds(0, 0, width, height)
            logo?.draw(canvas)
            if (progress > 0f) {
                if (sweep.shader == null) {
                    sweep.shader = LinearGradient(
                        0f, 0f, width.toFloat(), 0f,
                        0x00ffffff, 0x88ffffff.toInt(), Shader.TileMode.CLAMP,
                    )
                }
                canvas.save()
                canvas.clipRect(0f, 0f, width * progress, height.toFloat())
                shape.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(shape, radius, radius, sweep)
                canvas.restore()
            }
            if (locked) drawPadlock(canvas)
        }

        /** A small padlock in the top-right corner, drawn here so no extra asset is needed. */
        private fun drawPadlock(canvas: Canvas) {
            val size = bubbleHeight * 0.34f
            val inset = bubbleHeight * 0.06f
            val cx = width - inset - size / 2f
            val cy = inset + size / 2f
            canvas.drawCircle(cx, cy, size / 2f, badge)
            canvas.drawCircle(cx, cy, size / 2f - badgeRing.strokeWidth / 2f, badgeRing)

            val bodyWidth = size * 0.46f
            val bodyHeight = size * 0.32f
            val bodyTop = cy - size * 0.04f
            lock.style = Paint.Style.FILL
            shape.set(cx - bodyWidth / 2f, bodyTop, cx + bodyWidth / 2f, bodyTop + bodyHeight)
            canvas.drawRoundRect(shape, size * 0.07f, size * 0.07f, lock)

            val shackle = bodyWidth * 0.32f
            lock.style = Paint.Style.STROKE
            lock.strokeWidth = size * 0.11f
            lock.strokeCap = Paint.Cap.ROUND
            shape.set(cx - shackle, bodyTop - shackle, cx + shackle, bodyTop + shackle)
            canvas.drawArc(shape, 180f, 180f, false, lock)
        }
    }

    /** The hold prompt above the bubble; its window never takes touches. */
    private class BubbleLabel(context: Context, private val message: String, bubbleHeight: Int) : View(context) {
        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xe6101418.toInt() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffffffff.toInt()
            textAlign = Paint.Align.CENTER
        }
        private val shape = RectF()

        val pillHeight: Int = (bubbleHeight * 0.26f).toInt().coerceAtLeast(1)
        val pillWidth: Int

        init {
            text.textSize = pillHeight * 0.48f
            pillWidth = (text.measureText(message) + text.textSize * 1.6f).toInt()
                .coerceAtLeast((pillHeight * 1.6f).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val radius = pillHeight / 2f
            shape.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(shape, radius, radius, background)
            val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(message, width / 2f, baseline, text)
        }
    }
}
