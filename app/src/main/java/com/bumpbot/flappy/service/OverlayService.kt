package com.bumpbot.flappy.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.bumpbot.flappy.R
import com.bumpbot.flappy.bot.BotAction
import com.bumpbot.flappy.bot.FlappyBot

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "bumpbot_channel"
        private const val NOTIF_ID = 1
        private const val CAPTURE_SCALE = 2
    }

    private lateinit var windowManager: WindowManager

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded = false

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleAdded = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val bot = FlappyBot()
    private var running = false
    private var menuVisible = true

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private var frameCount = 0
    private var lastFpsTime = 0L

    private var tvStatus: TextView? = null

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

        if (!overlayAdded) {
            createOverlay()
            createBubble()
        }

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

        if (Build.VERSION.SDK_INT >= 34) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        running = false
                        tvStatus?.text = "PROJECTION ENDED"
                    }
                }
            }, mainHandler)
        }

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
    private fun createBubble() {
        val density = resources.displayMetrics.density
        val size = (48 * density).toInt()

        val container = FrameLayout(this)

        val circle = View(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#CC4CAF50"))
        bg.setStroke((2 * density).toInt(), Color.WHITE)
        circle.background = bg
        container.addView(circle, FrameLayout.LayoutParams(size, size))

        val label = TextView(this)
        label.text = "B"
        label.setTextColor(Color.WHITE)
        label.textSize = 18f
        label.gravity = Gravity.CENTER
        container.addView(label, FrameLayout.LayoutParams(size, size))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val bp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - size - 20
            y = screenHeight / 3
        }

        var startX = 0f
        var startY = 0f
        var startPX = 0
        var startPY = 0
        var dragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startPX = bp.x
                    startPY = bp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (dx * dx + dy * dy > 200) dragging = true
                    if (dragging) {
                        bp.x = startPX + dx.toInt()
                        bp.y = startPY + dy.toInt()
                        try { windowManager.updateViewLayout(container, bp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) showMenu()
                    true
                }
                else -> false
            }
        }

        bubbleView = container
        bubbleParams = bp

        windowManager.addView(container, bp)
        bubbleAdded = true
        container.visibility = View.GONE
    }

    private fun showMenu() {
        overlayView?.visibility = View.VISIBLE
        bubbleView?.visibility = View.GONE
        menuVisible = true
    }

    private fun hideMenu() {
        overlayView?.visibility = View.GONE
        bubbleView?.visibility = View.VISIBLE
        menuVisible = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
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

        overlayParams = lp

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_menu, null)
        overlayView = view
        tvStatus = view.findViewById(R.id.tv_status)

        val btnPlay: Button = view.findViewById(R.id.btn_play)
        val btnStop: Button = view.findViewById(R.id.btn_stop)
        val btnCalibrate: Button = view.findViewById(R.id.btn_calibrate)
        val btnClose: Button = view.findViewById(R.id.btn_close)

        btnPlay.setOnClickListener { startBot() }
        btnStop.setOnClickListener { stopBot() }
        btnCalibrate.setOnClickListener { calibrate() }
        btnClose.setOnClickListener { hideMenu() }

        val root = view.findViewById<View>(R.id.overlay_root)
        setupDrag(root, lp, view)

        windowManager.addView(view, lp)
        overlayAdded = true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(handle: View, lp: WindowManager.LayoutParams, target: View) {
        var startX = 0f
        var startY = 0f
        var startPX = 0
        var startPY = 0
        var dragging = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startPX = lp.x
                    startPY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (dx * dx + dy * dy > 200) dragging = true
                    if (dragging) {
                        lp.x = startPX + dx.toInt()
                        lp.y = startPY + dy.toInt()
                        try { windowManager.updateViewLayout(target, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> dragging
                else -> false
            }
        }
    }

    private fun startBot() {
        if (running) return
        running = true
        tvStatus?.text = "RUNNING"
        lastFpsTime = System.currentTimeMillis()
        frameCount = 0
        captureHandler.post(captureRunnable)
    }

    private fun stopBot() {
        running = false
        tvStatus?.text = "STOPPED"
    }

    private fun calibrate() {
        bot.resetCalibration()
        captureHandler.post {
            val frame = grabFrame()
            if (frame != null) {
                bot.calibrate(frame)
                frame.recycle()
                mainHandler.post {
                    tvStatus?.text = "CALIBRATED"
                    Toast.makeText(this, "Ready", Toast.LENGTH_SHORT).show()
                }
            } else {
                mainHandler.post { tvStatus?.text = "CAL FAIL" }
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
                    mainHandler.post {
                        if (running) {
                            tvStatus?.text = "${fps}fps B:${state.birdY} G:${state.gapCenterY}"
                        }
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

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val imgWidth = image.width
            val imgHeight = image.height
            val rowPadding = rowStride - pixelStride * imgWidth

            val bmp = Bitmap.createBitmap(
                imgWidth + rowPadding / pixelStride,
                imgHeight,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            image.close()

            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, imgWidth, imgHeight)
                bmp.recycle()
                cropped
            } else {
                bmp
            }
        } catch (e: Exception) {
            image.close()
            null
        }
    }

    override fun onDestroy() {
        running = false
        captureHandler.removeCallbacksAndMessages(null)
        captureThread.quitSafely()

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        try { if (overlayAdded) windowManager.removeView(overlayView) } catch (_: Exception) {}
        try { if (bubbleAdded) windowManager.removeView(bubbleView) } catch (_: Exception) {}
        overlayAdded = false
        bubbleAdded = false

        super.onDestroy()
    }
}
