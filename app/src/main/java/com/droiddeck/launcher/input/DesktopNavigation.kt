package com.droiddeck.launcher.input

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout

internal object NavigationPlacement {
    fun clamp(value: Float, available: Float) = value.coerceIn(0f, available.coerceAtLeast(0f))
}

/** One light tap per press. The view's own feedback request follows the system setting and the window's
 * feedback flags, and when that request does not reach the platform (no attached window, or the flag that
 * enables feedback is withheld) the vibrator plays the same effect the platform would have. */
internal object NavigationHaptics {
    /** Overrides the system haptic setting for the fallback; null reads it (Android keeps it readable without a permission). */
    internal var systemSetting: Boolean? = null

    /** The feedback request that is made for a tap; false when the platform refused it. */
    internal var request: (View) -> Boolean = { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    fun tap(view: View): Boolean {
        if (request(view)) return true
        return fallback(view.context)
    }

    // Android keeps this setting readable without a permission; it defaults to on.
    private fun enabled(context: Context): Boolean = systemSetting
        ?: (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0)

    private fun fallback(context: Context): Boolean {
        if (!enabled(context)) return false
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return false
        return vibrate(vibrator, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    /** The vibrator's own request, so a device or service that refuses it does not count as a tap's haptic. */
    internal var vibrate: (Vibrator, VibrationEffect) -> Boolean = { vibrator, effect ->
        vibrator.vibrate(effect)
        true
    }
}

class DesktopNavigation(context: Context, private val action: (String) -> Unit) : FrameLayout(context) {
    private val prefs = context.getSharedPreferences("desktop-navigation", Context.MODE_PRIVATE)
    internal val bar = LinearLayout(context)
    private val idle = Runnable { collapse() }
    private var enabled = false
    private var held = false
    private var safeLeft = 0
    private var safeTop = 0
    private var safeRight = 0
    private var safeBottom = 0
    var collapsed = false
        private set
    private fun icon(name: String, label: String, click: () -> Unit) = Icon(context, name, label) {
        NavigationHaptics.tap(this)
        click()
    }
    val wakeHandle = icon("wake", "Show desktop controls; drag to move") { expand() }
    val gripButton = icon("grip", "Collapse controls; drag to move; long press for options") {
        // The button's own tap hides the bar under the pointer, so it always collapses and then keeps itself reachable.
        collapse(woke = true)
    }
    val homeButton = icon("home", "Plasma desktop") { invoke("home") }

    init {
        clipChildren = false
        isFocusable = false
        visibility = GONE
        // A FrameLayout sizes itself to the largest child, and only the child it shows decides that size: the hidden
        // handle alone measured the capsule 120x120 and left the bar and every button at 0,0-0,0. It carries both
        // states, so it is as wide as the bar's five buttons and as tall as one, and onMeasure keeps it there.
        layoutParams = LayoutParams(dp(48) * 5, dp(48), Gravity.TOP or Gravity.LEFT)
        wakeHandle.layoutParams = LayoutParams(dp(48), dp(48))
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        bar.addView(gripButton)
        bar.addView(icon("back", "Back in desktop") { invoke("back") })
        bar.addView(homeButton)
        bar.addView(icon("overview", "Desktop windows") { invoke("overview") })
        bar.addView(icon("keyboard", "Show or hide keyboard") { invoke("keyboard") })
        addView(bar)
        addView(wakeHandle)
        wakeHandle.visibility = GONE
        style()
        fun dragListener() = object : OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0f
            var startY = 0f
            var moving = false
            var longPress = false
            val options = Runnable { if (!moving) { longPress = true; showOptions() } }
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        held = true
                        removeCallbacks(idle)
                        downX = event.rawX; downY = event.rawY; startX = x; startY = y
                        moving = false; longPress = false
                        postDelayed(options, 600)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX; val dy = event.rawY - downY
                        if (!moving && kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) {
                            moving = true; removeCallbacks(options)
                            // A collapsed handle only has to leave its resting position once, then it follows the finger.
                            if (collapsed) parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (moving) {
                            x = startX + dx; y = startY + dy; clamp()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(options)
                        if (moving) {
                            savePosition()
                            if (collapsed) snapToEdge()
                        } else if (!longPress && event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                        held = false; scheduleIdle()
                    }
                }
                return true
            }
        }
        gripButton.setOnTouchListener(dragListener())
        wakeHandle.setOnTouchListener(dragListener())
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!collapsed) clamp() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Both states of the capsule share one frame: the bar's five buttons while it shows, the handle while it is
     * collapsed. A frame left to its children would be measured from the one child that is not GONE, so the hidden
     * bar would never give the showing one its width. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val showing = if (collapsed) wakeHandle else bar
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec).takeIf { MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED } ?: (dp(48) * bar.childCount), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.AT_MOST),
            )
        }
        setMeasuredDimension(
            resolveSize(showing.measuredWidth, widthMeasureSpec),
            resolveSize(showing.measuredHeight, heightMeasureSpec),
        )
    }

    private fun requestParentLayout() {
        var host: android.view.ViewParent? = parent
        while (host is View && host.id == NO_ID && host.parent is View) host = host.parent
        (host as? View)?.requestLayout()
    }

    fun active(on: Boolean) {
        if (enabled == on) return
        enabled = on
        visibility = if (on) VISIBLE else GONE
        // The host arranges its children by this view's own frame, and nothing else marks that frame stale when the
        // capsule changes size or goes away.
        requestParentLayout()
        if (on) {
            // A session starts with the capsule; a handle saved by a collapse is restored at its side.
            val side = prefs.getString("edge", null)
            if (side == "left" || side == "right") {
                collapsed = true
                bar.visibility = GONE
                wakeHandle.visibility = VISIBLE
                requestLayout()
                post { restorePosition() }
            } else {
                expand()
            }
        } else {
            removeCallbacks(idle)
        }
    }

    private fun invoke(name: String) {
        action(name)
        scheduleIdle()
    }

    private fun scheduleIdle() {
        removeCallbacks(idle)
        if (enabled && !held && !prefs.getBoolean("pinned", false)) postDelayed(idle, 6000)
    }

    private fun collapse(woke: Boolean = false) {
        if (!enabled || (!woke && (held || prefs.getBoolean("pinned", false)))) return
        collapsed = true
        bar.visibility = GONE
        wakeHandle.visibility = VISIBLE
        requestLayout()
        // The handle wakes at the nearest side, at the height it was left at.
        post { snapToEdge() }
    }

    /** Ends a collapsed placement at the nearest side, keeping the vertical position the user chose.
     * The frame follows x and y on the host's next layout pass. */
    internal fun snapToEdge() {
        if (!collapsed) return
        val host = parent as? View ?: return
        val handleWidth = wakeHandle.width.takeIf { it > 0 } ?: width
        toEdge(host, handleWidth)
        clamp()
        savePosition()
    }

    private fun toEdge(host: View, handleWidth: Int) {
        x = if (x - safeLeft + handleWidth / 2f < (host.width - safeLeft - safeRight) / 2f) safeLeft.toFloat()
            else (host.width - safeRight - handleWidth).coerceAtLeast(safeLeft).toFloat()
    }

    private fun expand() {
        if (collapsed) prefs.edit().remove("edge").apply()
        collapsed = false
        bar.visibility = VISIBLE
        wakeHandle.visibility = GONE
        requestLayout()
        post { restorePosition() }
        scheduleIdle()
    }

    private fun restorePosition() {
        val host = parent as? View ?: return
        val availableX = (host.width - width - safeLeft - safeRight).coerceAtLeast(0)
        val availableY = (host.height - height - safeTop - safeBottom).coerceAtLeast(0)
        val edge = prefs.getString("edge", null)
        val top = prefs.getInt("top", Int.MIN_VALUE)
        if (edge != null && top != Int.MIN_VALUE) {
            // The wake handle keeps the side it was left at and its own vertical position, also when the
            // screen is rotated or the IME changes the safe area after it was saved.
            x = if (edge == "right") (safeLeft + availableX).toFloat() else safeLeft.toFloat()
            y = safeTop + NavigationPlacement.clamp(top.toFloat(), availableY.toFloat())
        } else {
            val left = prefs.getInt("left", Int.MIN_VALUE)
            if (left != Int.MIN_VALUE && top != Int.MIN_VALUE) {
                // Stored against the frame that was saved, which is wider than the handle: keep the north-east corner it was left at.
                x = (safeLeft + left - prefs.getInt("width", width)).toFloat()
                y = safeTop + NavigationPlacement.clamp(top.toFloat(), availableY.toFloat())
            } else {
                x = safeLeft + availableX * prefs.getFloat("x", 1f)
                y = safeTop + availableY * prefs.getFloat("y", 0.8f)
            }
        }
        clamp()
    }

    private fun savePosition(storedWidth: Int = width, storedHeight: Int = height, frameWidth: Int = width) {
        val host = parent as? View ?: return
        val dx = (host.width - storedWidth - safeLeft - safeRight).coerceAtLeast(1)
        val dy = (host.height - storedHeight - safeTop - safeBottom).coerceAtLeast(1)
        val edit = prefs.edit().putFloat("x", ((x - safeLeft) / dx).coerceIn(0f, 1f))
            .putFloat("y", ((y - safeTop) / dy).coerceIn(0f, 1f))
            .putInt("left", (x - safeLeft + frameWidth).toInt()).putInt("top", (y - safeTop).toInt())
            .putInt("width", storedWidth).putInt("height", storedHeight)
        if (collapsed) edit.putString("edge", if (x - safeLeft + width / 2f < (host.width - safeLeft - safeRight) / 2f) "left" else "right")
        edit.apply()
    }

    private fun clamp() {
        val host = parent as? View ?: return
        x = safeLeft + NavigationPlacement.clamp(x - safeLeft, (host.width - width - safeRight - safeLeft).toFloat())
        y = safeTop + NavigationPlacement.clamp(y - safeTop, (host.height - height - safeBottom - safeTop).toFloat())
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            safeLeft = safe.left; safeTop = safe.top; safeRight = safe.right; safeBottom = safe.bottom
        }
        post { clamp() }
        return super.onApplyWindowInsets(insets)
    }

    private fun style() {
        val opacity = if (prefs.getBoolean("subtle", false)) 130 else 205
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(Color.argb(opacity, 25, 30, 38))
            setStroke(dp(1), Color.argb(80, 220, 230, 245))
        }
        elevation = dp(5).toFloat()
        for (i in 0 until bar.childCount) (bar.getChildAt(i) as? Icon)?.small = prefs.getBoolean("small", true)
        wakeHandle.small = true
        invalidate()
    }

    private fun showOptions() {
        held = true; removeCallbacks(idle)
        val labels = arrayOf("Always visible", "Smaller icons (48 dp touch targets)", "More transparent")
        val keys = arrayOf("pinned", "small", "subtle")
        val checked = keys.map { prefs.getBoolean(it, it == "small") }.toBooleanArray()
        AlertDialog.Builder(context).setTitle("Desktop controls")
            .setMultiChoiceItems(labels, checked) { _, index, on ->
                prefs.edit().putBoolean(keys[index], on).apply(); style()
            }.setNeutralButton("Reset position") { _, _ -> prefs.edit().remove("x").remove("y").apply(); restorePosition() }
            .setPositiveButton("Done", null).setOnDismissListener { held = false; scheduleIdle() }.show()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { held = true; removeCallbacks(idle) }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            held = false; scheduleIdle()
        }
        return handled
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { if (collapsed) clamp() else restorePosition() }
    }

    override fun onDetachedFromWindow() { removeCallbacks(idle); super.onDetachedFromWindow() }

    class Icon(context: Context, private val icon: String, label: String, click: () -> Unit) : View(context) {
        var small = true
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235, 240, 248); style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 1.7f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        init {
            contentDescription = label; isClickable = true
            val size = (48 * resources.displayMetrics.density).toInt()
            // The bar is a LinearLayout: a FrameLayout.LayoutParams (what `layoutParams` holds in a FrameLayout)
            // loses its width when the row lays it out, and the row then collapses onto its first button.
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { click() }
        }
        override fun onDraw(canvas: Canvas) {
            canvas.save(); canvas.translate(width / 2f, height / 2f)
            val scale = resources.displayMetrics.density * if (small) 0.8f else 1f
            canvas.scale(scale, scale)
            when (icon) {
                "home" -> { canvas.drawCircle(0f, 0f, 9f, paint) }
                "back" -> { val p = Path(); p.moveTo(4f, -8f); p.lineTo(-4f, 0f); p.lineTo(4f, 8f); canvas.drawPath(p, paint) }
                "overview" -> { canvas.drawRoundRect(-9f, -9f, 9f, 9f, 3f, 3f, paint); canvas.drawLine(-3f, -8f, -3f, 8f, paint) }
                "keyboard" -> {
                    canvas.drawRoundRect(-11f, -7f, 11f, 7f, 2f, 2f, paint)
                    for (row in 0..1) for (col in 0..3) canvas.drawPoint(-7f + col * 4.5f, -3f + row * 4f, paint)
                    canvas.drawLine(-5f, 4f, 5f, 4f, paint)
                }
                "grip" -> { for (row in -1..1) for (col in 0..1) canvas.drawPoint(-2f + col * 4f, row * 4f, paint) }
                else -> { canvas.drawLine(-2f, -10f, -2f, 10f, paint); canvas.drawLine(3f, -6f, 3f, 6f, paint) }
            }
            canvas.restore()
        }
    }
}
