package com.bumpbot.flappy.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.bumpbot.flappy.R
import com.bumpbot.flappy.bot.BotAction
import com.bumpbot.flappy.bot.FlappyBot

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "bumpbot_channel"
        private const val NOTIF_ID = 1
        private const val CAPTURE_SCALE = 4
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val bot = FlappyBot()
    private var running = false

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var frameCount = 0
    private var lastFpsTime = 0L

    private lateinit var tvStatus: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        captureThread = HandlerThread("CaptureThread").also { it.start() }
        captureHandler = Handler(captureThread.looper)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        intent?.let {
            val resultCode = it.getIntExtra("resultCode", -1)
            @Suppress("DEPRECATION")
            val data = it.getParcelableExtra<Intent>("projectionData")
            if (data != null) {
                setupProjection(resultCode, data)
            }
        }

        createOverlay()
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val captureW = screenWidth / CAPTURE_SCALE
        val captureH = screenHeight / CAPTURE_SCALE

        imageReader = ImageReader.newInstance(
            captureW, captureH,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "BumpBotCapture",
            captureW, captureH, screenDensity / CAPTURE_SCALE,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, captureHandler
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_menu, null)
        tvStatus = overlayView.findViewById(R.id.tv_status)

        val btnPlay: Button = overlayView.findViewById(R.id.btn_play)
        val btnStop: Button = overlayView.findViewById(R.id.btn_stop)
        val btnCalibrate: Button = overlayView.findViewById(R.id.btn_calibrate)
        val btnClose: Button = overlayView.findViewById(R.id.btn_close)

        btnPlay.setOnClickListener { startBot() }
        btnStop.setOnClickListener { stopBot() }
        btnCalibrate.setOnClickListener { calibrate() }
        btnClose.setOnClickListener { stopSelf() }

        val root = overlayView.findViewById<View>(R.id.overlay_root)
        var dragStartX = 0f
        var dragStartY = 0f
        var paramStartX = 0
        var paramStartY = 0
        var isDragging = false

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    paramStartX = params.x
                    paramStartY = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (dx * dx + dy * dy > 100) isDragging = true
                    if (isDragging) {
                        params.x = paramStartX + dx.toInt()
                        params.y = paramStartY + dy.toInt()
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
    }

    private fun startBot() {
        if (running) return
        running = true
        tvStatus.text = "RUNNING"
        lastFpsTime = System.currentTimeMillis()
        frameCount = 0

        captureHandler.post(captureRunnable)
    }

    private fun stopBot() {
        running = false
        tvStatus.text = "STOPPED"
    }

    private fun calibrate() {
        bot.resetCalibration()

        captureHandler.post {
            val frame = grabFrame()
            if (frame != null) {
                bot.calibrate(frame)
                frame.recycle()
                overlayView.post {
                    tvStatus.text = "CALIBRATED"
                    Toast.makeText(this, "Background sampled", Toast.LENGTH_SHORT).show()
                }
            } else {
                overlayView.post {
                    tvStatus.text = "CAL FAILED — no frame"
                }
            }
        }
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!running) return

            val frame = grabFrame()
            if (frame != null) {
                val state = bot.analyze(frame)

                if (state.action == BotAction.TAP) {
                    val tapX = screenWidth * 0.5f
                    val tapY = screenHeight * 0.5f
                    TapAccessibilityService.instance?.tap(tapX, tapY)
                }

                frameCount++
                val now = System.currentTimeMillis()
                val elapsed = now - lastFpsTime
                if (elapsed >= 1000) {
                    val fps = (frameCount * 1000f / elapsed).toInt()
                    frameCount = 0
                    lastFpsTime = now
                    overlayView.post {
                        tvStatus.text = "RUN | ${fps}fps | bird:${state.birdY} gap:${state.gapCenterY}"
                    }
                }

                frame.recycle()
            }

            captureHandler.postDelayed(this, 16)
        }
    }

    private fun grabFrame(): Bitmap? {
        val image: Image? = try {
            imageReader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        image ?: return null

        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        image.close()

        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            bmp.recycle()
            cropped
        } else {
            bmp
        }
    }

    override fun onDestroy() {
        running = false
        captureHandler.removeCallbacksAndMessages(null)
        captureThread.quitSafely()

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        try {
            windowManager.removeView(overlayView)
        } catch (_: Exception) {}

        super.onDestroy()
    }
}
