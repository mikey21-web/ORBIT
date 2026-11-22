package com.orbit.assistant

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class DiyaWakeService : Service() {
    private val tag = "DiyaWake"
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val running = AtomicBoolean(true)
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: View? = null
    private var pulse: ValueAnimator? = null
    private val sampleRate = 16000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orbit:diya-wake").apply {
            acquire(10 * 60 * 1000L)
        }
        try {
            spotter = buildSpotter()
            stream = spotter!!.createStream()
            startMicrophone()
        } catch (t: Throwable) {
            Log.e(tag, "failed to start keyword spotting", t)
            mainHandler.post { toast("Hey Diya could not start: ${t.message ?: "unknown error"}") }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        recordThread?.join(1000)
        try { recorder?.stop() } catch (_: Throwable) {}
        recorder?.release()
        stream?.release()
        spotter?.release()
        recognizer?.destroy()
        pulse?.cancel()
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun buildSpotter(): KeywordSpotter {
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "kws/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
                decoder = "kws/decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
                joiner = "kws/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
            ),
            tokens = "kws/tokens.txt",
            numThreads = 1,
        )
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig = model,
            keywordsFile = "kws/keywords.txt",
            keywordsScore = 1.0f,
            keywordsThreshold = 0.1f,
        )
        return KeywordSpotter(assetManager = assets, config = config)
    }

    private fun startMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val format = AudioFormat.ENCODING_PCM_16BIT
        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, format
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            format,
            maxOf(min * 2, sampleRate),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("microphone unavailable")
        }
        this.recorder = recorder
        recorder.startRecording()
        val currentStream = stream ?: return
        recordThread = Thread {
            val buffer = ShortArray(1600)
            while (running.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                currentStream.acceptWaveform(samples, sampleRate = sampleRate)
                val kws = spotter ?: break
                while (kws.isReady(currentStream)) {
                    kws.decode(currentStream)
                    val keyword = kws.getResult(currentStream).keyword
                    if (keyword.isNotBlank()) {
                        kws.reset(currentStream)
                        Log.i(tag, "wake detected: $keyword")
                        mainHandler.post { onWake() }
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun onWake() {
        toast("Hey Diya heard")
        showOverlay()
        startListening()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Enable display over other apps for the mic button")
            return
        }
        removeOverlay()
        val glowShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setGradientType(GradientDrawable.RADIAL_GRADIENT)
            setGradientRadius(90 * resources.displayMetrics.density)
            setColors(intArrayOf(Color.argb(150, 72, 220, 255), Color.TRANSPARENT))
        }
        val glow = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(180), dp(180))
            background = glowShape
        }
        val mic = android.widget.Button(this).apply {
            text = "🎙"
            textSize = 28f
            setBackgroundColor(Color.argb(230, 18, 24, 38))
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(76), dp(76)).apply {
                gravity = Gravity.CENTER
            }
            setOnClickListener { startListening() }
        }
        val root = FrameLayout(this).apply {
            addView(glow)
            addView(mic)
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    removeOverlay()
                    true
                } else {
                    v.performClick()
                    false
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(120)
        }
        runCatching { getSystemService(WindowManager::class.java).addView(root, params) }
        overlay = root
        val animator = ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { glow.alpha = it.animatedValue as Float }
        }
        animator.start()
        pulse = animator
        mainHandler.postDelayed({ removeOverlay() }, 8000)
    }

    private fun removeOverlay() {
        pulse?.cancel()
        pulse = null
        overlay?.let { view ->
            runCatching { getSystemService(WindowManager::class.java).removeView(view) }
        }
        overlay = null
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Microphone permission is needed")
            return
        }
        recognizer?.destroy()
        val speech = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                removeOverlay()
                toast("Listening...")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                toast("Could not hear you")
            }
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty().trim()
                if (text.isNotEmpty()) sendTask(text)
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { speech.startListening(intent) }
    }

    private fun sendTask(text: String) {
        toast("Heard: $text")
        Thread {
            try {
                if (!AgentService.isRunning()) {
                    startForegroundService(Intent(this, AgentService::class.java))
                }
                val token = AgentToken.ensure(this)
                val url = URL("http://127.0.0.1:${BuildConfig.AGENT_PORT}/task")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Agent-Token", token)
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 15000
                val body = JSONObject().put("text", text).toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                runCatching { connection.errorStream?.close() }
                runCatching { connection.inputStream?.close() }
                mainHandler.post {
                    if (code == 200) toast("Working on it") else toast("Could not send command")
                }
            } catch (t: Throwable) {
                Log.e(tag, "task request failed", t)
                mainHandler.post { toast("Could not send command") }
            }
        }.start()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel("diya-wake") == null) {
            manager.createNotificationChannel(
                NotificationChannel("diya-wake", "Hey Diya", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, DiyaWakeService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, WebConsoleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = android.app.Notification.Builder(this, "diya-wake")
            .setContentTitle("ORBIT")
            .setContentText("Hey Diya is listening")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .addAction(android.app.Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        startForeground(21, notification)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.orbit.assistant.STOP_DIYA_WAKE"
    }
}
