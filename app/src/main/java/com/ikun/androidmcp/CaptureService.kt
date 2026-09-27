package com.ikun.androidmcp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object ScreenBridge {
    private fun refuseIfLan(app: Context) {
        val prefs = app.getSharedPreferences("mcp_server", Context.MODE_PRIVATE)
        if (prefs.getBoolean("lan_enabled", false)) {
            throw SecurityException("局域网监听开启时，屏幕/相机/麦克风操作被禁用")
        }
    }

    fun status(app: Context): JSONObject {
        val prefs = app.getSharedPreferences("mcp_server", Context.MODE_PRIVATE)
        return JSONObject()
            .put("accessibilityEnabled", AccessibilityBridgeService.isEnabled(app))
            .put("recording", CaptureService.isRecording())
            .put("lanEnabled", prefs.getBoolean("lan_enabled", false))
            .put("shellEnabled", prefs.getBoolean("local_shell_enabled", false))
            .put("dataWritesEnabled", prefs.getBoolean("data_writes_enabled", false))
    }

    fun tap(app: Context, args: JSONObject): JSONObject { refuseIfLan(app); return AccessibilityBridgeService.tapAt(args.optInt("x", -1), args.optInt("y", -1)) }
    fun swipe(app: Context, args: JSONObject): JSONObject {
        refuseIfLan(app)
        return AccessibilityBridgeService.swipeAt(args.optInt("x1", -1), args.optInt("y1", -1), args.optInt("x2", -1), args.optInt("y2", -1), args.optLong("durationMs", 300L))
    }
    fun key(app: Context, args: JSONObject): JSONObject { refuseIfLan(app); return AccessibilityBridgeService.globalKey(args.optString("action", "back")) }
    fun text(app: Context, args: JSONObject): JSONObject { refuseIfLan(app); return AccessibilityBridgeService.inputText(args.optString("text")) }

    fun screenshot(app: Context, args: JSONObject): JSONObject {
        refuseIfLan(app)
        return CaptureService.requestScreenshot(app, args.optInt("maxWidth", 1080))
    }

    fun recordStart(app: Context, args: JSONObject): JSONObject {
        refuseIfLan(app)
        return CaptureService.startRecording(app, args.optInt("autoStopSeconds", 60))
    }

    fun recordStop(app: Context): JSONObject = CaptureService.stopRecording(app)

    fun photo(app: Context, args: JSONObject): JSONObject {
        refuseIfLan(app)
        return CaptureService.takePhoto(app, args.optString("facing", "back"))
    }

    fun audioRecord(app: Context, args: JSONObject): JSONObject {
        refuseIfLan(app)
        return CaptureService.recordAudio(app, args.optInt("seconds", 10))
    }
}

