package com.captionoverlay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs

/**
 * Runs the whole overlay experience:
 *  - a draggable floating subtitle bar (captions land here)
 *  - a draggable floating "chat head" that expands into a small action menu
 *  - a continuous speech-recognition loop that keeps the captions flowing
 *
 * Speech recognition uses Android's built-in SpeechRecognizer, preferring the
 * on-device model (free, works with no internet on most modern phones). If you
 * want cloud-grade accuracy instead, swap RecognizerBackend for a client built
 * on Google's open-sourced Live Transcribe library
 * (https://github.com/google/live-transcribe-speech-engine), which talks to
 * the paid Cloud Speech-to-Text API — see the README for notes on that swap.
 */
class OverlayService : Service(), RecognitionListener {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())

    private var subtitleRoot: View? = null
    private var subtitleText: TextView? = null
    private var subtitleParams: WindowManager.LayoutParams? = null

    private var menuRoot: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var subMenuContainer: View? = null
    private var isMenuExpanded = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )

        addSubtitleOverlay()
        addMenuOverlay()
        setupRecognizer()

        if (prefs.micEnabled) startRecognitionLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        safeRemoveView(subtitleRoot)
        safeRemoveView(menuRoot)
        MainActivity.isServiceRunning = false
    }

    private fun safeRemoveView(view: View?) {
        try {
            if (view != null) windowManager.removeView(view)
        } catch (_: Exception) {
            // View was already detached — nothing to do.
        }
    }

    // ---------------------------------------------------------------------
    // Floating subtitle bar
    // ---------------------------------------------------------------------

    private fun addSubtitleOverlay() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_subtitle, null)
        subtitleRoot = view
        subtitleText = view.findViewById(R.id.textSubtitle)
        subtitleText?.textSize = prefs.currentFontSizeSp()
        subtitleText?.background?.alpha = (prefs.currentOpacity() * 255).toInt()

        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = (metrics.heightPixels * 0.78).toInt()
        subtitleParams = params

        // Only vertical dragging for the caption bar — keeps it centered like
        // real captioning tools, users just slide it up/down out of the way.
        val dragHandle: ImageView = view.findViewById(R.id.dragHandleSubtitle)
        var initialY = 0
        var initialTouchY = 0f
        dragHandle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(subtitleRoot, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
    }

    // ---------------------------------------------------------------------
    // Expandable floating menu ("chat head" style)
    // ---------------------------------------------------------------------

    private fun addMenuOverlay() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_menu, null)
        menuRoot = view
        subMenuContainer = view.findViewById(R.id.subMenuContainer)

        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = metrics.widthPixels - dp(80)
        params.y = dp(120)
        menuParams = params

        setupFabDragAndClick(view, params)
        setupMenuActions(view)

        windowManager.addView(view, params)
    }

    private fun setupFabDragAndClick(root: View, params: WindowManager.LayoutParams) {
        val fab: ImageButton = root.findViewById(R.id.btnMainFab)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        fab.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(menuRoot, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - initialTouchX) > dp(6) ||
                        abs(event.rawY - initialTouchY) > dp(6)
                    if (!moved) {
                        v.performClick()
                        toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleMenu() {
        val container = subMenuContainer ?: return
        isMenuExpanded = !isMenuExpanded
        if (isMenuExpanded) {
            container.visibility = View.VISIBLE
            container.alpha = 0f
            container.translationY = -20f
            container.animate().alpha(1f).translationY(0f).setDuration(160).start()
        } else {
            container.animate().alpha(0f).translationY(-20f).setDuration(140)
                .withEndAction { container.visibility = View.GONE }.start()
        }
    }

    private fun setupMenuActions(root: View) {
        val btnToggleMic: ImageButton = root.findViewById(R.id.btnToggleMic)
        val labelToggleMic: TextView = root.findViewById(R.id.labelToggleMic)
        val btnFontSize: ImageButton = root.findViewById(R.id.btnFontSize)
        val btnLanguage: ImageButton = root.findViewById(R.id.btnLanguage)
        val labelLanguage: TextView = root.findViewById(R.id.labelLanguage)
        val btnOpacity: ImageButton = root.findViewById(R.id.btnOpacity)
        val btnClose: ImageButton = root.findViewById(R.id.btnClose)

        fun refreshMicButton() {
            btnToggleMic.setImageResource(if (prefs.micEnabled) R.drawable.ic_mic_off else R.drawable.ic_mic)
            labelToggleMic.setText(if (prefs.micEnabled) R.string.menu_mic_on else R.string.menu_mic_off)
        }
        refreshMicButton()
        labelLanguage.text = prefs.supportedLanguages.first { it.first == prefs.language }.second

        btnToggleMic.setOnClickListener {
            prefs.micEnabled = !prefs.micEnabled
            refreshMicButton()
            if (prefs.micEnabled) {
                startRecognitionLoop()
            } else {
                isListening = false
                speechRecognizer?.cancel()
                subtitleText?.text = getString(R.string.menu_mic_off)
            }
        }

        btnFontSize.setOnClickListener {
            subtitleText?.textSize = prefs.cycleFontSize()
        }

        btnLanguage.setOnClickListener {
            val newLang = prefs.cycleLanguage()
            labelLanguage.text = prefs.supportedLanguages.first { it.first == newLang }.second
            // Restart recognition immediately so the new language takes effect.
            if (prefs.micEnabled) {
                isListening = false
                speechRecognizer?.cancel()
                mainHandler.postDelayed({ startRecognitionLoop() }, 200)
            }
        }

        btnOpacity.setOnClickListener {
            val opacity = prefs.cycleOpacity()
            subtitleText?.background?.alpha = (opacity * 255).toInt()
        }

        btnClose.setOnClickListener {
            stopSelf()
        }
    }

    // ---------------------------------------------------------------------
    // Speech recognition — continuous loop built on Android's SpeechRecognizer
    // ---------------------------------------------------------------------

    private fun setupRecognizer() {
        speechRecognizer =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        speechRecognizer?.setRecognitionListener(this)
    }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

    private fun startRecognitionLoop() {
        if (!prefs.micEnabled) return
        isListening = true
        try {
            speechRecognizer?.startListening(buildRecognizerIntent())
        } catch (_: Exception) {
            scheduleRestart(400)
        }
    }

    private fun scheduleRestart(delayMs: Long = 150) {
        if (!isListening) return
        mainHandler.postDelayed({
            if (isListening) {
                speechRecognizer?.cancel()
                try {
                    speechRecognizer?.startListening(buildRecognizerIntent())
                } catch (_: Exception) {
                    // Will retry on the next loop tick.
                }
            }
        }, delayMs)
    }

    private fun updateSubtitle(text: String, isPartial: Boolean) {
        mainHandler.post {
            subtitleText?.text = text
            subtitleText?.setTextColor(
                resources.getColor(
                    if (isPartial) R.color.subtitle_partial_text else R.color.subtitle_text,
                    theme
                )
            )
        }
    }

    override fun onReadyForSpeech(params: android.os.Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}

    override fun onError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(100)
            SpeechRecognizer.ERROR_BUSY -> scheduleRestart(300)
            else -> scheduleRestart(500)
        }
    }

    override fun onResults(results: android.os.Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) updateSubtitle(text, isPartial = false)
        scheduleRestart(60)
    }

    override fun onPartialResults(partialResults: android.os.Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) updateSubtitle(text, isPartial = true)
    }

    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

    // ---------------------------------------------------------------------
    // Notification (required for a microphone foreground service)
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_stop), stopPendingIntent)
            .build()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val CHANNEL_ID = "caption_overlay_channel"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.captionoverlay.app.ACTION_STOP"
    }
}
