package de.kf.tapstop

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import de.kf.tapstop.OverlayService.Phase

internal class BubbleOverlay(
    private val context: TextInsertService,
    val recording: OverlayService
) {
    // The connected AccessibilityService supplies the system's accessibility overlay token.
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val resources get() = context.resources
    private val settings = recording.settings
    private val phase get() = recording.phase
    private val main = Handler(Looper.getMainLooper())
    private lateinit var bubble: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var downX = 0f
    private var downY = 0f
    private var initialX = 0
    private var initialY = 0
    private val gesture = BubbleGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var fan: FrameLayout? = null
    private val modeObserver = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "mode") { closeFan(); render() }
    }
    private val longPress = Runnable {
        if (phase == Phase.IDLE && gesture.longPress(SystemClock.uptimeMillis())) toggleFan()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // touch() delegates only confirmed taps to performClick().
    fun attach() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val margin = (12 * resources.displayMetrics.density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            title = context.getString(R.string.bubble_window)
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.bubbleRight) screenWidth - size - margin else margin
            y = settings.bubbleY.takeIf { it >= 0 }?.coerceIn(margin, screenHeight - size - margin)
                ?: screenHeight / 2
        }
        bubble = object : TextView(context) {
            override fun performClick(): Boolean {
                super.performClick()
                if (fan != null) closeFan() else recording.onTap()
                return true
            }
        }.apply {
            isClickable = true
            gravity = Gravity.CENTER
            textSize = 23f
            setTextColor(Color.WHITE)
            elevation = 12 * resources.displayMetrics.density
            contentDescription = context.getString(R.string.bubble_record_start)
            setOnTouchListener { _, event -> touch(event) }
        }
        render()
        windowManager.addView(bubble, params)
        settings.observeMode(modeObserver)
    }

    private fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(longPress)
                downX = event.rawX; downY = event.rawY
                initialX = params.x; initialY = params.y
                gesture.down(event.eventTime, phase == Phase.IDLE)
                if (phase == Phase.IDLE) main.postAtTime(longPress, event.eventTime + BubbleGesture.LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                moveGesture(event)
            }
            MotionEvent.ACTION_UP -> {
                moveGesture(event)
                main.removeCallbacks(longPress)
                when (gesture.up(event.eventTime)) {
                    BubbleGesture.Release.TAP -> bubble.performClick()
                    BubbleGesture.Release.LONG_PRESS -> toggleFan()
                    BubbleGesture.Release.DRAG -> snapToEdge()
                    BubbleGesture.Release.NONE -> Unit
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                val wasDragging = gesture.state == BubbleGesture.State.DRAGGING
                gesture.cancel()
                if (wasDragging) closeFan()
            }
        }
        return true
    }

    private fun moveGesture(event: MotionEvent) {
        val dx = event.rawX - downX
        val dy = event.rawY - downY
        gesture.move(dx, dy)
        if (gesture.state == BubbleGesture.State.DRAGGING) {
            main.removeCallbacks(longPress)
            // Keep an open fan's input window until UP so removing it cannot lose the stream.
            fan?.let { root -> for (i in 0 until root.childCount) root.getChildAt(i).visibility = View.INVISIBLE }
            params.x = (initialX + dx).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
            params.y = (initialY + dy).toInt().coerceIn(0, (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0))
            windowManager.updateViewLayout(bubble, params)
        }
    }

    private fun closeFan() {
        val previous = fan
        fan = null
        previous?.let { windowManager.removeViewImmediate(it) }
    }

    private fun toggleFan() {
        if (fan != null) { closeFan(); return }
        if (phase != Phase.IDLE) return
        val root = object : FrameLayout(context) {
            private var satelliteSize = 0
            private var gap = 0

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = View.MeasureSpec.getSize(widthMeasureSpec)
                val height = View.MeasureSpec.getSize(heightMeasureSpec)
                setMeasuredDimension(width, height)
                gap = minOf((8 * resources.displayMetrics.density).toInt(), height / 8)
                satelliteSize = minOf((52 * resources.displayMetrics.density).toInt(),
                    (height - 3 * gap) / 4, width / 3).coerceAtLeast(1)
                val spec = View.MeasureSpec.makeMeasureSpec(satelliteSize, View.MeasureSpec.EXACTLY)
                for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
            }

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                val origin = IntArray(2); getLocationOnScreen(origin)
                val location = IntArray(2); bubble.getLocationOnScreen(location)
                val positions = fanPositions(width, height, location[0] - origin[0],
                    location[1] - origin[1], bubble.width, satelliteSize, gap)
                for (i in 0 until childCount) {
                    val point = positions[i]
                    getChildAt(i).layout(point.x, point.y, point.x + satelliteSize, point.y + satelliteSize)
                }
            }

            override fun performClick(): Boolean {
                super.performClick()
                closeFan()
                return true
            }
        }
        // A touch-modal, non-focusable window swallows the complete outside gesture.
        // It never steals the editor focus used by the existing insertion path.
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            title = context.getString(R.string.mode_picker_window)
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        var touchingBubble = false
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val location = IntArray(2); bubble.getLocationOnScreen(location)
                touchingBubble = event.rawX >= location[0] && event.rawX < location[0] + bubble.width &&
                    event.rawY >= location[1] && event.rawY < location[1] + bubble.height
            }
            if (touchingBubble) touch(event)
            else if (event.actionMasked == MotionEvent.ACTION_UP) root.performClick()
            true
        }
        // Attach children before the first traversal. Adding them from a layout listener
        // can lose the next layout request, leaving every satellite at 0 × 0.
        Mode.entries.forEach { mode ->
            val satellite = TextView(context).apply {
                gravity = Gravity.CENTER
                text = context.getString(R.string.mode_satellite, mode.symbol, context.getString(mode.labelRes))
                textSize = 12f
                setTextColor(Color.WHITE)
                contentDescription = context.getString(R.string.select_mode, context.getString(mode.labelRes))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (mode == settings.mode) Color.rgb(53, 100, 184) else Color.rgb(65, 71, 85))
                    setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
                }
                setOnClickListener { settings.mode = mode; closeFan(); render() }
            }
            root.addView(satellite)
        }
        try {
            windowManager.addView(root, layout)
            fan = root
        } catch (e: WindowManager.BadTokenException) {
            android.util.Log.w("TapStop", "Accessibility overlay token no longer valid", e)
        }
    }

    fun onConfigurationChanged() {
        main.removeCallbacks(longPress)
        gesture.cancel()
        closeFan()
        if (::bubble.isInitialized) {
            params.title = context.getString(R.string.bubble_window)
            snapToEdge()
            render()
        }
    }

    private fun snapToEdge() {
        closeFan()
        val right = params.x + params.width / 2 >= resources.displayMetrics.widthPixels / 2
        val margin = (12 * resources.displayMetrics.density).toInt()
        params.x = if (right) resources.displayMetrics.widthPixels - params.width - margin else margin
        windowManager.updateViewLayout(bubble, params)
        settings.bubbleRight = right
        settings.bubbleY = params.y
    }

    fun render() {
        val color = when (phase) {
            Phase.IDLE -> Color.rgb(65, 71, 85)
            Phase.RECORDING -> Color.rgb(211, 48, 55)
            Phase.PREPARING, Phase.PROCESSING -> Color.rgb(53, 100, 184)
        }
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        bubble.text = context.getString(R.string.mode_bubble, settings.mode.symbol,
            when (phase) { Phase.IDLE -> "🎙"; Phase.RECORDING -> "■"; Phase.PREPARING, Phase.PROCESSING -> "…" })
        bubble.text = android.text.SpannableString(bubble.text).apply {
            setSpan(android.text.style.RelativeSizeSpan(0.6f), 0, settings.mode.symbol.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val description = when (phase) {
            Phase.IDLE -> context.getString(R.string.bubble_record_start)
            Phase.RECORDING -> context.getString(R.string.bubble_record_stop)
            Phase.PREPARING -> context.getString(R.string.bubble_preparing)
            Phase.PROCESSING -> context.getString(R.string.bubble_processing)
        }
        bubble.contentDescription = context.getString(R.string.bubble_accessibility_status,
            context.getString(settings.mode.labelRes), description)
    }

    fun detach() {
        main.removeCallbacks(longPress)
        gesture.cancel()
        settings.removeModeObserver(modeObserver)
        closeFan()
        windowManager.removeViewImmediate(bubble)
    }
}