class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ""
        val type = when (action) {
            ACTION_PROJECTION -> if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            ACTION_CAMERA -> if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ACTION_MICROPHONE -> if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            else -> 0
        }
        try {
            val notification = buildNotification()
            if (type != 0) startForeground(NOTIFICATION_ID, notification, type)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            serviceFailed = true
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_PROJECTION -> deliverProjection(intent)
            ACTION_CAMERA -> cameraReady = true
            ACTION_MICROPHONE -> micReady = true
        }
        return START_NOT_STICKY
    }

    private fun deliverProjection(intent: Intent?) {
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        }
        if (code == 0 || data == null) { projectionState.set(STATE_DENIED); return }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try { manager.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (projection == null) { projectionState.set(STATE_DENIED); return }
        if (Build.VERSION.SDK_INT >= 34) {
            projection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        }
        CaptureService.projection = projection
        projectionState.set(STATE_OK)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MCP 采集服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "屏幕/相机/麦克风采集运行状态"
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Android MCP Bridge 采集中")
            .setContentText("用户授权的采集会话正在运行")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_PROJECTION = "com.ikun.androidmcp.CAPTURE_PROJECTION"
        private const val ACTION_CAMERA = "com.ikun.androidmcp.CAPTURE_CAMERA"
        private const val ACTION_MICROPHONE = "com.ikun.androidmcp.CAPTURE_MICROPHONE"
        private const val CHANNEL_ID = "mcp_capture"
        private const val NOTIFICATION_ID = 18766
        private const val STATE_NONE = 0
        private const val STATE_OK = 1
        private const val STATE_DENIED = 2

        private val projectionState = AtomicInteger(STATE_NONE)
        @Volatile private var projection: MediaProjection? = null
        @Volatile private var cameraReady = false
        @Volatile private var micReady = false
        @Volatile private var serviceFailed = false
        private var recorder: MediaRecorder? = null
        private var recordDisplay: VirtualDisplay? = null
        @Volatile private var recordingFile: File? = null
        @Volatile private var recording = false

        fun isRecording() = recording

        fun projectionGranted(code: Int, data: Intent, app: Context) {
            val intent = Intent(app, CaptureService::class.java).setAction(ACTION_PROJECTION)
            intent.putExtra("code", code)
            intent.putExtra("data", data)
            try {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
            } catch (e: Exception) {
                projectionState.set(STATE_DENIED)
            }
        }

        fun projectionDenied() { projectionState.set(STATE_DENIED) }

        @Suppress("DEPRECATION")
        private fun bringAppForeground(app: Context) {
            runCatching {
                app.startActivity(Intent(app, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_CAPTURE_HINT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        private fun startMode(app: Context, action: String) {
            val intent = Intent(app, CaptureService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
            } catch (e: Exception) {
                serviceFailed = true
            }
        }

        private fun resetProjection() {
            runCatching { projection?.stop() }
            projection = null
            projectionState.set(STATE_NONE)
        }

        private fun awaitProjection(app: Context): MediaProjection {
            resetProjection()
            serviceFailed = false
            runCatching {
                app.startActivity(Intent(app, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_SCREEN_CAPTURE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { throw IllegalStateException("无法请求屏幕采集授权") }
            val start = System.currentTimeMillis()
            while (projectionState.get() == STATE_NONE && System.currentTimeMillis() - start < 120_000) {
                Thread.sleep(200)
            }
            return projection ?: throw SecurityException("未获得屏幕采集授权（用户取消或超时120秒）")
        }

        fun requestScreenshot(app: Context, maxWidthArg: Int): JSONObject {
            val maxWidth = maxWidthArg.coerceIn(240, 2160)
            val proj = awaitProjection(app)
            try {
                Thread.sleep(700)
                val metrics = app.resources.displayMetrics
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                var display: VirtualDisplay? = null
                try {
                    display = proj.createVirtualDisplay("mcp-shot", width, height, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
                    val image = acquireImage(reader) ?: throw IllegalStateException("8秒内未捕获到屏幕帧")
                    val raw = toBitmap(image, width, height)
                    val scaled = scaleBitmap(raw, maxWidth)
                    val bytes = compressJpeg(scaled, 70)
                    val dir = File(storageRoot(), "Pictures/AndroidMcpBridge").apply { mkdirs() }
                    val file = File(dir, "screenshot_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { it.write(bytes) }
                    return JSONObject().put("path", file.absolutePath).put("sizeBytes", bytes.size)
                        .put("width", scaled.width).put("height", scaled.height)
                        .put("mime", "image/jpeg")
                        .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                } finally {
                    display?.release()
                    reader.close()
                }
            } finally {
                stopProjectionIfIdle(app)
            }
        }

        fun startRecording(app: Context, autoStopSeconds: Int): JSONObject {
            if (recording) throw IllegalStateException("已有录制进行中，请先 screen.record.stop")
            val duration = autoStopSeconds.coerceIn(5, 600)
            val proj = awaitProjection(app)
            val metrics = app.resources.displayMetrics
            val dir = File(storageRoot(), "Movies/AndroidMcpBridge").apply { mkdirs() }
            val file = File(dir, "screen_${System.currentTimeMillis()}.mp4")
            val rec = MediaRecorder()
            try {
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                rec.setVideoSize(metrics.widthPixels, metrics.heightPixels)
                rec.setVideoFrameRate(30)
                rec.setVideoEncodingBitRate(8_000_000)
                rec.setOutputFile(file.absolutePath)
                val surface = MediaCodec.createPersistentInputSurface()
                rec.setInputSurface(surface)
                rec.prepare()
                recordDisplay = proj.createVirtualDisplay("mcp-rec", metrics.widthPixels, metrics.heightPixels,
                    metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
                rec.start()
                recorder = rec
                recordingFile = file
                recording = true
                val contextRef = app.applicationContext
                Thread {
                    run {
                        Thread.sleep(duration * 1000L)
                        if (recording) runCatching { stopRecording(contextRef) }
                    }
                }.start()
                return JSONObject().put("recording", true).put("file", file.absolutePath).put("autoStopSeconds", duration)
            } catch (e: Exception) {
                runCatching { rec.release() }
                runCatching { recordDisplay?.release() }
                recordDisplay = null
                recorder = null
                recordingFile = null
                recording = false
                stopProjectionIfIdle(app)
                throw IllegalStateException("录屏启动失败：${e.message ?: "未知错误"}")
            }
        }

        fun stopRecording(app: Context): JSONObject {
            if (!recording) throw IllegalStateException("当前没有进行中的录制")
            val file = recordingFile
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            runCatching { recordDisplay?.release() }
            recordDisplay = null
            recording = false
            recordingFile = null
            runCatching { projection?.stop() }
            projection = null
            projectionState.set(STATE_NONE)
            app.stopService(Intent(app, CaptureService::class.java))
            return JSONObject().put("stopped", true)
                .put("path", file?.absolutePath ?: "")
                .put("sizeBytes", file?.length() ?: 0L)
        }

        private fun stopProjectionIfIdle(app: Context) {
            if (!recording) {
                runCatching { projection?.stop() }
                projection = null
                projectionState.set(STATE_NONE)
                app.stopService(Intent(app, CaptureService::class.java))
            }
        }

        private fun acquireImage(reader: ImageReader): Image? {
            val latch = CountDownLatch(1)
            val ref = AtomicReference<Image?>()
            reader.setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireLatestImage() }.getOrNull()
                if (image != null) { ref.set(image); latch.countDown() }
            }, Handler(Looper.getMainLooper()))
            latch.await(8, TimeUnit.SECONDS)
            return ref.get()
        }

        private fun toBitmap(image: Image, width: Int, height: Int): Bitmap {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmapWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            return if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
        }

        private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int): Bitmap {
            if (bitmap.width <= maxWidth) return bitmap
            val ratio = maxWidth.toFloat() / bitmap.width
            return Bitmap.createScaledBitmap(bitmap, maxWidth, (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        }

        private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            return output.toByteArray()
        }

        @Suppress("DEPRECATION")
        private fun storageRoot(): File = Environment.getExternalStorageDirectory()

        fun takePhoto(app: Context, facing: String): JSONObject {
            if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("请先授予 CAMERA")
            }
            cameraReady = false
            serviceFailed = false
            bringAppForeground(app)
            Thread.sleep(900)
            startMode(app, ACTION_CAMERA)
            var waited = 0
            while (!cameraReady && !serviceFailed && waited < 5000) { Thread.sleep(150); waited += 150 }
            if (!cameraReady) throw IllegalStateException("相机前台服务未能启动；请打开 App 后重试")
            try {
                val bytes = captureStill(facing)
                val dir = File(storageRoot(), "DCIM/AndroidMcpBridge").apply { mkdirs() }
                val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { it.write(bytes) }
                val out = JSONObject().put("taken", true).put("path", file.absolutePath).put("sizeBytes", bytes.size).put("mime", "image/jpeg")
                if (bytes.size <= 5L * 1024 * 1024) {
                    out.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
                return out
            } finally {
                app.stopService(Intent(app, CaptureService::class.java))
            }
        }

        @Suppress("DEPRECATION")
        private fun captureStill(facing: String): ByteArray {
            val wantFront = facing.equals("front", ignoreCase = true)
            var index = -1
            val info = Camera.CameraInfo()
            val count = try { Camera.getNumberOfCameras() } catch (e: Exception) { 0 }
            for (i in 0 until count) {
                runCatching { Camera.getCameraInfo(i, info) }
                val isFront = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                if (isFront == wantFront) { index = i; break }
            }
            if (index < 0) throw IllegalStateException("找不到${facing}摄像头")
            val camera = try { Camera.open(index) } catch (e: Exception) { throw IllegalStateException("摄像头打开失败：${e.message ?: "设备占用或被策略禁止"}") }
            try {
                runCatching {
                    camera.setPreviewTexture(SurfaceTexture(0))
                    camera.startPreview()
                }
                runCatching {
                    val parameters = camera.parameters
                    parameters.jpegQuality = 70
                    camera.parameters = parameters
                }
                val latch = CountDownLatch(1)
                val ref = AtomicReference<ByteArray?>()
                camera.takePicture(null, null) { data, _ -> ref.set(data); latch.countDown() }
                if (!latch.await(20, TimeUnit.SECONDS)) throw IllegalStateException("相机20秒未返回照片")
                return ref.get() ?: throw IllegalStateException("相机返回空数据")
            } finally {
                runCatching { camera.stopPreview() }
                runCatching { camera.release() }
            }
        }

        fun recordAudio(app: Context, seconds: Int): JSONObject {
            if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("请先授予 RECORD_AUDIO")
            }
            val duration = seconds.coerceIn(1, 120)
            micReady = false
            serviceFailed = false
            bringAppForeground(app)
            Thread.sleep(900)
            startMode(app, ACTION_MICROPHONE)
            var waited = 0
            while (!micReady && !serviceFailed && waited < 5000) { Thread.sleep(150); waited += 150 }
            if (!micReady) throw IllegalStateException("麦克风前台服务未能启动；请打开 App 后重试")
            val dir = File(storageRoot(), "Music/AndroidMcpBridge").apply { mkdirs() }
            val file = File(dir, "audio_${System.currentTimeMillis()}.m4a")
            val rec = MediaRecorder()
            try {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(96_000)
                rec.setAudioSamplingRate(44_100)
                rec.setOutputFile(file.absolutePath)
                rec.prepare()
                rec.start()
                Thread.sleep(duration * 1000L)
                runCatching { rec.stop() }
                return JSONObject().put("recorded", true).put("path", file.absolutePath)
                    .put("seconds", duration).put("sizeBytes", file.length())
            } catch (e: Exception) {
                throw IllegalStateException("录音失败：${e.message ?: "未知错误"}")
            } finally {
                runCatching { rec.release() }
                app.stopService(Intent(app, CaptureService::class.java))
            }
        }
    }
}
