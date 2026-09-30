package de.kf.blitztext

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaRecorder
import android.os.SystemClock
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.content.res.Configuration

class OverlayService : Service() {
    private enum class Phase { IDLE, RECORDING, PROCESSING }

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var settings: Settings
    private val main = Handler(Looper.getMainLooper())
    private var worker = Executors.newSingleThreadExecutor()
    private var processingToken = 0
    private var recordingStarted = 0L
    private var recordingTarget: String? = null
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var phase = Phase.IDLE
    private var downX = 0f
    private var downY = 0f
    private var initialX = 0
    private var initialY = 0
    private lateinit var gesture: BubbleGesture
    private var fan: FrameLayout? = null
    private val modeObserver = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "mode") { closeFan(); render() }
    }
    private val longPress = Runnable {
        if (phase == Phase.IDLE && gesture.longPress(SystemClock.uptimeMillis())) toggleFan()
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        settings.observeMode(modeObserver)
        gesture = BubbleGesture(ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("blitztext", "TapStop-Blase", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !AndroidSettings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(1, notification())
            if (!::bubble.isInitialized) addBubble()
            running = true
        } catch (e: Exception) {
            toast("Blase konnte nicht gestartet werden: ${e.localizedMessage}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "blitztext")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("TapStop")
            .setContentText("Blase ist aktiv")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // touch() delegates only confirmed taps to performClick().
    private fun addBubble() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val margin = (12 * resources.displayMetrics.density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.bubbleRight) screenWidth - size - margin else margin
            y = settings.bubbleY.takeIf { it >= 0 }?.coerceIn(margin, screenHeight - size - margin)
                ?: screenHeight / 2
        }
        bubble = object : TextView(this) {
            override fun performClick(): Boolean {
                super.performClick()
                if (fan != null) closeFan() else onTap()
                return true
            }
        }.apply {
            isClickable = true
            gravity = Gravity.CENTER
            textSize = 23f
            setTextColor(Color.WHITE)
            elevation = 12 * resources.displayMetrics.density
            contentDescription = "TapStop: Aufnahme starten"
            setOnTouchListener { _, event -> touch(event) }
        }
        render()
        windowManager.addView(bubble, params)
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
        previous?.let { windowManager.removeView(it) }
    }

    private fun toggleFan() {
        if (fan != null) { closeFan(); return }
        if (phase != Phase.IDLE) return
        val root = object : FrameLayout(this) {
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
        fan = root
        // A touch-modal, non-focusable window swallows the complete outside gesture.
        // It never steals the editor focus used by the existing insertion path.
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
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
            val satellite = TextView(this).apply {
                gravity = Gravity.CENTER
                text = getString(R.string.mode_satellite, mode.symbol, mode.label)
                textSize = 12f
                setTextColor(Color.WHITE)
                contentDescription = "Modus ${mode.label} auswählen"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (mode == settings.mode) Color.rgb(53, 100, 184) else Color.rgb(65, 71, 85))
                    setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
                }
                setOnClickListener { settings.mode = mode; closeFan(); render() }
            }
            root.addView(satellite)
        }
        windowManager.addView(root, layout)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.removeCallbacks(longPress)
        gesture.cancel()
        closeFan()
        if (::bubble.isInitialized) snapToEdge()
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

    private fun onTap() {
        when (phase) {
            Phase.IDLE -> startRecording()
            Phase.RECORDING -> stopRecording()
            Phase.PROCESSING -> Unit
        }
    }

    private fun startRecording() {
        val key = settings.apiKey()
        if (key.isBlank()) { toast("Bitte zuerst den ${settings.provider.label} API-Key speichern."); return }
        TextInsertService.instance?.rememberFocusedField()
        recordingTarget = TextInsertService.instance?.statisticsTargetPackage()
        val file = File(cacheDir, "recording-${System.currentTimeMillis()}.m4a")
        try {
            val newRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder = newRecorder
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(128_000)
            newRecorder.setAudioSamplingRate(44_100)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
            recordingStarted = SystemClock.elapsedRealtime()
            audioFile = file
            phase = Phase.RECORDING
            vibrate()
            render()
        } catch (e: Exception) {
            recorder?.release(); recorder = null
            file.delete()
            phase = Phase.IDLE
            render()
            toast("Aufnahme fehlgeschlagen: ${e.localizedMessage}")
        }
    }

    private fun stopRecording() {
        val file = audioFile
        val recordingEnded = SystemClock.elapsedRealtime()
        val recordingMs = (recordingEnded - recordingStarted).coerceAtLeast(0)
        try {
            recorder?.stop()
            recorder?.release(); recorder = null
            audioFile = null
            if (file == null || file.length() == 0L) error("Keine Aufnahme vorhanden.")
            phase = Phase.PROCESSING
            vibrate()
            render()
            val mode = settings.mode
            val provider = settings.provider
            val key = settings.apiKey(provider)
            val groqModel = settings.groqModel
            val token = ++processingToken
            val client = ApiClient(provider, groqModel)
            val rawTranscript = AtomicReference<String?>(null)
            val metrics = AtomicReference(DictationStat(
                timestamp = System.currentTimeMillis(), recordingMs = recordingMs,
                provider = provider.label,
                model = if (provider == Provider.OPENAI) "whisper-1" else groqModel.id,
                mode = mode.name, targetPackage = recordingTarget
            ))
            main.postDelayed({
                if (token == processingToken && phase == Phase.PROCESSING) {
                    processingToken++
                    UsageStats.get(this).record(metrics.get())
                    client.cancel()
                    worker.shutdownNow()
                    worker = Executors.newSingleThreadExecutor()
                    file.delete()
                    val raw = rawTranscript.get()
                    if (raw != null) {
                        copy(withTrailingSpace(raw))
                        toast("Überarbeitung dauerte zu lange. Rohtranskript wurde kopiert.")
                    } else toast("Transkription dauerte zu lange. Bitte erneut versuchen.")
                    reset()
                }
            }, if (mode.usesRewrite) 140_000 else 90_000)
            worker.execute {
                var raw: String? = null
                try {
                    val sttStarted = SystemClock.elapsedRealtime()
                    raw = client.transcribe(file, key)
                    val sttMs = SystemClock.elapsedRealtime() - sttStarted
                    metrics.set(metrics.get().copy(rawWords = countWords(raw), sttMs = sttMs))
                    rawTranscript.set(raw)
                    val rewriteStarted = SystemClock.elapsedRealtime()
                    val result = if (mode.usesRewrite) client.rewrite(raw, key, mode) else raw
                    val finished = SystemClock.elapsedRealtime()
                    val completed = metrics.get().copy(
                        finalWords = countWords(result),
                        rewriteMs = if (mode.usesRewrite) finished - rewriteStarted else null,
                        totalMs = finished - recordingEnded, success = true
                    )
                    main.post {
                        if (token == processingToken && phase == Phase.PROCESSING) {
                            deliver(result)
                            UsageStats.get(this).record(completed)
                        }
                    }
                } catch (e: Exception) {
                    val transcript = raw
                    main.post {
                        if (token != processingToken || phase != Phase.PROCESSING) return@post
                        UsageStats.get(this).record(metrics.get())
                        if (transcript != null) {
                            copy(withTrailingSpace(transcript))
                            toast("Überarbeitung fehlgeschlagen; Rohtranskript kopiert: ${e.localizedMessage}")
                        } else toast("Transkription fehlgeschlagen: ${e.localizedMessage}")
                        reset()
                    }
                } finally { file.delete() }
            }
        } catch (e: Exception) {
            recorder?.release(); recorder = null
            audioFile = null
            file?.delete()
            toast("Aufnahme fehlgeschlagen: ${e.localizedMessage}")
            reset()
        }
    }

    private fun deliver(text: String) {
        val ready = withTrailingSpace(text)
        try {
            if (!TextInsertService.instance.orFalseInsert(ready)) {
                copy(ready)
                toast("Text in Zwischenablage kopiert. Bitte einfügen.")
            }
        } catch (_: Exception) {
            copy(ready)
            toast("Text in Zwischenablage kopiert. Bitte einfügen.")
        } finally { reset() }
    }

    private fun TextInsertService?.orFalseInsert(text: String): Boolean = this?.insert(text) == true

    private fun withTrailingSpace(text: String): String =
        if (text.isNotEmpty() && !text.last().isWhitespace()) "$text " else text

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TapStop", text))
    }

    private fun reset() { phase = Phase.IDLE; render() }

    private fun render() {
        if (!::bubble.isInitialized) return
        val color = when (phase) {
            Phase.IDLE -> Color.rgb(65, 71, 85)
            Phase.RECORDING -> Color.rgb(211, 48, 55)
            Phase.PROCESSING -> Color.rgb(53, 100, 184)
        }
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
        }
        bubble.text = getString(R.string.mode_bubble, settings.mode.symbol,
            when (phase) { Phase.IDLE -> "🎙"; Phase.RECORDING -> "■"; Phase.PROCESSING -> "…" })
        bubble.text = android.text.SpannableString(bubble.text).apply {
            setSpan(android.text.style.RelativeSizeSpan(0.6f), 0, settings.mode.symbol.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        bubble.contentDescription = "${settings.mode.label}. " + when (phase) {
            Phase.IDLE -> "TapStop: Aufnahme starten"
            Phase.RECORDING -> "TapStop: Aufnahme läuft, zum Beenden tippen"
            Phase.PROCESSING -> "TapStop: Text wird verarbeitet"
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        main.removeCallbacks(longPress)
        settings.removeModeObserver(modeObserver)
        closeFan()
        processingToken++
        recorder?.runCatching { stop() }
        recorder?.release(); recorder = null
        audioFile?.delete(); audioFile = null
        if (::bubble.isInitialized) windowManager.removeView(bubble)
        worker.shutdownNow()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { @Volatile var running = false; private set }
}
