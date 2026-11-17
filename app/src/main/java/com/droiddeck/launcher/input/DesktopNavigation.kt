package com.droiddeck.launcher.input

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Build
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

class DesktopNavigation(context: Context, private val action: (String) -> Unit) : FrameLayout(context) {
    private val prefs = context.getSharedPreferences("desktop-navigation", Context.MODE_PRIVATE)
    private val bar = LinearLayout(context)
    private val idle = Runnable { collapse() }
    private var enabled = false
    private var held = false
    private var safeLeft = 0
    private var safeTop = 0
    private var safeRight = 0
    private var safeBottom = 0
    var collapsed = false
        private set
    var hapticClicks = 0
        private set
    private fun icon(name: String, label: String, click: () -> Unit) = Icon(context, name, label) {
        hapticClicks++
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        click()
    }
    val wakeHandle = icon("wake", "Show desktop controls; drag to move") { expand() }
    val gripButton = icon("grip", "Collapse controls; drag to move; long press for options") { collapse(true) }
    val homeButton = icon("home", "Plasma desktop") { invoke("home") }

    init {
        clipChildren = false
        isFocusable = false
        visibility = GONE
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT)
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
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) {
                            moving = true; removeCallbacks(options)
                            x = startX + dx; y = startY + dy; clamp()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(options)
                        if (moving) savePosition() else if (!longPress && event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                        held = false; scheduleIdle()
                    }
                }
                return true
            }
        }
        gripButton.setOnTouchListener(dragListener())
        wakeHandle.setOnTouchListener(dragListener())
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clamp() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun active(on: Boolean) {
        if (enabled == on) return
        enabled = on
        visibility = if (on) VISIBLE else GONE
        if (on) { expand(); post { restorePosition() } } else removeCallbacks(idle)
    }

    private fun invoke(name: String) {
        action(name)
        scheduleIdle()
    }

    private fun scheduleIdle() {
        removeCallbacks(idle)
        if (enabled && !held && !prefs.getBoolean("pinned", false)) postDelayed(idle, 6000)
    }

    private fun collapse(manual: Boolean = false) {
        if (!enabled || (!manual && (held || prefs.getBoolean("pinned", false)))) return
        // The handle's own frame is where it is left; the next restore puts it back there without a jump.
        if (isShown) {
            val host = parent as? View
            val hx = (host?.width ?: 0) - wakeHandle.width - safeLeft - safeRight
            val hy = (host?.height ?: 0) - wakeHandle.height - safeTop - safeBottom
            prefs.edit().putFloat("x", ((x - safeLeft) / hx.coerceAtLeast(1)).coerceIn(0f, 1f))
                .putFloat("y", ((y - safeTop) / hy.coerceAtLeast(1)).coerceIn(0f, 1f))
                .putInt("left", (x - safeLeft).toInt()).putInt("top", (y - safeTop).toInt())
                .putInt("width", wakeHandle.width).putInt("height", wakeHandle.height).apply()
        }
        collapsed = true
        bar.visibility = GONE
        wakeHandle.visibility = VISIBLE
        requestLayout()
        post { restorePosition() }
    }

    private fun expand() {
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
        val left = prefs.getInt("left", Int.MIN_VALUE)
        val top = prefs.getInt("top", Int.MIN_VALUE)
        if (left != Int.MIN_VALUE && top != Int.MIN_VALUE) {
            // Stored against the frame that was saved, which is wider than the handle: keep the north-east corner it was left at.
            x = (safeLeft + left - prefs.getInt("width", width)).toFloat()
            y = (safeTop + top).toFloat()
        } else {
            x = safeLeft + availableX * prefs.getFloat("x", 1f)
            y = safeTop + availableY * prefs.getFloat("y", 0.8f)
        }
        clamp()
    }

    private fun savePosition(storedWidth: Int = width, storedHeight: Int = height, frameWidth: Int = width) {
        val host = parent as? View ?: return
        val dx = (host.width - storedWidth - safeLeft - safeRight).coerceAtLeast(1)
        val dy = (host.height - storedHeight - safeTop - safeBottom).coerceAtLeast(1)
        prefs.edit().putFloat("x", ((x - safeLeft) / dx).coerceIn(0f, 1f))
            .putFloat("y", ((y - safeTop) / dy).coerceIn(0f, 1f))
            .putInt("left", (x - safeLeft + frameWidth).toInt()).putInt("top", (y - safeTop).toInt())
            .putInt("width", storedWidth).putInt("height", storedHeight).apply()
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
