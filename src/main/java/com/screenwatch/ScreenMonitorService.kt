package com.screenwatch

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMonitorService : Service() {

    companion object {
        const val TAG = "ScreenWatcher"
        const val CHANNEL_ID = "screen_watcher_ch"
        const val NOTIF_ID = 42
        const val ACTION_STOP_ALARM    = "com.screenwatch.STOP_ALARM"
        const val ACTION_STOP_SERVICE  = "com.screenwatch.STOP_SERVICE"
        const val ACTION_SELECT_REGION = "com.screenwatch.SELECT_REGION"
        const val ACTION_START_MONITOR = "com.screenwatch.START_MONITOR"
        const val CHANNEL_DELTA        = 10
        const val POLL_INTERVAL_MS     = 1000L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var screenW = 0
    private var screenH = 0
    private var region: IntArray = intArrayOf(0, 0, 200, 200)
    private var minChangedPixels = 10
    private var regionSelected = false
    private var isMonitoring = false
    private var previousPixels: IntArray? = null
    private val alarmActive = AtomicBoolean(false)
    private var mediaPlayer: MediaPlayer? = null
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var pollFuture: ScheduledFuture<*>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14 requires the FGS type to match what we're about to do BEFORE
        // getMediaProjection() is called. So we check the action first and promote
        // to MEDIA_PROJECTION type immediately when we know we have a token incoming.
        val action = intent?.action
        val needsProjectionType = action == ACTION_START_MONITOR
        startForegroundCompat(useMediaProjectionType = needsProjectionType)

        when (action) {
            ACTION_STOP_ALARM    -> { stopAlarm(); return START_STICKY }
            ACTION_STOP_SERVICE  -> { teardown(); stopSelf(); return START_NOT_STICKY }
            ACTION_SELECT_REGION -> { showOverlay(); return START_STICKY }
            ACTION_START_MONITOR -> {
                val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra("data")
                }
                minChangedPixels = intent.getIntExtra("threshold", 10)
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startCapture(resultCode, data)
                } else {
                    // Token invalid — drop back to dataSync so we're not stuck as mediaProjection
                    startForegroundCompat(useMediaProjectionType = false)
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { teardown(); super.onDestroy() }

    // ── Foreground service type switching ─────────────────────────────────────
    // Android 14 strictly enforces that mediaProjection type requires a valid token.
    // We use dataSync when idle (no token) and mediaProjection when capturing.

    private fun startForegroundCompat(useMediaProjectionType: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (useMediaProjectionType)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIF_ID, buildNotif(), type)
        } else {
            startForeground(NOTIF_ID, buildNotif())
        }
    }

    // ── Overlay region selector ───────────────────────────────────────────────

    private fun showOverlay() {
        removeOverlay()
        val view = object : View(this) {
            private var startX = 0f; private var startY = 0f
            private var curX = 0f;   private var curY = 0f
            private var dragging = false; private var hasInput = false

            private val dimPaint    = Paint().apply { color = Color.argb(160, 0, 0, 0) }
            private val fillPaint   = Paint().apply { color = Color.argb(30, 80, 160, 255) }
            private val borderPaint = Paint().apply {
                color = Color.argb(230, 80, 160, 255)
                style = Paint.Style.STROKE; strokeWidth = 4f
            }
            private val cornerPaint = Paint().apply { color = Color.WHITE }
            private val headPaint   = Paint().apply {
                color = Color.WHITE; textSize = 44f; textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD; setShadowLayer(6f, 0f, 2f, Color.BLACK)
            }
            private val subPaint = Paint().apply {
                color = Color.argb(200, 255, 255, 255); textSize = 30f
                textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 1f, Color.BLACK)
            }
            private val sizePaint = Paint().apply {
                color = Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
            }

            init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

            override fun onDraw(c: Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                c.drawRect(0f, 0f, w, h, dimPaint)
                if (!hasInput) {
                    c.drawText("Drag to select region", w / 2f, h / 2f - 28f, headPaint)
                    c.drawText("Tap outside selection to cancel", w / 2f, h / 2f + 28f, subPaint)
                    return
                }
                val left = minOf(startX, curX); val top = minOf(startY, curY)
                val right = maxOf(startX, curX); val bottom = maxOf(startY, curY)
                val sel = RectF(left, top, right, bottom)
                c.drawRect(sel, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
                c.drawRect(sel, fillPaint)
                c.drawRect(sel, borderPaint)
                val hs = 16f
                for ((cx, cy) in listOf(left to top, right to top, left to bottom, right to bottom))
                    c.drawCircle(cx, cy, hs, cornerPaint)
                val lw = (right - left).toInt(); val lh = (bottom - top).toInt()
                val ly = if (top > 60f) top - 16f else bottom + 48f
                c.drawText("${lw} × ${lh} px", (left + right) / 2f, ly, sizePaint)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.x; startY = e.y; curX = e.x; curY = e.y
                        dragging = true; hasInput = true; invalidate(); return true
                    }
                    MotionEvent.ACTION_MOVE -> { curX = e.x; curY = e.y; invalidate(); return true }
                    MotionEvent.ACTION_UP -> {
                        curX = e.x; curY = e.y; dragging = false
                        val w = Math.abs(curX - startX).toInt()
                        val h = Math.abs(curY - startY).toInt()
                        if (w < 20 || h < 20) removeOverlay()
                        else onRegionSelected(minOf(startX, curX).toInt(), minOf(startY, curY).toInt(), w, h)
                        return true
                    }
                }
                return super.onTouchEvent(e)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            windowManager?.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            Log.e(TAG, "Cannot add overlay: ${e.message}")
        }
    }

    private fun removeOverlay() {
        overlayView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    private fun onRegionSelected(x: Int, y: Int, w: Int, h: Int) {
        removeOverlay()
        region = intArrayOf(x, y, w, h)
        regionSelected = true
        Log.d(TAG, "Region: ${w}x${h} at ($x,$y)")
        updateNotif()
    }

    // ── MediaProjection & capture ─────────────────────────────────────────────

    private fun startCapture(resultCode: Int, data: Intent) {
        if (!regionSelected) { Log.w(TAG, "No region selected"); return }
        stopCapture()

        val projMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = projMgr.getMediaProjection(resultCode, data)
        mediaProjection = mp

        // REQUIRED on Android 14+: register callback BEFORE createVirtualDisplay
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped externally")
                mainHandler.post {
                    stopCapture()
                    isMonitoring = false
                    // Drop back to dataSync type since we no longer have a projection
                    startForegroundCompat(useMediaProjectionType = false)
                    updateNotif()
                }
            }
        }, mainHandler)

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        val dpi = resources.displayMetrics.densityDpi
        virtualDisplay = mp.createVirtualDisplay(
            "ScreenWatcher", screenW, screenH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        isMonitoring = true

        previousPixels = null
        pollFuture = executor.scheduleWithFixedDelay(
            ::captureAndCompare, 500L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
        updateNotif()
        Log.d(TAG, "Capture started")
    }

    private fun stopCapture() {
        pollFuture?.cancel(false); pollFuture = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
        previousPixels = null; isMonitoring = false
    }

    // ── Pixel comparison ──────────────────────────────────────────────────────

    private fun captureAndCompare() {
        if (alarmActive.get()) return
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val x0 = region[0].coerceIn(0, screenW - 1)
            val y0 = region[1].coerceIn(0, screenH - 1)
            val x1 = (region[0] + region[2]).coerceIn(0, screenW)
            val y1 = (region[1] + region[3]).coerceIn(0, screenH)
            val cw = x1 - x0; val ch = y1 - y0
            if (cw <= 0 || ch <= 0) return

            val pixels = IntArray(cw * ch)
            var idx = 0
            for (row in y0 until y1) {
                for (col in x0 until x1) {
                    val bi = row * rowStride + col * pixelStride
                    pixels[idx++] = ((buffer[bi].toInt() and 0xFF) shl 16) or
                                    ((buffer[bi + 1].toInt() and 0xFF) shl 8) or
                                     (buffer[bi + 2].toInt() and 0xFF)
                }
            }
            val prev = previousPixels
            previousPixels = pixels
            if (prev == null) { Log.d(TAG, "Baseline captured (${cw}x${ch})"); return }
            val changed = countChangedPixels(prev, pixels)
            Log.v(TAG, "Changed: $changed px")
            if (changed >= minChangedPixels) mainHandler.post { triggerAlarm(changed) }
        } finally {
            image.close()
        }
    }

    private fun countChangedPixels(prev: IntArray, curr: IntArray): Int {
        if (prev.size != curr.size) return Int.MAX_VALUE
        var count = 0
        for (i in prev.indices) {
            val p = prev[i]; val c = curr[i]
            if (Math.abs((p shr 16 and 0xFF) - (c shr 16 and 0xFF)) >= CHANNEL_DELTA ||
                Math.abs((p shr 8  and 0xFF) - (c shr 8  and 0xFF)) >= CHANNEL_DELTA ||
                Math.abs((p        and 0xFF) - (c        and 0xFF)) >= CHANNEL_DELTA) count++
        }
        return count
    }

    // ── Alarm ─────────────────────────────────────────────────────────────────

    private fun triggerAlarm(changed: Int) {
        if (!alarmActive.compareAndSet(false, true)) return
        updateNotif()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(applicationContext, uri)
                isLooping = true; prepare(); start()
            }
        } catch (e: Exception) { Log.e(TAG, "Alarm failed: ${e.message}") }

        // Launch invisible AlarmActivity so volume-down key can dismiss the alarm
        val actIntent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(actIntent)
    }

    private fun stopAlarm() {
        if (!alarmActive.compareAndSet(true, false)) return
        mediaPlayer?.apply { try { if (isPlaying) stop() } catch (_: Exception) {}; release() }
        mediaPlayer = null
        previousPixels = null   // re-baseline after dismiss (matches Python behaviour)
        updateNotif()
        // Tell AlarmActivity to finish (in case it's still open after notification dismiss)
        sendBroadcast(Intent(AlarmActivity.ACTION_ALARM_DISMISSED))
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    private fun teardown() {
        removeOverlay()
        pollFuture?.cancel(false)
        try { executor.shutdown() } catch (_: Exception) {}
        stopAlarm()
        stopCapture()
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Screen Watcher",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Screen change detection"; setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun svcPi(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req, Intent(this, ScreenMonitorService::class.java).apply { this.action = action },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotif(): Notification {
        val alarming = alarmActive.get()
        val text = when {
            alarming       -> "⚠️ Change detected! Dismiss to silence."
            isMonitoring   -> "Monitoring ${region[2]}×${region[3]} region…"
            regionSelected -> "Region selected. Open app to start monitoring."
            else           -> "Tap 'Select Region' to begin."
        }
        val openApp = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Watcher")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply {
                if (!isMonitoring && !alarming)
                    addAction(android.R.drawable.ic_menu_crop, "Select Region",
                        svcPi(ACTION_SELECT_REGION, 1))
                if (alarming)
                    addAction(android.R.drawable.ic_media_pause, "Dismiss Alarm",
                        svcPi(ACTION_STOP_ALARM, 2))
                addAction(android.R.drawable.ic_delete, "Stop",
                    svcPi(ACTION_STOP_SERVICE, 3))
            }.build()
    }

    fun updateNotif() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif())
    }
}
