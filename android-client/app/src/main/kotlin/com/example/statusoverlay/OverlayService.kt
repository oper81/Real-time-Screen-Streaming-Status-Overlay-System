package com.example.statusoverlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class OverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var windowManager: WindowManager
    private var overlayView: TextView? = null

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Буферы кадров используются только в потоке backgroundHandler
    private var frameBitmap: Bitmap? = null
    private val jpegStream = ByteArrayOutputStream()

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var isRunning = false
    private var reconnectJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "status_overlay_channel"
        private const val NOTIFICATION_ID = 1
        private const val RECONNECT_DELAY_MS = 3000L

        // Если в очереди сокета накопилось больше этого объёма — кадры пропускаем
        private const val MAX_QUEUE_BYTES = 512L * 1024L

        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_CAPTURE_INTENT = "CAPTURE_INTENT"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlayView()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Защита от повторного запуска: проекцию можно использовать только один раз
        if (isRunning) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        val captureIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_CAPTURE_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_CAPTURE_INTENT)
        }

        if (resultCode != Activity.RESULT_OK || captureIntent == null) {
            Log.e(TAG, "Не получены корректные данные для запуска MediaProjection")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // ШАГ 1: сначала foreground с типом mediaProjection
            createNotificationChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // ШАГ 2: только после startForeground получаем проекцию
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, captureIntent)
            if (mediaProjection == null) {
                Log.e(TAG, "getMediaProjection вернул null")
                stopSelf()
                return START_NOT_STICKY
            }

            isRunning = true

            // ШАГ 3: сокет и захват экрана
            connectWebSocket()
            startScreenCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска трансляции", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startScreenCapture() {
        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val fullWidth: Int
        val fullHeight: Int
        val dpi: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            fullWidth = bounds.width()
            fullHeight = bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            fullWidth = metrics.widthPixels
            fullHeight = metrics.heightPixels
            dpi = metrics.densityDpi
        }

        val width = (fullWidth / 2).coerceAtLeast(1)
        val height = (fullHeight / 2).coerceAtLeast(1)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "Трансляция экрана остановлена системой.")
                stopSelf()
            }
        }
        projectionCallback = callback
        mediaProjection?.registerCallback(callback, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenStream", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val ws = webSocket
                if (ws == null || ws.queueSize() > MAX_QUEUE_BYTES) {
                    image.close()
                    return@setOnImageAvailableListener
                }

                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val paddedWidth = rowStride / pixelStride

                var bmp = frameBitmap
                if (bmp == null || bmp.width != paddedWidth || bmp.height != height) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    frameBitmap = bmp
                }

                bmp.copyPixelsFromBuffer(plane.buffer)
                image.close()

                // Вырезаем чистый кадр без технического отступа (rowPadding)
                val cleanBitmap = Bitmap.createBitmap(bmp, 0, 0, width, height)

                jpegStream.reset()
                cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 60, jpegStream)
                cleanBitmap.recycle()

                val bytes = jpegStream.toByteArray()
                ws.send(bytes.toByteString())
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка конвейера ImageReader: ${e.message}")
                image.close()
            }
        }, backgroundHandler)
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(MainActivity.WEBSOCKET_URL).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket открыт")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("group") && json.has("status")) {
                        val group = json.getInt("group").toString().padStart(2, '0')
                        val status = json.getInt("status")

                        overlayView?.post {
                            overlayView?.text = "$group$status%"
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка разбора JSON: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(RECONNECT_DELAY_MS)
            connectWebSocket()
        }
    }
    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Система мониторинга")
            .setContentText("Трансляция экрана и оверлей активны")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Status Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    private fun addOverlayView() {
        val scale = resources.displayMetrics.density // Масштабирование под экран устройства (~2.75 для Redmi)

        val textView = TextView(this).apply {
            text = "--"

            // КРИТИЧНО: Выберите цвет в зависимости от темы вашего статус-бара!
            // Если статус-бар белый — ставьте Color.BLACK. Если черный — Color.WHITE.
            setTextColor(Color.WHITE)

            setBackgroundColor(Color.TRANSPARENT)  // Полностью прозрачный фон
            textSize = 9f                         // Увеличили размер (раньше был 8f), чтобы текст лучше читался
            setTypeface(null, Typeface.BOLD)       // Жирный шрифт для четкости
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            // ИСПРАВЛЕНО: Сдвигаем оверлей влево, чтобы уйти из-под иконки батареи
            x = (24 * scale + 0.5f).toInt()        // Отступ справа (60 пикселей сдвинут текст левее батареи)
            y = (0 * scale + 0.5f).toInt()       // Вертикальное центрирование в статус-баре
        }

        try {
            windowManager.addView(textView, params)
            overlayView = textView
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось добавить overlay-окно.", e)
        }
    }


    private fun removeOverlayView() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка удаления оверлея: ${e.message}")
            }
        }
        overlayView = null
    }
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "Service stopped")
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.let { mp ->
            projectionCallback?.let { mp.unregisterCallback(it) }
            mp.stop()
        }
        backgroundThread?.quit()
        frameBitmap?.recycle()
        removeOverlayView()
        serviceScope.cancel()
    }
}
