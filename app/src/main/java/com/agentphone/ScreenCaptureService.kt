package com.agentphone

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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.max

/**
 * ScreenCaptureService
 *
 * Captures screenshots on demand via MediaProjection.
 *
 * Key design:
 *   - Captures at 50% scale for speed (captureW x captureH)
 *   - Tracks original screen size separately (screenW x screenH)
 *   - Exposes scaleX / scaleY so callers can convert LLM coordinates
 *     (which are in capture space) back to real screen coordinates for tapping.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CAPTURE_SCALE = 0.5f   // capture at 50% — plenty for vision LLM

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Real device screen dimensions
    var screenW = 0; private set
    var screenH = 0; private set

    // Capture dimensions (= screenW * CAPTURE_SCALE)
    var captureW = 0; private set
    var captureH = 0; private set

    // Scale factors: multiply LLM coordinate by these to get real screen coordinate
    val scaleX get() = if (captureW > 0) screenW.toFloat() / captureW else 1f
    val scaleY get() = if (captureH > 0) screenH.toFloat() / captureH else 1f

    private var screenDensity = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        readScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "Missing resultCode or resultData")
            stopSelf()
            return START_NOT_STICKY
        }

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, resultData)
        setupVirtualDisplay()

        Log.d(TAG, "Started — screen: ${screenW}x${screenH}, capture: ${captureW}x${captureH}, scale: ${scaleX}x${scaleY}")
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Capture the current screen. Returns a Bitmap in capture resolution
     * (captureW x captureH). Returns null on failure.
     *
     * Caller uses scaleX/scaleY to convert coordinates back to screen space.
     */
    fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null.also {
            Log.e(TAG, "ImageReader not initialized")
        }

        val deadline = System.currentTimeMillis() + 400
        while (System.currentTimeMillis() < deadline) {
            try {
                val image = reader.acquireLatestImage() ?: run {
                    Thread.sleep(30)
                    return@run null
                }

                try {
                    val plane = image.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride

                    if (pixelStride == 0) {
                        Log.e(TAG, "pixelStride is 0 — invalid image plane")
                        return null
                    }

                    val rowPadding = rowStride - pixelStride * captureW
                    val extraPixels = max(0, rowPadding / pixelStride)

                    val raw = Bitmap.createBitmap(
                        captureW + extraPixels, captureH,
                        Bitmap.Config.ARGB_8888
                    )
                    raw.copyPixelsFromBuffer(plane.buffer)

                    // Crop to exact capture dimensions (strips row padding)
                    val cropped = if (extraPixels > 0) {
                        val c = Bitmap.createBitmap(raw, 0, 0, captureW, captureH)
                        raw.recycle()
                        c
                    } else raw

                    Log.d(TAG, "Captured ${cropped.width}x${cropped.height}")
                    return cropped
                } finally {
                    image.close()
                }
            } catch (e: IllegalStateException) {
                // ImageReader was closed — give up
                Log.e(TAG, "ImageReader closed during capture: ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Capture error: ${e.message}")
                Thread.sleep(30)
            }
        }

        Log.w(TAG, "captureScreen timed out — no frame received")
        return null
    }

    // ── Private ───────────────────────────────────────────────────────

    private fun readScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenW = bounds.width()
            screenH = bounds.height()
            val dm = resources.displayMetrics
            screenDensity = dm.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
            screenDensity = dm.densityDpi
        }

        captureW = (screenW * CAPTURE_SCALE).toInt()
        captureH = (screenH * CAPTURE_SCALE).toInt()
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AgentCapture",
            captureW, captureH, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun teardown() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Agent Phone screen capture" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Agent Phone")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
