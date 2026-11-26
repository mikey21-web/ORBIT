package com.orbit.assistant

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class DiyaWakeService : Service() {
    private val tag = "DiyaWake"
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val running = AtomicBoolean(true)
    private val sampleRate = 16000

    private var model: Model? = null
    private var wakeRecognizer: Recognizer? = null
    private var commandRecognizer: Recognizer? = null
    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: View? = null
    private var pulse: ValueAnimator? = null
    private var screenReceiver: BroadcastReceiver? = null

    @Volatile
    private var listeningForCommand = false

    @Volatile
    private var lastWakeAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "DiyaWake onCreate")
        startForegroundWithNotification()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orbit:diya-wake").apply {
            acquire(10 * 60 * 1000L)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        registerScreenReceiver()
        Log.i(tag, "unpacking vosk model")
        StorageService.unpack(
            applicationContext,
            "model-en-us",
            "model",
            { unpacked ->
                model = unpacked
                Log.i(tag, "vosk model ready")
                try {
                    startRecognition(unpacked)
                } catch (t: Throwable) {
                    Log.e(tag, "could not start recognition", t)
                    toast("Voice engine could not start: ${t.message ?: "unknown error"}")
                    stopSelf()
                }
            },
            { error ->
                Log.e(tag, "vosk model unpack failed", error)
                toast("Voice model missing")
                stopSelf()
            },
        )
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
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        recordThread?.join(1000)
        try { recorder?.stop() } catch (_: Throwable) {}
        recorder?.release()
        wakeRecognizer?.let { runCatching { it.close() } }
        commandRecognizer?.let { runCatching { it.close() } }
        model?.let { runCatching { it.close() } }
        pulse?.cancel()
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.i(tag, "screen awake: resetting voice recognizers")
                wakeRecognizer?.let { runCatching { it.reset() } }
                commandRecognizer?.let { runCatching { it.reset() } }
            }
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun startRecognition(unpacked: Model) {
        wakeRecognizer = Recognizer(unpacked, sampleRate.toFloat(), WAKE_GRAMMAR).apply {
            runCatching { setEndpointerDelays(5.0f, 0.2f, 3.0f) }
        }
        commandRecognizer = Recognizer(unpacked, sampleRate.toFloat()).apply {
            runCatching { setEndpointerDelays(5.0f, 0.45f, 8.0f) }
        }
        startMicrophone()
        Log.i(tag, "vosk wake loop started")
    }

    private fun openRecorder(): AudioRecord {
        val format = AudioFormat.ENCODING_PCM_16BIT
        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, format
        )
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(format)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min * 2, sampleRate))
            .setPrivacySensitive(true)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("microphone unavailable")
        }
        return rec
    }

    private fun startMicrophone() {
        var active = openRecorder()
        this.recorder = active
        active.startRecording()
        recordThread = Thread {
            val buffer = ShortArray(1600)
            var consecutiveErrors = 0
            var loops = 0
            var totalSamples = 0L
            while (running.get()) {
                val read = active.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                if (read > 0) {
                    consecutiveErrors = 0
                    totalSamples += read
                    if (listeningForCommand) {
                        val rec = commandRecognizer ?: continue
                        try {
                            if (rec.acceptWaveForm(buffer, read)) {
                                val text = voskText(rec.getResult())
                                if (text.isNotBlank()) mainHandler.post { onCommand(text) }
                            }
                        } catch (t: Throwable) {
                            Log.e(tag, "command decode failed", t)
                        }
                    } else {
                        val rec = wakeRecognizer ?: continue
                        try {
                            val finalText = if (rec.acceptWaveForm(buffer, read)) {
                                voskText(rec.getResult())
                            } else {
                                ""
                            }
                            if (isWake(finalText)) {
                                rec.reset()
                                mainHandler.post { onWake() }
                            } else if (finalText.isEmpty()) {
                                val partial = voskText(rec.getPartialResult())
                                if (isWake(partial)) {
                                    rec.reset()
                                    mainHandler.post { onWake() }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e(tag, "wake decode failed", t)
                        }
                    }
                } else {
                    consecutiveErrors++
                    if (consecutiveErrors >= 25) {
                        Log.w(tag, "microphone read failing ($read); restarting AudioRecord")
                        runCatching { active.stop() }
                        runCatching { active.release() }
                        active = openRecorder()
                        this.recorder = active
                        runCatching { active.startRecording() }
                        consecutiveErrors = 0
                    }
                    Thread.sleep(20)
                }
                loops++
                if (loops % 100 == 0) Log.i(tag, "audio alive: $totalSamples samples")
                Thread.sleep(10)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun voskText(json: String): String {
        return try {
            val obj = JSONObject(json)
            val raw = obj.optString("text", obj.optString("partial", ""))
            raw.trim().lowercase(Locale.US)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun isWake(text: String): Boolean {
        if (text.isBlank()) return false
        return text.contains("hey diya") || text.contains("hey dee ya") ||
            text.contains("hey dia") || text.contains("hi diya")
    }

    private fun onWake() {
        if (listeningForCommand) return
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < 2000) return
        lastWakeAt = now
        listeningForCommand = true
        commandRecognizer?.let { runCatching { it.reset() } }
        Log.i(tag, "wake detected: HEY_DIYA")
        toast("Hey Diya heard")
        showOverlay()
    }

    private fun onCommand(text: String) {
        if (!listeningForCommand) return
        listeningForCommand = false
        removeOverlay()
        wakeRecognizer?.let { runCatching { it.reset() } }
        commandRecognizer?.let { runCatching { it.reset() } }
        toast("Heard: $text")
        sendTask(text)
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

    private fun sendTask(text: String) {
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
                val body = JSONObject()
                    .put("text", text)
                    .put("display", 0)
                    .toString()
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
        private const val WAKE_GRAMMAR =
            "[\"hey diya\", \"hey dee ya\", \"hey dia\", \"hi diya\", \"[unk]\"]"
    }
}
