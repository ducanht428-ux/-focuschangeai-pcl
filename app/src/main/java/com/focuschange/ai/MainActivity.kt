package com.focuschange.ai

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.focuschange.ai.ui.theme.FocusChangeAITheme
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Ảnh chụp toàn bộ trạng thái HUD sau mỗi khung hình.
 * Được tạo trên cameraExecutor và chỉ đọc trên UI thread (immutable).
 */
private data class HudSnapshot(
    val faceDetected: Boolean,
    val points: List<Pair<Float, Float>>,
    val frameWidth: Int,
    val frameHeight: Int,
    val ear1: Float?,
    val ear2: Float?,
    val eyeStatus: String,
    val distanceCm: Float?,
    val tooClose: Boolean,
    val calibrated: Boolean,
    val calibrationProgress: Int,
    val baselineEar: Float?,
    val closeThreshold: Float?,
    val bpm: Float?,
    val blinkDetected: Boolean,
    val sei: Int?,
    val processingMs: Float,
    val perclosPercent: Float?,
    val alertMessage: String?,
    val sessionSeconds: Long,
    val averageSei: Int?,
    val fps: Float,
    val distanceScale: Float
)

class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "FocusChangeAI"
        const val PREFS_NAME = "focus_change_prefs"
        const val KEY_DISTANCE_SCALE = "distance_scale"

        // Chỉ số điểm mốc mắt (giữ nguyên theo code gốc).
        val RIGHT_EYE = listOf(33, 160, 158, 133, 153, 144)
        val LEFT_EYE = listOf(362, 385, 387, 263, 373, 380)
        val EYE_DRAW_INDICES: Set<Int> = (RIGHT_EYE + LEFT_EYE).toSet()

        // Điểm mốc dùng cho khoảng cách và tư thế đầu.
        const val LM_OUTER_RIGHT_EYE = 33
        const val LM_OUTER_LEFT_EYE = 263
        const val LM_NOSE_TIP = 1
        const val LM_FOREHEAD = 10
        const val LM_CHIN = 152
        const val LM_FACE_RIGHT = 234
        const val LM_FACE_LEFT = 454
        const val MIN_LANDMARKS = 468

        // Mô hình lỗ kim (Pinhole): D = (W_real * f) / W_pixel
        const val REAL_EYE_CORNER_DISTANCE_CM = 6.3f
        const val FOCAL_LENGTH_PX = 500f
        const val FOCAL_REFERENCE_LONG_SIDE = 640f
        const val MIN_SAFE_DISTANCE_CM = 30f
        const val DISTANCE_RECOVER_CM = 32f
        const val DISTANCE_SMOOTHING = 0.3f
        const val REFERENCE_DISTANCE_CM = 50f

        // Hiệu chuẩn EAR thích nghi.
        const val CALIBRATION_FRAMES = 60
        const val CALIBRATION_MIN_EAR = 0.20f
        const val CLOSE_RATIO = 0.68f
        const val OPEN_RATIO = 0.80f
        const val MIN_CLOSED_FRAMES = 1

        // Chớp mắt / BPM.
        const val BPM_WINDOW_MS = 60_000L
        const val BPM_MIN_WINDOW_MS = 10_000L
        const val BPM_RELIABLE_AFTER_MS = 15_000L
        const val MAX_BLINK_DURATION_MS = 1_000L
        const val DROWSY_CLOSURE_MS = 1_500L
        const val LOW_BLINK_BPM = 8f

        // PERCLOS (tỉ lệ thời gian nhắm mắt trong 60 giây).
        const val PERCLOS_BUCKETS = 60
        const val PERCLOS_MIN_SAMPLE_MS = 3_000L
        const val PERCLOS_ALERT = 15f

        // Mất tập trung / nghỉ giải lao.
        const val LOOK_AWAY_YAW = 0.15f
        const val LOOK_AWAY_PITCH = 0.18f
        const val LOOK_AWAY_MS = 2_000L
        const val BREAK_REMINDER_MS = 20L * 60L * 1000L
        const val BREAK_REMINDER_SHOW_MS = 30_000L
        const val BREAK_DETECT_MS = 20_000L

        // SEI.
        const val SEI_SMOOTHING = 0.12f
        const val SEI_NO_FACE_DECAY = 0.08f
        const val SEI_TOO_CLOSE_PENALTY = 15f
        const val W_HEAD = 0.30f
        const val W_DISTANCE = 0.25f
        const val W_EYES = 0.25f
        const val W_BPM = 0.20f

        const val LOG_EVERY_N_FRAMES = 30
    }

    private var faceLandmarker: FaceLandmarker? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var cameraStarted = false
    private var lastVideoTimestamp = 0L

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    // ---------------- Thông tin giao diện (chỉ ghi trên UI thread) ----------------
    private var status by mutableStateOf("Đang khởi tạo MediaPipe...")
    private var faceCount by mutableIntStateOf(0)
    private var blinkCount by mutableIntStateOf(0)

    private var landmarkPoints by mutableStateOf(
        emptyList<Pair<Float, Float>>()
    )
    private var frameWidth by mutableIntStateOf(1)
    private var frameHeight by mutableIntStateOf(1)

    private var ear1Text by mutableStateOf("--")
    private var ear2Text by mutableStateOf("--")
    private var eyeStatus by mutableStateOf("Chưa xác định")
    private var processingText by mutableStateOf("-- ms")

    private var distanceText by mutableStateOf("--")
    private var distanceAlert by mutableStateOf(false)
    private var distanceScaleText by mutableStateOf("x1.00")
    private var calibrationText by mutableStateOf("Đang chờ khuôn mặt để hiệu chuẩn...")
    private var bpmText by mutableStateOf("--")
    private var seiValue by mutableIntStateOf(-1)

    private var perclosText by mutableStateOf("--")
    private var alertText by mutableStateOf("")
    private var sessionText by mutableStateOf("--")
    private var fpsText by mutableStateOf("--")

    // ---------------- Trạng thái xử lý (chỉ dùng trên cameraExecutor) ----------------
    @Volatile
    private var recalibrationRequested = false

    @Volatile
    private var distanceCalibrationRequested = false

    @Volatile
    private var distanceScale = 1f

    // Hiệu chuẩn EAR
    private var calibrated = false
    private var calFrames = 0
    private var calSumEar1 = 0f
    private var calSumEar2 = 0f
    private var calSumYaw = 0f
    private var calSumPitch = 0f
    private var baselineEar1 = 0f
    private var baselineEar2 = 0f
    private var closeThreshold1 = 0f
    private var closeThreshold2 = 0f
    private var openThreshold1 = 0f
    private var openThreshold2 = 0f
    private var baselineYaw = 0.5f
    private var baselinePitch = 0.5f

    // Máy trạng thái chớp mắt
    private var eyesClosed = false
    private var closedFrameCount = 0
    private var closedStartMs = 0L
    private val blinkTimestamps = mutableListOf<Long>()
    private var monitoringStartMs = 0L

    // PERCLOS (60 bucket x 1 giây)
    private val perclosClosedMs = LongArray(PERCLOS_BUCKETS)
    private val perclosTotalMs = LongArray(PERCLOS_BUCKETS)
    private val perclosBucketSecond = LongArray(PERCLOS_BUCKETS) { -1L }
    private var perclosLastFrameMs = 0L

    // Khoảng cách
    private var smoothedDistanceCm: Float? = null
    private var distanceAlertActive = false

    // Tư thế đầu
    private var prevNoseX = Float.NaN
    private var prevNoseY = Float.NaN
    private var jitterEma = 0f
    private var lookAwayStartMs = 0L

    // Nghỉ giải lao 20-20-20
    private var workStartMs = 0L
    private var faceLostSinceMs = 0L

    // SEI
    private var seiEma = 0f
    private var seiInitialized = false
    private var seiSum = 0L
    private var seiCount = 0L

    // FPS
    private var prevFrameTimeMs = 0L
    private var fpsEma = 0f

    private var frameCounter = 0L

    // Bộ đệm tái sử dụng để giảm GC (chỉ dùng trên cameraExecutor)
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)
    private var pixelBuffer = IntArray(0)

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                previewView?.let { startCamera(it) }
            } else {
                status = "Bạn cần cấp quyền camera."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Giữ màn hình sáng khi đang theo dõi.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        distanceScale = prefs.getFloat(KEY_DISTANCE_SCALE, 1f).coerceIn(0.3f, 3f)
        distanceScaleText = String.format(Locale.US, "x%.2f", distanceScale)

        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            faceLandmarker =
                FaceLandmarkerHelper(this).setupFaceLandmarker()
            status = "MediaPipe đã sẵn sàng."
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo MediaPipe", e)
            status = "Lỗi model: ${e.message}"
        }

        setContent {
            FocusChangeAITheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text(
                            text = "FocusChangeAI",
                            style = MaterialTheme.typography.headlineSmall
                        )

                        Spacer(Modifier.height(6.dp))
                        Text(text = status)

                        Text(
                            text = "Số khuôn mặt: $faceCount",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Text(text = "Số điểm mốc: ${landmarkPoints.size}")

                        Text(
                            text = "Số lần chớp mắt: $blinkCount",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(text = "Nhịp chớp mắt (BPM): $bpmText")

                        Text(text = "EAR mắt 1: $ear1Text")
                        Text(text = "EAR mắt 2: $ear2Text")
                        Text(text = "Trạng thái mắt: $eyeStatus")
                        Text(text = "Thời gian nhận diện: $processingText ($fpsText FPS)")

                        Text(
                            text = "Khoảng cách: $distanceText (hệ số $distanceScaleText)",
                            color = if (distanceAlert) Color.Red
                            else MaterialTheme.colorScheme.onSurface
                        )

                        if (distanceAlert) {
                            Text(
                                text = "⚠ Cảnh báo: bạn đang ngồi quá gần màn hình (< 30 cm)!",
                                color = Color.Red,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Text(text = "Hiệu chuẩn: $calibrationText")
                        Text(text = "Mệt mỏi (PERCLOS): $perclosText")
                        Text(text = "Phiên làm việc: $sessionText")

                        if (alertText.isNotEmpty()) {
                            Text(
                                text = alertText,
                                color = Color(0xFFEF6C00),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        val seiLabel = seiLabelOf(seiValue)
                        Text(
                            text = if (seiValue >= 0) {
                                "SEI: $seiValue / 100 ($seiLabel)"
                            } else {
                                "SEI: -- / 100"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = seiColorOf(seiValue)
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    recalibrationRequested = true
                                    calibrationText = "Đang hiệu chuẩn lại..."
                                    seiValue = -1
                                }
                            ) {
                                Text("Hiệu chuẩn lại")
                            }

                            Button(
                                onClick = {
                                    distanceCalibrationRequested = true
                                }
                            ) {
                                Text("Chỉnh khoảng cách 50 cm")
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { context ->
                                    PreviewView(context).also { view ->
                                        view.scaleType =
                                            PreviewView.ScaleType.FILL_CENTER
                                        previewView = view

                                        if (faceLandmarker == null) {
                                            status = "Model chưa sẵn sàng."
                                        } else {
                                            checkCameraPermission(view)
                                        }
                                    }
                                }
                            )

                            Canvas(Modifier.fillMaxSize()) {
                                val points = landmarkPoints
                                if (points.isEmpty()) return@Canvas

                                // Ánh xạ khớp với FILL_CENTER (phóng to + cắt giữa).
                                val fw = frameWidth.toFloat().coerceAtLeast(1f)
                                val fh = frameHeight.toFloat().coerceAtLeast(1f)
                                val scale = max(size.width / fw, size.height / fh)
                                val offsetX = (size.width - fw * scale) / 2f
                                val offsetY = (size.height - fh * scale) / 2f

                                points.forEachIndexed { index, point ->
                                    val isEye = index in EYE_DRAW_INDICES

                                    drawCircle(
                                        color = if (isEye) Color.Yellow else Color.Green,
                                        radius = if (isEye) 4f else 2f,
                                        center = Offset(
                                            // Camera trước: lật gương theo trục X.
                                            (1f - point.first) * fw * scale + offsetX,
                                            point.second * fh * scale + offsetY
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun seiLabelOf(sei: Int): String = when {
        sei < 0 -> "--"
        sei >= 80 -> "Tốt"
        sei >= 60 -> "Khá"
        sei >= 40 -> "Trung bình"
        else -> "Thấp"
    }

    private fun seiColorOf(sei: Int): Color = when {
        sei < 0 -> Color.Gray
        sei >= 80 -> Color(0xFF2E7D32)
        sei >= 60 -> Color(0xFFF9A825)
        sei >= 40 -> Color(0xFFEF6C00)
        else -> Color.Red
    }

    private fun checkCameraPermission(view: PreviewView) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startCamera(view)
        } else {
            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun startCamera(view: PreviewView) {
        if (cameraStarted || faceLandmarker == null) return

        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }

                // Giảm độ phân giải phân tích để giảm thời gian xử lý.
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                analysis.setAnalyzer(cameraExecutor) { image ->
                    analyzeFrame(image)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )

                cameraStarted = true

                runOnUiThread {
                    status = "Camera trước đang hoạt động."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi mở camera", e)

                runOnUiThread {
                    status = "Lỗi camera: ${e.message}"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ======================= TOÁN HỌC HÌNH HỌC =======================

    /** Khoảng cách Euclid trong không gian pixel (tránh méo do ảnh không vuông). */
    private fun pixelDistance(
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
        width: Int,
        height: Int
    ): Float {
        val dx = (a.first - b.first) * width
        val dy = (a.second - b.second) * height
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateEAR(
        points: List<Pair<Float, Float>>,
        indices: List<Int>,
        width: Int,
        height: Int
    ): Float {
        if (
            indices.size != 6 ||
            indices.any { it !in points.indices }
        ) {
            return 1f
        }

        val p1 = points[indices[0]]
        val p2 = points[indices[1]]
        val p3 = points[indices[2]]
        val p4 = points[indices[3]]
        val p5 = points[indices[4]]
        val p6 = points[indices[5]]

        val vertical1 = pixelDistance(p2, p6, width, height)
        val vertical2 = pixelDistance(p3, p5, width, height)
        val horizontal = pixelDistance(p1, p4, width, height)

        if (horizontal < 0.001f) return 1f

        return (vertical1 + vertical2) / (2f * horizontal)
    }

    /**
     * Mô hình lỗ kim: Distance(cm) = (W_real * f) / W_pixel.
     * f được quy đổi theo cạnh dài của ảnh so với ảnh tham chiếu 640 px.
     * Giá trị trả về CHƯA nhân hệ số hiệu chuẩn người dùng.
     */
    private fun estimateDistanceCm(
        points: List<Pair<Float, Float>>,
        width: Int,
        height: Int
    ): Float? {
        val wPixel = pixelDistance(
            points[LM_OUTER_RIGHT_EYE],
            points[LM_OUTER_LEFT_EYE],
            width,
            height
        )
        if (wPixel < 1f) return null

        val focalPx = FOCAL_LENGTH_PX *
                (max(width, height).toFloat() / FOCAL_REFERENCE_LONG_SIDE)

        return (REAL_EYE_CORNER_DISTANCE_CM * focalPx) / wPixel
    }

    private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    // ======================= PERCLOS =======================

    private fun resetPerclos() {
        perclosClosedMs.fill(0L)
        perclosTotalMs.fill(0L)
        perclosBucketSecond.fill(-1L)
        perclosLastFrameMs = 0L
    }

    private fun recordPerclos(nowMs: Long, closed: Boolean) {
        val dt = if (perclosLastFrameMs == 0L) {
            0L
        } else {
            (nowMs - perclosLastFrameMs).coerceIn(0L, 200L)
        }
        perclosLastFrameMs = nowMs
        if (dt == 0L) return

        val sec = nowMs / 1000L
        val idx = (sec % PERCLOS_BUCKETS).toInt()

        if (perclosBucketSecond[idx] != sec) {
            perclosBucketSecond[idx] = sec
            perclosClosedMs[idx] = 0L
            perclosTotalMs[idx] = 0L
        }

        perclosTotalMs[idx] += dt
        if (closed) {
            perclosClosedMs[idx] += dt
        }
    }

    /** Phần trăm thời gian nhắm mắt trong 60 giây gần nhất. */
    private fun perclosPercent(nowMs: Long): Float {
        val nowSec = nowMs / 1000L
        var closed = 0L
        var total = 0L

        for (i in 0 until PERCLOS_BUCKETS) {
            val bucketSec = perclosBucketSecond[i]
            if (bucketSec < 0L) continue

            val age = nowSec - bucketSec
            if (age in 0L until PERCLOS_BUCKETS.toLong()) {
                closed += perclosClosedMs[i]
                total += perclosTotalMs[i]
            }
        }

        return if (total < PERCLOS_MIN_SAMPLE_MS) {
            0f
        } else {
            closed * 100f / total.toFloat()
        }
    }

    // ======================= HIỆU CHUẨN & SEI =======================

    private fun resetCalibration() {
        calibrated = false
        calFrames = 0
        calSumEar1 = 0f
        calSumEar2 = 0f
        calSumYaw = 0f
        calSumPitch = 0f
        eyesClosed = false
        closedFrameCount = 0
        closedStartMs = 0L
        blinkTimestamps.clear()
        monitoringStartMs = 0L
        smoothedDistanceCm = null
        distanceAlertActive = false
        prevNoseX = Float.NaN
        prevNoseY = Float.NaN
        jitterEma = 0f
        lookAwayStartMs = 0L
        workStartMs = 0L
        faceLostSinceMs = 0L
        seiInitialized = false
        seiEma = 0f
        seiSum = 0L
        seiCount = 0L
        resetPerclos()
    }

    private fun finishCalibration(nowMs: Long) {
        val n = calFrames.toFloat()
        baselineEar1 = calSumEar1 / n
        baselineEar2 = calSumEar2 / n

        closeThreshold1 = baselineEar1 * CLOSE_RATIO
        closeThreshold2 = baselineEar2 * CLOSE_RATIO
        openThreshold1 = baselineEar1 * OPEN_RATIO
        openThreshold2 = baselineEar2 * OPEN_RATIO

        baselineYaw = calSumYaw / n
        baselinePitch = calSumPitch / n

        calibrated = true
        monitoringStartMs = nowMs
        workStartMs = nowMs
        blinkTimestamps.clear()
        eyesClosed = false
        closedFrameCount = 0
        resetPerclos()

        Log.d(
            TAG,
            "Hiệu chuẩn xong: baseline1=$baselineEar1 baseline2=$baselineEar2 " +
                    "close1=$closeThreshold1 close2=$closeThreshold2"
        )
    }

    private fun distanceScore(d: Float?): Float {
        if (d == null) return 70f
        return when {
            d < MIN_SAFE_DISTANCE_CM -> (d / MIN_SAFE_DISTANCE_CM) * 50f
            d < 45f -> 60f + ((d - MIN_SAFE_DISTANCE_CM) / 15f) * 40f
            d <= 80f -> 100f
            d <= 120f -> 100f - ((d - 80f) / 40f) * 50f
            else -> 50f
        }
    }

    private fun eyeScore(closedDurationMs: Long, closedNow: Boolean): Float = when {
        closedNow && closedDurationMs >= DROWSY_CLOSURE_MS -> 0f
        closedNow && closedDurationMs >= 500L -> 40f
        closedNow -> 90f
        else -> 100f
    }

    private fun bpmScore(bpm: Float, reliable: Boolean): Float {
        if (!reliable) return 85f
        return when {
            bpm in 12f..25f -> 100f
            bpm in 8f..12f -> 70f + ((bpm - 8f) / 4f) * 30f
            bpm in 25f..32f -> 100f - ((bpm - 25f) / 7f) * 30f
            bpm < 8f -> 40f + (bpm / 8f) * 30f
            else -> max(30f, 70f - (bpm - 32f) * 2f)
        }
    }

    private fun headScore(
        yawDev: Float,
        pitchDev: Float,
        rollDeg: Float,
        jitter: Float
    ): Float {
        val orientation = 100f -
                clamp01(yawDev / 0.20f) * 50f -
                clamp01(pitchDev / 0.20f) * 30f -
                clamp01(abs(rollDeg) / 25f) * 20f
        val stability = 100f * (1f - clamp01(jitter / 0.05f))
        return (orientation * 0.7f + stability * 0.3f).coerceIn(0f, 100f)
    }

    private fun updateSei(raw: Float): Int {
        val target = raw.coerceIn(0f, 100f)
        seiEma = if (!seiInitialized) {
            seiInitialized = true
            target
        } else {
            seiEma + (target - seiEma) * SEI_SMOOTHING
        }

        val value = seiEma.roundToInt().coerceIn(0, 100)
        seiSum += value.toLong()
        seiCount++
        return value
    }

    private fun averageSei(): Int? {
        return if (seiCount > 0L) {
            (seiSum.toFloat() / seiCount.toFloat()).roundToInt()
        } else {
            null
        }
    }

    private fun sessionSeconds(nowMs: Long): Long {
        return if (calibrated && monitoringStartMs != 0L) {
            ((nowMs - monitoringStartMs) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    private fun updateFps(nowMs: Long) {
        if (prevFrameTimeMs != 0L) {
            val dt = nowMs - prevFrameTimeMs
            if (dt > 0L) {
                val instant = 1000f / dt.toFloat()
                fpsEma = if (fpsEma == 0f) {
                    instant
                } else {
                    fpsEma * 0.9f + instant * 0.1f
                }
            }
        }
        prevFrameTimeMs = nowMs
    }

    // ======================= XỬ LÝ KHUNG HÌNH =======================

    private fun noFaceSnapshot(
        imgW: Int,
        imgH: Int,
        nowMs: Long,
        processingMs: Float
    ): HudSnapshot {
        eyesClosed = false
        closedFrameCount = 0
        prevNoseX = Float.NaN
        prevNoseY = Float.NaN
        jitterEma = 0f
        lookAwayStartMs = 0L
        perclosLastFrameMs = 0L

        // Rời khỏi màn hình đủ lâu được tính là đã nghỉ giải lao.
        if (faceLostSinceMs == 0L) {
            faceLostSinceMs = nowMs
        }
        if (calibrated && nowMs - faceLostSinceMs >= BREAK_DETECT_MS) {
            workStartMs = 0L
        }

        var sei: Int? = null
        if (calibrated && seiInitialized) {
            seiEma += (0f - seiEma) * SEI_NO_FACE_DECAY
            sei = seiEma.roundToInt().coerceIn(0, 100)
        }

        return HudSnapshot(
            faceDetected = false,
            points = emptyList(),
            frameWidth = imgW,
            frameHeight = imgH,
            ear1 = null,
            ear2 = null,
            eyeStatus = "Không phát hiện khuôn mặt",
            distanceCm = null,
            tooClose = false,
            calibrated = calibrated,
            calibrationProgress = calFrames,
            baselineEar = if (calibrated) (baselineEar1 + baselineEar2) / 2f else null,
            closeThreshold = if (calibrated) (closeThreshold1 + closeThreshold2) / 2f else null,
            bpm = null,
            blinkDetected = false,
            sei = sei,
            processingMs = processingMs,
            perclosPercent = null,
            alertMessage = null,
            sessionSeconds = sessionSeconds(nowMs),
            averageSei = averageSei(),
            fps = fpsEma,
            distanceScale = distanceScale
        )
    }

    private fun buildSnapshot(
        points: List<Pair<Float, Float>>,
        imgW: Int,
        imgH: Int,
        nowMs: Long,
        processingMs: Float
    ): HudSnapshot {
        if (recalibrationRequested) {
            recalibrationRequested = false
            resetCalibration()
        }

        updateFps(nowMs)

        if (points.size < MIN_LANDMARKS) {
            return noFaceSnapshot(imgW, imgH, nowMs, processingMs)
        }

        // Khuôn mặt đã quay lại.
        faceLostSinceMs = 0L
        if (calibrated && workStartMs == 0L) {
            workStartMs = nowMs
        }

        // ---- EAR ----
        val ear1 = calculateEAR(points, RIGHT_EYE, imgW, imgH)
        val ear2 = calculateEAR(points, LEFT_EYE, imgW, imgH)

        // ---- Khoảng cách (mô hình lỗ kim + hệ số hiệu chuẩn) ----
        val rawUnscaled = estimateDistanceCm(points, imgW, imgH)

        if (rawUnscaled != null && distanceCalibrationRequested) {
            distanceCalibrationRequested = false
            val newScale = (REFERENCE_DISTANCE_CM / rawUnscaled).coerceIn(0.3f, 3f)
            distanceScale = newScale
            smoothedDistanceCm = null
            prefs.edit().putFloat(KEY_DISTANCE_SCALE, newScale).apply()
            Log.d(TAG, "Hiệu chuẩn khoảng cách: hệ số = $newScale")
        }

        val rawDistance = rawUnscaled?.let { it * distanceScale }
        if (rawDistance != null) {
            val prev = smoothedDistanceCm
            smoothedDistanceCm = if (prev == null) {
                rawDistance
            } else {
                prev + (rawDistance - prev) * DISTANCE_SMOOTHING
            }
        }
        val distance = smoothedDistanceCm

        if (distance != null) {
            distanceAlertActive = if (distanceAlertActive) {
                distance < DISTANCE_RECOVER_CM
            } else {
                distance < MIN_SAFE_DISTANCE_CM
            }
        }

        // ---- Tư thế đầu ----
        val nose = points[LM_NOSE_TIP]
        val faceLeft = points[LM_FACE_RIGHT]
        val faceRight = points[LM_FACE_LEFT]
        val forehead = points[LM_FOREHEAD]
        val chin = points[LM_CHIN]

        val faceWidthNorm = abs(faceRight.first - faceLeft.first).coerceAtLeast(0.0001f)
        val faceHeightNorm = abs(chin.second - forehead.second).coerceAtLeast(0.0001f)

        val yawRatio = (nose.first - faceLeft.first) /
                (faceRight.first - faceLeft.first).let { if (abs(it) < 0.0001f) 0.0001f else it }
        val pitchRatio = (nose.second - forehead.second) /
                (chin.second - forehead.second).let { if (abs(it) < 0.0001f) 0.0001f else it }

        val eyeA = points[LM_OUTER_RIGHT_EYE]
        val eyeB = points[LM_OUTER_LEFT_EYE]
        val rollDx = abs((eyeB.first - eyeA.first) * imgW)
        val rollDy = (eyeB.second - eyeA.second) * imgH
        val rollDeg = atan2(rollDy, rollDx.coerceAtLeast(0.0001f)) * 180f / PI.toFloat()

        if (!prevNoseX.isNaN()) {
            val mx = (nose.first - prevNoseX) / faceWidthNorm
            val my = (nose.second - prevNoseY) / faceHeightNorm
            val movement = sqrt(mx * mx + my * my)
            jitterEma = jitterEma * 0.8f + movement * 0.2f
        }
        prevNoseX = nose.first
        prevNoseY = nose.second

        var blinkDetected = false
        var currentEyeStatus: String
        var bpm: Float? = null
        var sei: Int? = null
        var perclos: Float? = null
        var alert: String? = null

        if (!calibrated) {
            // ---- Pha hiệu chuẩn: chỉ nhận khung hợp lệ (EAR > 0.20 cả hai mắt) ----
            if (ear1 > CALIBRATION_MIN_EAR && ear2 > CALIBRATION_MIN_EAR) {
                calSumEar1 += ear1
                calSumEar2 += ear2
                calSumYaw += yawRatio
                calSumPitch += pitchRatio
                calFrames++

                if (calFrames >= CALIBRATION_FRAMES) {
                    finishCalibration(nowMs)
                }
            }
            currentEyeStatus = "Đang hiệu chuẩn"
        } else {
            // ---- Phát hiện chớp mắt theo ngưỡng thích nghi ----
            val bothEyesClosed =
                ear1 < closeThreshold1 && ear2 < closeThreshold2
            val bothEyesOpen =
                ear1 > openThreshold1 && ear2 > openThreshold2

            if (bothEyesClosed) {
                closedFrameCount++

                if (closedFrameCount >= MIN_CLOSED_FRAMES && !eyesClosed) {
                    eyesClosed = true
                    closedStartMs = nowMs
                }
            } else if (bothEyesOpen) {
                if (eyesClosed) {
                    val duration = nowMs - closedStartMs
                    if (duration <= MAX_BLINK_DURATION_MS) {
                        blinkDetected = true
                        blinkTimestamps.add(nowMs)
                    }
                }

                eyesClosed = false
                closedFrameCount = 0
            }

            // ---- PERCLOS ----
            recordPerclos(nowMs, eyesClosed)
            val perclosValue = perclosPercent(nowMs)
            perclos = perclosValue

            // ---- Cửa sổ trượt 60 giây ----
            while (blinkTimestamps.isNotEmpty() &&
                nowMs - blinkTimestamps[0] > BPM_WINDOW_MS
            ) {
                blinkTimestamps.removeAt(0)
            }

            val elapsed = nowMs - monitoringStartMs
            val windowMs = elapsed.coerceIn(BPM_MIN_WINDOW_MS, BPM_WINDOW_MS)
            val bpmValue = blinkTimestamps.size * 60_000f / windowMs.toFloat()
            bpm = bpmValue
            val bpmReliable = elapsed >= BPM_RELIABLE_AFTER_MS

            val closedDuration = if (eyesClosed) nowMs - closedStartMs else 0L

            currentEyeStatus = when {
                eyesClosed && closedDuration >= DROWSY_CLOSURE_MS ->
                    "Nhắm lâu - có thể buồn ngủ"
                eyesClosed -> "Đang nhắm"
                else -> "Đang mở hoặc chuyển trạng thái"
            }

            // ---- Mất tập trung (nhìn ra ngoài màn hình) ----
            val yawDev = abs(yawRatio - baselineYaw)
            val pitchDev = abs(pitchRatio - baselinePitch)

            val lookingAwayNow =
                yawDev > LOOK_AWAY_YAW || pitchDev > LOOK_AWAY_PITCH
            if (lookingAwayNow) {
                if (lookAwayStartMs == 0L) {
                    lookAwayStartMs = nowMs
                }
            } else {
                lookAwayStartMs = 0L
            }
            val lookAwayLong =
                lookAwayStartMs != 0L && nowMs - lookAwayStartMs >= LOOK_AWAY_MS

            // ---- Nhắc nghỉ 20-20-20 ----
            val workMs = if (workStartMs == 0L) 0L else nowMs - workStartMs
            var breakReminder = false
            if (workMs >= BREAK_REMINDER_MS) {
                if (workMs < BREAK_REMINDER_MS + BREAK_REMINDER_SHOW_MS) {
                    breakReminder = true
                } else {
                    workStartMs = nowMs
                }
            }

            // ---- Chỉ số SEI ----
            val head = headScore(yawDev, pitchDev, rollDeg, jitterEma)
            val dist = distanceScore(distance)
            val eyes = (
                    eyeScore(closedDuration, eyesClosed) -
                            clamp01((perclosValue - 5f) / 25f) * 50f
                    ).coerceAtLeast(0f)
            val blinkRate = bpmScore(bpmValue, bpmReliable)

            var raw = head * W_HEAD +
                    dist * W_DISTANCE +
                    eyes * W_EYES +
                    blinkRate * W_BPM

            if (distanceAlertActive) {
                raw -= SEI_TOO_CLOSE_PENALTY
            }

            sei = updateSei(raw)

            // ---- Cảnh báo ưu tiên (khoảng cách có banner riêng) ----
            alert = when {
                eyesClosed && closedDuration >= DROWSY_CLOSURE_MS ->
                    "⚠ Nhắm mắt quá lâu - bạn có thể đang buồn ngủ!"
                perclosValue >= PERCLOS_ALERT ->
                    "⚠ Dấu hiệu mệt mỏi (PERCLOS cao) - hãy nghỉ ngơi."
                lookAwayLong ->
                    "⚠ Bạn đang nhìn ra ngoài màn hình."
                bpmReliable && bpmValue < LOW_BLINK_BPM ->
                    "⚠ Chớp mắt quá ít - hãy chớp mắt thường xuyên hơn."
                breakReminder ->
                    "⏰ Đã 20 phút: hãy nhìn xa 6 m trong 20 giây (quy tắc 20-20-20)."
                else -> null
            }
        }

        frameCounter++
        if (frameCounter % LOG_EVERY_N_FRAMES == 0L) {
            Log.d(
                "BlinkDebug",
                "EAR1=$ear1, EAR2=$ear2, closed=$eyesClosed, " +
                        "dist=$distance, bpm=$bpm, perclos=$perclos, sei=$sei"
            )
            Log.d(
                "FaceSpeed",
                "MediaPipe: ${"%.1f".format(Locale.US, processingMs)} ms, " +
                        "FPS: ${"%.1f".format(Locale.US, fpsEma)}"
            )
        }

        return HudSnapshot(
            faceDetected = true,
            points = points,
            frameWidth = imgW,
            frameHeight = imgH,
            ear1 = ear1,
            ear2 = ear2,
            eyeStatus = currentEyeStatus,
            distanceCm = distance,
            tooClose = distanceAlertActive,
            calibrated = calibrated,
            calibrationProgress = calFrames,
            baselineEar = if (calibrated) (baselineEar1 + baselineEar2) / 2f else null,
            closeThreshold = if (calibrated) (closeThreshold1 + closeThreshold2) / 2f else null,
            bpm = bpm,
            blinkDetected = blinkDetected,
            sei = sei,
            processingMs = processingMs,
            perclosPercent = perclos,
            alertMessage = alert,
            sessionSeconds = sessionSeconds(nowMs),
            averageSei = averageSei(),
            fps = fpsEma,
            distanceScale = distanceScale
        )
    }

    /** Chạy trên UI thread: áp dụng ảnh chụp trạng thái lên giao diện. */
    private fun applySnapshot(s: HudSnapshot) {
        faceCount = if (s.faceDetected) 1 else 0
        landmarkPoints = s.points
        frameWidth = s.frameWidth
        frameHeight = s.frameHeight

        if (s.blinkDetected) {
            blinkCount++
        }

        ear1Text = s.ear1?.let {
            String.format(Locale.US, "%.3f", it)
        } ?: "--"

        ear2Text = s.ear2?.let {
            String.format(Locale.US, "%.3f", it)
        } ?: "--"

        eyeStatus = s.eyeStatus

        processingText = String.format(
            Locale.US,
            "%.1f ms",
            s.processingMs
        )
        fpsText = String.format(Locale.US, "%.0f", s.fps)

        distanceText = s.distanceCm?.let {
            String.format(Locale.US, "%.1f cm", it)
        } ?: "--"
        distanceAlert = s.tooClose
        distanceScaleText = String.format(Locale.US, "x%.2f", s.distanceScale)

        bpmText = s.bpm?.let {
            String.format(Locale.US, "%.1f", it)
        } ?: "--"

        perclosText = s.perclosPercent?.let {
            String.format(Locale.US, "%.1f %%", it)
        } ?: "--"

        calibrationText = calibrationLabelFrom(s)
        seiValue = s.sei ?: -1
        alertText = s.alertMessage ?: ""

        sessionText = if (s.calibrated) {
            val minutes = s.sessionSeconds / 60L
            val seconds = s.sessionSeconds % 60L
            val avg = s.averageSei?.toString() ?: "--"
            String.format(Locale.US, "%02d:%02d | SEI trung bình: %s", minutes, seconds, avg)
        } else {
            "--"
        }

        status = if (s.faceDetected) {
            "Đã phát hiện khuôn mặt."
        } else {
            "Chưa phát hiện khuôn mặt."
        }
    }

    /** Dựng chuỗi hiệu chuẩn chỉ từ snapshot (an toàn khi chạy trên UI thread). */
    private fun calibrationLabelFrom(s: HudSnapshot): String {
        return if (s.calibrated && s.baselineEar != null && s.closeThreshold != null) {
            String.format(
                Locale.US,
                "Hoàn tất (EAR cơ sở %.3f, ngưỡng nhắm %.3f)",
                s.baselineEar,
                s.closeThreshold
            )
        } else {
            "Đang hiệu chuẩn ${s.calibrationProgress}/$CALIBRATION_FRAMES - " +
                    "hãy nhìn thẳng, mở mắt tự nhiên"
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        var bitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null
        var mpImage: MPImage? = null

        try {
            val landmarker = faceLandmarker ?: return

            // Chuyển YUV trực tiếp sang Bitmap RGB, không nén JPEG.
            val source = imageProxyToBitmap(imageProxy)
            bitmap = source

            val rotation = imageProxy.imageInfo.rotationDegrees

            val rotated = if (rotation != 0) {
                val matrix = Matrix().apply {
                    postRotate(rotation.toFloat())
                }

                Bitmap.createBitmap(
                    source,
                    0,
                    0,
                    source.width,
                    source.height,
                    matrix,
                    false
                )
            } else {
                source
            }
            rotatedBitmap = rotated

            val imgW = rotated.width
            val imgH = rotated.height

            val image = BitmapImageBuilder(rotated).build()
            mpImage = image

            val timestamp = maxOf(
                SystemClock.uptimeMillis(),
                lastVideoTimestamp + 1
            )
            lastVideoTimestamp = timestamp

            val startNs = SystemClock.elapsedRealtimeNanos()

            val result = landmarker.detectForVideo(
                image,
                timestamp
            )

            val processingMs =
                (SystemClock.elapsedRealtimeNanos() - startNs) /
                        1_000_000f

            val landmarks = result.faceLandmarks().firstOrNull()

            val points = landmarks?.map { point ->
                point.x() to point.y()
            } ?: emptyList()

            // Toàn bộ phép toán khoảng cách / EAR / SEI chạy tại đây (cameraExecutor).
            val snapshot = buildSnapshot(
                points = points,
                imgW = imgW,
                imgH = imgH,
                nowMs = SystemClock.elapsedRealtime(),
                processingMs = processingMs
            )

            runOnUiThread {
                applySnapshot(snapshot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phân tích ảnh", e)

            runOnUiThread {
                status = "Lỗi nhận diện: ${e.message}"
                landmarkPoints = emptyList()
                faceCount = 0
                eyeStatus = "Không xác định"
                distanceText = "--"
                distanceAlert = false
                alertText = ""
            }
        } finally {
            try {
                mpImage?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Không đóng được MPImage", e)
            }

            val rotatedRef = rotatedBitmap
            val bitmapRef = bitmap

            if (rotatedRef != null && rotatedRef !== bitmapRef) {
                rotatedRef.recycle()
            }

            bitmapRef?.recycle()
            imageProxy.close()
        }
    }

    private fun readPlane(buffer: ByteBuffer, current: ByteArray): ByteArray {
        val dup = buffer.duplicate()
        val size = dup.remaining()
        val target = if (current.size >= size) current else ByteArray(size)
        dup.get(target, 0, size)
        return target
    }

    // Đọc YUV_420_888 có xét rowStride và pixelStride.
    // Không tạo JPEG trung gian.
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Sao chép mỗi plane một lần vào mảng tái sử dụng (nhanh hơn get() từng byte).
        yBytes = readPlane(yPlane.buffer, yBytes)
        uBytes = readPlane(uPlane.buffer, uBytes)
        vBytes = readPlane(vPlane.buffer, vBytes)

        val yLast = yPlane.buffer.remaining() - 1
        val uLast = uPlane.buffer.remaining() - 1
        val vLast = vPlane.buffer.remaining() - 1

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        if (pixelBuffer.size != width * height) {
            pixelBuffer = IntArray(width * height)
        }
        val pixels = pixelBuffer

        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = row / 2
            val uRowBase = uvRow * uRowStride
            val vRowBase = uvRow * vRowStride
            val outRow = row * width

            for (col in 0 until width) {
                val yIndex = (yRow + col * yPixelStride).coerceAtMost(yLast)

                val uvCol = col / 2
                val uIndex = (uRowBase + uvCol * uPixelStride).coerceAtMost(uLast)
                val vIndex = (vRowBase + uvCol * vPixelStride).coerceAtMost(vLast)

                val y = yBytes[yIndex].toInt() and 0xFF
                val u = (uBytes[uIndex].toInt() and 0xFF) - 128
                val v = (vBytes[vIndex].toInt() and 0xFF) - 128

                val y1192 = 1192 * (y - 16).coerceAtLeast(0)

                val r = ((y1192 + 1634 * v).coerceIn(0, 262143) shr 10)
                val g = ((y1192 - 833 * v - 400 * u).coerceIn(0, 262143) shr 10)
                val b = ((y1192 + 2066 * u).coerceIn(0, 262143) shr 10)

                pixels[outRow + col] =
                    (0xFF shl 24) or
                            (r shl 16) or
                            (g shl 8) or
                            b
            }
        }

        return Bitmap.createBitmap(
            pixels,
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
    }

    override fun onDestroy() {
        previewView = null

        cameraProvider?.unbindAll()
        cameraProvider = null

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
            try {
                // Chờ khung đang xử lý kết thúc trước khi đóng model.
                cameraExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        faceLandmarker?.close()
        faceLandmarker = null

        super.onDestroy()
    }
}
