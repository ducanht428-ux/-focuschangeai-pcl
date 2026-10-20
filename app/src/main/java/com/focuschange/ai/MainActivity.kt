package com.focuschange.ai

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Điểm mốc khuôn mặt dạng mảng phẳng [x0, y0, z0, x1, y1, z1, ...].
 * Tránh tạo ~1.400 đối tượng Pair/Float mỗi khung hình (giảm GC, mượt hơn).
 */
private class Landmarks(val data: FloatArray, val count: Int) {
    fun x(i: Int): Float = data[i * 3]
    fun y(i: Int): Float = data[i * 3 + 1]
    fun z(i: Int): Float = data[i * 3 + 2]
}

private val EMPTY_LANDMARKS = Landmarks(FloatArray(0), 0)

/**
 * Ảnh chụp toàn bộ trạng thái HUD sau mỗi khung hình.
 * Được tạo trên cameraExecutor và chỉ đọc trên UI thread (immutable).
 */
private data class HudSnapshot(
    val faceDetected: Boolean,
    val points: Landmarks,
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
    val distanceScale: Float,
    val yawDeg: Float?,
    val pitchDeg: Float?,
    val rollDeg: Float?,
    val gazeStatus: String,
    val expressionStatus: String,
    val bfr: Float?,
    val mar: Float?,
    val yawnCount: Int,
    val chronicLevel: Int,
    val inattentionPercent: Float?,
    val violationScore: Float,
    val anomaly: String?,
    val avgBlinkMs: Float?,
    val fatigueIndex: Int?,
    val signalQuality: Int,
    val signalNote: String,
    val minSei: Int?,
    val violationsTotal: Int,
    val chronicEpisodes: Int,
    val yawnTotal: Int,
    val habitStatus: String,
    val habitDetail: String,
    val habitHistory: String,
    val alertCode: Int,
    val eyeDetail: String
)

/** Một mẫu tư thế đầu trong cửa sổ trượt thời gian (góc thô, chưa lọc). */
private data class PoseSample(
    val timeMs: Long,
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val ear: Float
)

/** Một lần vi phạm chú ý (có trọng số) trong cửa sổ tích lũy. */
private data class ViolationEvent(
    val timeMs: Long,
    val weight: Float
)

/**
 * Bộ lọc One Euro: làm mượt tín hiệu nhiễu nhưng vẫn bám nhanh khi chuyển động nhanh.
 */
private class OneEuroFilter(
    private val minCutoff: Float,
    private val beta: Float,
    private val dCutoff: Float = 1f
) {
    private var initialized = false
    private var prevRaw = 0f
    private var prevFiltered = 0f
    private var prevDeriv = 0f
    private var prevTimeMs = 0L

    fun reset() {
        initialized = false
    }

    private fun alpha(cutoff: Float, dtSeconds: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dtSeconds)
    }

    fun filter(value: Float, nowMs: Long): Float {
        if (!initialized) {
            initialized = true
            prevRaw = value
            prevFiltered = value
            prevDeriv = 0f
            prevTimeMs = nowMs
            return value
        }

        val dt = (nowMs - prevTimeMs).coerceAtLeast(1L) / 1000f
        prevTimeMs = nowMs

        val deriv = (value - prevRaw) / dt
        val aD = alpha(dCutoff, dt)
        val derivHat = aD * deriv + (1f - aD) * prevDeriv

        val cutoff = minCutoff + beta * abs(derivHat)
        val a = alpha(cutoff, dt)
        val filtered = a * value + (1f - a) * prevFiltered

        prevRaw = value
        prevDeriv = derivHat
        prevFiltered = filtered
        return filtered
    }
}

/**
 * Bộ đếm tỉ lệ thời gian một cờ đúng trong 60 giây gần nhất
 * (60 bucket, mỗi bucket 1 giây). Dùng cho PERCLOS và tỉ lệ mất tập trung.
 */
private class WindowRatio(
    private val buckets: Int,
    private val minSampleMs: Long
) {
    private val flaggedMs = LongArray(buckets)
    private val totalMs = LongArray(buckets)
    private val bucketSecond = LongArray(buckets) { -1L }
    private var lastFrameMs = 0L

    fun reset() {
        flaggedMs.fill(0L)
        totalMs.fill(0L)
        bucketSecond.fill(-1L)
        lastFrameMs = 0L
    }

    /** Gọi khi mất khuôn mặt để không cộng dồn khoảng trống thời gian. */
    fun pause() {
        lastFrameMs = 0L
    }

    fun record(nowMs: Long, flagged: Boolean) {
        val dt = if (lastFrameMs == 0L) {
            0L
        } else {
            (nowMs - lastFrameMs).coerceIn(0L, 200L)
        }
        lastFrameMs = nowMs
        if (dt == 0L) return

        val sec = nowMs / 1000L
        val idx = (sec % buckets).toInt()

        if (bucketSecond[idx] != sec) {
            bucketSecond[idx] = sec
            flaggedMs[idx] = 0L
            totalMs[idx] = 0L
        }

        totalMs[idx] += dt
        if (flagged) {
            flaggedMs[idx] += dt
        }
    }

    fun percent(nowMs: Long): Float {
        val nowSec = nowMs / 1000L
        var flagged = 0L
        var total = 0L

        for (i in 0 until buckets) {
            val sec = bucketSecond[i]
            if (sec < 0L) continue

            val age = nowSec - sec
            if (age in 0L until buckets.toLong()) {
                flagged += flaggedMs[i]
                total += totalMs[i]
            }
        }

        return if (total < minSampleMs) {
            0f
        } else {
            flagged * 100f / total.toFloat()
        }
    }
}

class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "FocusChangeAI"
        const val PREFS_NAME = "focus_change_prefs"
        // Đổi key: bỏ hệ số cũ đã được hiệu chuẩn theo công thức sai.
        const val KEY_DISTANCE_SCALE = "distance_scale_v2"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_SOUND_MODE = "sound_mode"
        const val KEY_SENSITIVITY = "sensitivity_level"
        const val KEY_HABIT_LEARN = "habit_learn_enabled"
        const val HABIT_AUTOSAVE_MS = 30_000L
        const val HABIT_STATUS_REFRESH_MS = 1_000L

        // SEI thấp hơn mức thường ngày của chính học sinh (độ lệch 1.5 lần dao động riêng) trong 45 giây.
        const val LOW_VS_PERSONAL_MS = 45_000L
        const val LOW_VS_PERSONAL_K = 1.5f
        const val PERSONAL_SEI_MIN_SPREAD = 8f

        // Loại cảnh báo (dùng để đếm số lần xuất hiện trước khi kêu).
        const val KIND_DROWSY = 1
        const val KIND_PERCLOS = 2
        const val KIND_DRIFT = 3
        const val KIND_LOOK_AWAY = 4
        const val KIND_YAWN = 5
        const val KIND_LOW_BLINK = 6
        const val KIND_LOW_PERSONAL = 7
        const val KIND_BREAK = 8
        const val KIND_CHRONIC = 9
        const val KIND_TOO_CLOSE = 10
        const val KIND_SQUINT = 11
        const val KIND_ASYMMETRY = 12
        const val KIND_INCOMPLETE_BLINK = 13
        const val KIND_HIGH_BLINK = 14

        // Chỉ phân tích mắt khi góc đầu đủ nhỏ (ngoài ngưỡng này landmark mắt kém tin cậy).
        const val EYE_MAX_YAW_DEG = 45f
        const val EYE_MAX_PITCH_DEG = 35f

        // Chỉ kêu khi cảnh báo đã xuất hiện đủ số lần trong khung thời gian.
        const val ALERT_SOUND_AFTER_NORMAL = 2          // cảnh báo thường: kêu từ lần thứ 2
        const val ALERT_SOUND_AFTER_HABIT = 4           // cảnh báo so với thói quen: kêu từ lần thứ 4
        const val ALERT_COUNT_WINDOW_MS = 10L * 60L * 1000L
        const val ALERT_COUNT_WINDOW_HABIT_MS = 30L * 60L * 1000L
        const val ALERT_REARM_MS = 5_000L               // tắt quá 5 giây rồi hiện lại mới tính là lần mới

        // Chỉ số điểm mốc mắt (giữ nguyên theo code gốc).
        val RIGHT_EYE = intArrayOf(33, 160, 158, 133, 153, 144)
        val LEFT_EYE = intArrayOf(362, 385, 387, 263, 373, 380)

        // Điểm mốc dùng cho khoảng cách và tư thế đầu.
        const val LM_OUTER_RIGHT_EYE = 33
        const val LM_OUTER_LEFT_EYE = 263
        const val LM_NOSE_TIP = 1
        const val LM_FOREHEAD = 10
        const val LM_CHIN = 152
        const val LM_FACE_RIGHT = 234
        const val LM_FACE_LEFT = 454
        const val MIN_LANDMARKS = 468
        const val IRIS_LANDMARKS = 478

        // Mặt nạ vẽ mắt (tra cứu O(1), không tạo đối tượng khi vẽ).
        val EYE_MASK = BooleanArray(IRIS_LANDMARKS).also { mask ->
            for (i in RIGHT_EYE) mask[i] = true
            for (i in LEFT_EYE) mask[i] = true
        }

        // Mống mắt (Iris): 468-472 mắt phải, 473-477 mắt trái.
        const val LM_RIGHT_IRIS = 468
        const val LM_LEFT_IRIS = 473
        const val LM_R_EYE_OUTER = 33
        const val LM_R_EYE_INNER = 133
        const val LM_L_EYE_INNER = 362
        const val LM_L_EYE_OUTER = 263
        const val LM_R_LID_UP = 159
        const val LM_R_LID_LOW = 145
        const val LM_L_LID_UP = 386
        const val LM_L_LID_LOW = 374

        // Hai điểm ngang của mống mắt (đo đường kính).
        const val LM_R_IRIS_H1 = 469
        const val LM_R_IRIS_H2 = 471
        const val LM_L_IRIS_H1 = 474
        const val LM_L_IRIS_H2 = 476

        // Chân mày và miệng.
        const val LM_BROW_INNER_R = 55
        const val LM_BROW_INNER_L = 285
        const val LM_MOUTH_UP = 13
        const val LM_MOUTH_LOW = 14
        const val LM_MOUTH_R = 78
        const val LM_MOUTH_L = 308

        const val RAD_TO_DEG = 57.29578f

        // Mô hình lỗ kim (Pinhole): D = (W_real * f_px) / W_pixel
        const val EYE_OUTER_WIDTH_CM = 8.9f        // khóe mắt ngoài - khóe mắt ngoài (không phải IPD 6.3)
        const val IRIS_DIAMETER_CM = 1.17f         // đường kính mống mắt người, rất ổn định
        const val FALLBACK_FOCAL_PX_AT_640 = 500f  // chỉ dùng nếu không đọc được Camera2
        const val MIN_SAFE_DISTANCE_CM = 30f
        const val DISTANCE_RECOVER_CM = 32f
        const val REFERENCE_DISTANCE_CM = 50f
        const val DIST_CAL_SAMPLES = 20
        const val DIST_MEDIAN_SIZE = 5

        // Hiệu chuẩn EAR thích nghi.
        const val CALIBRATION_FRAMES = 60
        const val CALIBRATION_MIN_EAR = 0.20f
        // Mắt nhỏ hoặc đeo kính: nếu sau 6 giây vẫn chưa gom được 10 khung hợp lệ thì hạ ngưỡng.
        const val CAL_RELAX_AFTER_MS = 6_000L
        const val CAL_RELAX_MIN_FRAMES = 10
        const val CAL_RELAXED_MIN_EAR = 0.12f
        const val CLOSE_RATIO = 0.68f

        // Chớp mắt / BPM.
        const val BPM_WINDOW_MS = 60_000L
        const val BPM_MIN_WINDOW_MS = 10_000L
        const val BPM_RELIABLE_AFTER_MS = 15_000L
        const val DROWSY_CLOSURE_MS = 1_500L
        const val LOW_BLINK_BPM = 8f
        const val HIGH_BLINK_BPM = 30f

        // PERCLOS và tỉ lệ mất tập trung (60 giây).
        const val RATIO_BUCKETS = 60
        const val RATIO_MIN_SAMPLE_MS = 3_000L
        const val PERCLOS_ALERT = 15f

        // Tư thế đầu 3D.
        const val LOOK_AWAY_YAW_DEG = 18f
        const val LOOK_AWAY_PITCH_DEG = 18f
        const val LOOK_AWAY_MS = 2_000L
        const val HEAD_FRONTAL_YAW_DEG = 20f

        // Phát hiện đóng băng / giật bất thường (Anti-Spoofing).
        const val POSE_WINDOW_MS = 10_000L
        const val FREEZE_MIN_SAMPLES = 60
        const val FREEZE_POSE_STD_DEG = 0.15f
        const val FREEZE_EAR_STD = 0.006f
        const val FREEZE_NO_BLINK_MS = 10_000L
        const val JERK_SPEED_DEG_PER_S = 200f
        const val JERK_MIN_GAP_MS = 300L
        const val JERK_WINDOW_MS = 10_000L
        const val JERK_COUNT_ALERT = 3

        // Hướng nhìn (Gaze) và Visual Drift.
        const val GAZE_H_DRIFT = 0.16f
        const val GAZE_V_DRIFT = 0.22f
        const val VISUAL_DRIFT_MS = 3_000L

        // Vi biểu cảm.
        const val BFR_FURROW_RATIO = 0.88f
        const val BFR_SUSTAIN_MS = 2_000L
        const val MAR_YAWN = 0.55f
        const val YAWN_MIN_MS = 1_200L
        const val YAWN_WINDOW_MS = 5L * 60L * 1000L

        // Chất lượng tín hiệu.
        const val LUMA_VERY_LOW = 35f
        const val LUMA_LOW = 60f
        const val LUMA_HIGH = 220f
        const val MIN_FACE_PX = 40f
        const val VERY_SMALL_FACE_PX = 25f
        const val QUALITY_OK = 50
        const val LUMA_SAMPLE_STEP = 8

        // Động cơ mất tập trung tích lũy.
        const val VIOLATION_WINDOW_MS = 5L * 60L * 1000L
        const val V_HEAD_AWAY = 1.0f
        const val V_VISUAL_DRIFT = 1.0f
        const val V_YAWN = 1.5f
        const val V_DROWSY = 2.0f
        const val V_TOO_CLOSE = 1.0f
        const val V_CONFUSED = 0.5f
        const val CHRONIC_WATCH = 3f
        const val CHRONIC_ON = 6f
        const val CHRONIC_OFF = 4f
        const val INATTENTION_RATIO_ALERT = 40f

        // Nghỉ giải lao 20-20-20.
        const val BREAK_REMINDER_MS = 20L * 60L * 1000L
        const val BREAK_REMINDER_SHOW_MS = 30_000L
        const val BREAK_DETECT_MS = 20_000L

        // SEI 2.0.
        const val SEI_SMOOTHING = 0.12f
        const val SEI_NO_FACE_DECAY = 0.08f
        const val SEI_TOO_CLOSE_PENALTY = 15f
        const val SEI_CHRONIC_PENALTY = 15f
        const val SEI_WATCH_PENALTY = 5f
        const val SEI_ANOMALY_CAP = 30f
        const val W_POSTURE = 0.20f
        const val W_DISTANCE = 0.15f
        const val W_GAZE = 0.20f
        const val W_EYES = 0.20f
        const val W_BPM = 0.10f
        const val W_EXPRESSION = 0.15f

        // Chỉ số mệt mỏi.
        const val FATIGUE_SMOOTHING = 0.10f

        // Giao diện.
        const val HISTORY_SIZE = 120
        const val UI_TEXT_REFRESH_MS = 120L

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

    private var landmarkPoints by mutableStateOf(EMPTY_LANDMARKS)
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

    private var headPoseText by mutableStateOf("--")
    private var gazeText by mutableStateOf("--")
    private var expressionText by mutableStateOf("--")
    private var expressionMetricsText by mutableStateOf("--")
    private var inattentionText by mutableStateOf("--")
    private var chronicLevel by mutableIntStateOf(0)
    private var chronicText by mutableStateOf("")
    private var anomalyText by mutableStateOf("")

    private var fatigueText by mutableStateOf("--")
    private var signalText by mutableStateOf("--")
    private var seiHistory by mutableStateOf(emptyList<Int>())
    private var soundMode by mutableStateOf(2) // 0 tắt, 1 chỉ cảnh báo quan trọng, 2 đầy đủ

    private var studentName by mutableStateOf("")
    private var habitText by mutableStateOf("--")
    private var habitDetailText by mutableStateOf("")
    private var habitHistoryText by mutableStateOf("")
    private var eyeDetailText by mutableStateOf("--")
    private var sensitivityUi by mutableStateOf(1)
    private var habitLearnUi by mutableStateOf(true)
    private var showAddStudent by mutableStateOf(false)
    private var showResetHabit by mutableStateOf(false)
    private var newStudentName by mutableStateOf("")
    private var activeStudentId = ""

    // Trạng thái chỉ dùng trên UI thread
    private var lastSnapshot: HudSnapshot? = null
    private var lastHistorySecond = 0L
    private var lastUiTextMs = 0L

    // Độ nhạy: 0 thấp, 1 vừa, 2 cao. Ghi từ UI thread, đọc từ cameraExecutor.
    @Volatile
    private var sensLevel = 1
    private var lastChronicLevelUi = 0
    private var lastTooCloseUi = false
    private var lastAnyToneMs = -1_000_000L
    private val alertOccurrences = Array(15) { ArrayList<Long>() }
    private val lastKindActiveMs = LongArray(15) { -1_000_000L }
    private val lastToneMs = LongArray(5) { -1_000_000L }
    private var toneGenerator: ToneGenerator? = null

    // ---------------- Trạng thái xử lý (chỉ dùng trên cameraExecutor) ----------------
    @Volatile
    private var recalibrationRequested = false

    @Volatile
    private var distanceCalibrationRequested = false

    @Volatile
    private var distanceScale = 1f

    // Hiệu chuẩn EAR + đường cơ sở cá nhân
    private var calibrated = false
    private var calFrames = 0
    private var calWaitStartMs = 0L
    private var calRejected = 0
    private var calSumEar1 = 0f
    private var calSumEar2 = 0f
    private var calSumYawDeg = 0f
    private var calSumPitchDeg = 0f
    private var calSumGazeH = 0f
    private var calSumGazeV = 0f
    private var calSumBfr = 0f
    private var baselineEar1 = 0f
    private var baselineEar2 = 0f
    private var closeThreshold1 = 0f
    private var closeThreshold2 = 0f
    private var baselineYawDeg = 0f
    private var baselinePitchDeg = 0f
    private var baselineGazeH = 0.5f
    private var baselineGazeV = 0.5f
    private var baselineBfr = 0.5f

    // Góc tư thế tuyệt đối (độ) tính từ điểm mốc 3D
    private var poseYawAbs = 0f
    private var posePitchAbs = 0f
    private var poseRollAbs = 0f

    // Bộ lọc One Euro
    private val yawFilter = OneEuroFilter(1.5f, 0.30f)
    private val pitchFilter = OneEuroFilter(1.5f, 0.30f)
    private val rollFilter = OneEuroFilter(1.5f, 0.30f)
    private val gazeHFilter = OneEuroFilter(1.5f, 5.0f)
    private val gazeVFilter = OneEuroFilter(1.5f, 5.0f)
    private val distanceFilter = OneEuroFilter(1.0f, 0.05f)

    // Thông số camera thật (để tính tiêu cự pixel chính xác)
    private var sensorFocalMm = 0f
    private var sensorPhysWmm = 0f
    private var sensorPhysHmm = 0f

    @Volatile
    private var streamW = 640

    @Volatile
    private var streamH = 480

    // Cửa sổ trung vị khoảng cách + mẫu hiệu chuẩn khoảng cách
    private val distWindow = ArrayDeque<Float>()
    private val distCalSamples = ArrayList<Float>()

    // Công cụ phân tích mắt nâng cấp (kính, góc nghiêng, nheo mắt, lệch hai mắt, chớp không hoàn toàn).
    private val eyeEngine = EyeEngine()

    // ---------------- Học thói quen từng học sinh (chỉ dùng trên cameraExecutor) ----------------
    private lateinit var habitStore: HabitStore
    private var habitProfile: HabitProfile? = null
    private var habitSession: HabitSession? = null
    private var lastHabitSaveMs = 0L
    private var lastHabitStatusMs = 0L
    private var habitStatus = "Đang nạp hồ sơ học sinh..."
    private var habitDetail = ""
    private var habitHistory = ""
    private var personalSeiMean = Float.NaN
    private var personalSeiSpread = 0f
    private var lowVsPersonalStartMs = 0L
    private var lastSeiValue = -1

    @Volatile
    private var habitLearnEnabled = true

    @Volatile
    private var studentSwitchRequest: String? = null

    @Volatile
    private var habitResetRequested = false

    // Ngưỡng hiện hành: mặc định, sẽ được thay bằng ngưỡng cá nhân khi hồ sơ đã đủ dữ liệu.
    private var thrLookYaw = LOOK_AWAY_YAW_DEG
    private var thrLookPitch = LOOK_AWAY_PITCH_DEG
    private var thrGazeH = GAZE_H_DRIFT
    private var thrGazeV = GAZE_V_DRIFT
    private var thrBlinkLow = LOW_BLINK_BPM
    private var thrBlinkHigh = HIGH_BLINK_BPM
    private var thrPerclos = PERCLOS_ALERT
    private var thrMar = MAR_YAWN

    // Máy trạng thái chớp mắt
    private var eyesClosed = false
    private var closedStartMs = 0L
    private var lastBlinkMs = 0L
    private val blinkTimestamps = mutableListOf<Long>()
    private val blinkDurations = mutableListOf<Long>()
    private var monitoringStartMs = 0L

    // PERCLOS và tỉ lệ mất tập trung
    private val perclos = WindowRatio(RATIO_BUCKETS, RATIO_MIN_SAMPLE_MS)
    private val inattention = WindowRatio(RATIO_BUCKETS, RATIO_MIN_SAMPLE_MS)

    // Khoảng cách
    private var smoothedDistanceCm: Float? = null
    private var distanceAlertActive = false

    // Tư thế đầu
    private var prevNoseX = Float.NaN
    private var prevNoseY = Float.NaN
    private var jitterEma = 0f
    private var lookAwayStartMs = 0L

    // Cửa sổ trượt tư thế đầu (Anti-Spoofing)
    private val poseWindow = ArrayDeque<PoseSample>()
    private val jerkTimestamps = mutableListOf<Long>()
    private var lastJerkMs = 0L

    // Visual Drift
    private var driftStartMs = 0L

    // Vi biểu cảm
    private var furrowStartMs = 0L
    private var yawnStartMs = 0L
    private var yawnCounted = false
    private val yawnTimestamps = mutableListOf<Long>()

    // Động cơ mất tập trung tích lũy
    private val violationEvents = ArrayDeque<ViolationEvent>()
    private var prevHeadAway = false
    private var prevDrift = false
    private var prevDrowsy = false
    private var prevTooClose = false
    private var prevConfused = false
    private var chronicActive = false
    private var chronicLevelValue = 0

    // Thống kê phiên
    private var violationsTotal = 0
    private var chronicEpisodes = 0
    private var yawnTotal = 0
    private var minSeiValue = Int.MAX_VALUE

    // Chất lượng tín hiệu
    private var lumaMean = 128f
    private var signalNote = "Tốt"
    private var signalReliable = true

    // Chỉ số mệt mỏi
    private var fatigueEma = 0f

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
    private var frameDtMs = 33f

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
        soundMode = prefs.getInt(
            KEY_SOUND_MODE,
            if (prefs.getBoolean(KEY_SOUND, true)) 2 else 0
        )

        sensLevel = prefs.getInt(KEY_SENSITIVITY, 1).coerceIn(0, 2)
        sensitivityUi = sensLevel

        habitStore = HabitStore(this)
        habitLearnEnabled = prefs.getBoolean(KEY_HABIT_LEARN, true)
        habitLearnUi = habitLearnEnabled
        activeStudentId = habitStore.currentId()
        studentName = habitStore.nameOf(activeStudentId)
        studentSwitchRequest = activeStudentId

        cameraExecutor = Executors.newSingleThreadExecutor()
        loadCameraIntrinsics()

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

                    val statsScroll = rememberScrollState()

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

                        Text(text = status)

                        val seiLabel = seiLabelOf(seiValue)
                        Text(
                            text = if (seiValue >= 0) {
                                "SEI 2.0: $seiValue / 100 ($seiLabel)"
                            } else {
                                "SEI 2.0: -- / 100"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = seiColorOf(seiValue)
                        )

                        // Biểu đồ SEI 2 phút gần nhất.
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            val history = seiHistory
                            if (history.size < 2) return@Canvas

                            val stepX = size.width / (HISTORY_SIZE - 1).toFloat()
                            val y60 = size.height * (1f - 60f / 100f)

                            drawLine(
                                color = Color.Gray.copy(alpha = 0.4f),
                                start = Offset(0f, y60),
                                end = Offset(size.width, y60),
                                strokeWidth = 1f
                            )

                            val lineColor = seiColorOf(history.last())
                            for (i in 1 until history.size) {
                                val x0 = (i - 1) * stepX
                                val x1 = i * stepX
                                val y0 = size.height * (1f - history[i - 1] / 100f)
                                val y1 = size.height * (1f - history[i] / 100f)

                                drawLine(
                                    color = lineColor,
                                    start = Offset(x0, y0),
                                    end = Offset(x1, y1),
                                    strokeWidth = 3f
                                )
                            }
                        }

                        if (chronicText.isNotEmpty()) {
                            Text(
                                text = chronicText,
                                color = if (chronicLevel >= 2) Color.Red else Color(0xFFEF6C00),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        if (anomalyText.isNotEmpty()) {
                            Text(
                                text = anomalyText,
                                color = Color.Red,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        if (distanceAlert) {
                            Text(
                                text = "⚠ Cảnh báo: bạn đang ngồi quá gần màn hình (< 30 cm)!",
                                color = Color.Red,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        if (alertText.isNotEmpty()) {
                            Text(
                                text = alertText,
                                color = Color(0xFFEF6C00),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        // Khu vực thông số có thể cuộn để khung camera luôn đủ lớn.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.8f)
                                .verticalScroll(statsScroll)
                        ) {
                            Text(
                                text = "Học sinh: $studentName",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(text = habitText)
                            if (habitDetailText.isNotEmpty()) {
                                Text(
                                    text = habitDetailText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (habitHistoryText.isNotEmpty()) {
                                Text(
                                    text = "Các phiên gần đây:\n$habitHistoryText",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { switchStudent() }) {
                                    Text("Đổi HS")
                                }
                                Button(
                                    onClick = {
                                        newStudentName = ""
                                        showAddStudent = true
                                    }
                                ) {
                                    Text("Thêm HS")
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { toggleHabitLearn() }) {
                                    Text(
                                        if (habitLearnUi) "Học thói quen: Bật"
                                        else "Học thói quen: Tắt"
                                    )
                                }
                                Button(onClick = { showResetHabit = true }) {
                                    Text("Xóa hồ sơ")
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { cycleSensitivity() }) {
                                    Text(
                                        when (sensitivityUi) {
                                            0 -> "Độ nhạy: Thấp"
                                            2 -> "Độ nhạy: Cao"
                                            else -> "Độ nhạy: Vừa"
                                        }
                                    )
                                }
                            }

                            if (showAddStudent) {
                                AlertDialog(
                                    onDismissRequest = { showAddStudent = false },
                                    title = { Text("Thêm học sinh") },
                                    text = {
                                        OutlinedTextField(
                                            value = newStudentName,
                                            onValueChange = { newStudentName = it.take(24) },
                                            singleLine = true,
                                            label = { Text("Tên hoặc mã học sinh") }
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                addStudent(newStudentName)
                                                showAddStudent = false
                                            }
                                        ) {
                                            Text("Thêm")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showAddStudent = false }) {
                                            Text("Hủy")
                                        }
                                    }
                                )
                            }

                            if (showResetHabit) {
                                AlertDialog(
                                    onDismissRequest = { showResetHabit = false },
                                    title = { Text("Xóa hồ sơ thói quen?") },
                                    text = {
                                        Text("Toàn bộ dữ liệu đã học của $studentName sẽ bị xóa và không khôi phục được.")
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                habitResetRequested = true
                                                showResetHabit = false
                                                seiValue = -1
                                                seiHistory = emptyList()
                                                calibrationText = "Đang hiệu chuẩn lại..."
                                            }
                                        ) {
                                            Text("Xóa")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showResetHabit = false }) {
                                            Text("Hủy")
                                        }
                                    }
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            Text(
                                text = "Số khuôn mặt: $faceCount",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(text = "Số điểm mốc: ${landmarkPoints.count}")

                            Text(
                                text = "Số lần chớp mắt: $blinkCount",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Text(text = "Nhịp chớp mắt (BPM): $bpmText")

                            Text(text = "EAR mắt 1: $ear1Text")
                            Text(text = "EAR mắt 2: $ear2Text")
                            Text(text = "Trạng thái mắt: $eyeStatus")
                            Text(text = "Phân tích mắt: $eyeDetailText")

                            Text(
                                text = "Khoảng cách: $distanceText (hệ số $distanceScaleText)",
                                color = if (distanceAlert) Color.Red
                                else MaterialTheme.colorScheme.onSurface
                            )

                            Text(text = "Tư thế đầu: $headPoseText")
                            Text(text = "Hướng nhìn: $gazeText")
                            Text(text = "Vi biểu cảm: $expressionText")
                            Text(text = "Chỉ số: $expressionMetricsText")
                            Text(text = "Mất tập trung: $inattentionText")

                            Text(text = "Mệt mỏi (PERCLOS): $perclosText")
                            Text(text = "Chỉ số mệt mỏi: $fatigueText")
                            Text(text = "Chất lượng tín hiệu: $signalText")
                            Text(text = "Hiệu chuẩn: $calibrationText")
                            Text(text = "Phiên làm việc: $sessionText")
                            Text(text = "Thời gian nhận diện: $processingText ($fpsText FPS)")
                            Text(
                                text = "Dữ liệu và hồ sơ thói quen chỉ xử lý và lưu trên thiết bị, không gửi ra ngoài.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    recalibrationRequested = true
                                    resetAlertCounters()
                                    calibrationText = "Đang hiệu chuẩn lại..."
                                    seiValue = -1
                                    seiHistory = emptyList()
                                }
                            ) {
                                Text("Hiệu chuẩn lại")
                            }

                            Button(
                                onClick = {
                                    distanceCalibrationRequested = true
                                }
                            ) {
                                Text("Chỉnh 50 cm")
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    // Đầy đủ -> Chỉ quan trọng -> Tắt -> Đầy đủ
                                    soundMode = if (soundMode == 0) 2 else soundMode - 1
                                    prefs.edit().putInt(KEY_SOUND_MODE, soundMode).apply()
                                    if (soundMode > 0) {
                                        // Phát thử để học sinh nghe mức âm lượng.
                                        playAlertSound(2, SystemClock.elapsedRealtime(), true)
                                    }
                                }
                            ) {
                                Text(
                                    when (soundMode) {
                                        2 -> "Âm thanh: Đầy đủ"
                                        1 -> "Âm thanh: Quan trọng"
                                        else -> "Âm thanh: Tắt"
                                    }
                                )
                            }

                            Button(
                                onClick = {
                                    shareReport()
                                }
                            ) {
                                Text("Chia sẻ báo cáo")
                            }
                        }

                        Spacer(Modifier.height(8.dp))

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
                                val lm = landmarkPoints
                                if (lm.count == 0) return@Canvas

                                // Ánh xạ khớp với FILL_CENTER (phóng to + cắt giữa).
                                val fw = frameWidth.toFloat().coerceAtLeast(1f)
                                val fh = frameHeight.toFloat().coerceAtLeast(1f)
                                val scale = max(size.width / fw, size.height / fh)
                                val offsetX = (size.width - fw * scale) / 2f
                                val offsetY = (size.height - fh * scale) / 2f

                                for (index in 0 until lm.count) {
                                    val isIris = index >= LM_RIGHT_IRIS
                                    val isEye = index < EYE_MASK.size && EYE_MASK[index]

                                    drawCircle(
                                        color = when {
                                            isIris -> Color.Cyan
                                            isEye -> Color.Yellow
                                            else -> Color.Green
                                        },
                                        radius = when {
                                            isIris -> 3f
                                            isEye -> 4f
                                            else -> 2f
                                        },
                                        center = Offset(
                                            // Camera trước: lật gương theo trục X.
                                            (1f - lm.x(index)) * fw * scale + offsetX,
                                            lm.y(index) * fh * scale + offsetY
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

    private fun fatigueLabelOf(index: Int): String = when {
        index < 25 -> "Tỉnh táo"
        index < 50 -> "Hơi mệt"
        index < 75 -> "Mệt"
        else -> "Rất mệt"
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

    // ======================= THÔNG SỐ CAMERA THẬT =======================

    private fun loadCameraIntrinsics() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) !=
                    CameraCharacteristics.LENS_FACING_FRONT
                ) continue

                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: continue
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue

                sensorFocalMm = f
                sensorPhysWmm = max(size.width, size.height)
                sensorPhysHmm = min(size.width, size.height)
                Log.d(TAG, "Camera trước: f=${f}mm sensor=${size.width}x${size.height}mm")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không đọc được thông số camera", e)
        }
    }

    /** Tiêu cự theo pixel của khung đang phân tích (đã tính phần sensor bị crop). */
    private fun focalPx(): Float {
        val longPx = max(streamW, streamH).toFloat()
        val shortPx = min(streamW, streamH).toFloat()

        if (sensorFocalMm <= 0f || sensorPhysWmm <= 0f || sensorPhysHmm <= 0f) {
            return FALLBACK_FOCAL_PX_AT_640 * (longPx / 640f)
        }

        val sensorAspect = sensorPhysWmm / sensorPhysHmm
        val streamAspect = longPx / shortPx
        val pxPerMm = if (sensorAspect >= streamAspect) {
            shortPx / sensorPhysHmm   // sensor rộng hơn: bị cắt hai bên
        } else {
            longPx / sensorPhysWmm    // sensor hẹp hơn: bị cắt trên dưới
        }
        return sensorFocalMm * pxPerMm
    }

    // ======================= HỌC THÓI QUEN THEO TỪNG HỌC SINH =======================

    // ---- Chạy trên UI thread ----

    private fun selectStudent(st: HabitStudent) {
        activeStudentId = st.id
        habitStore.setCurrent(st.id)
        studentName = st.name
        studentSwitchRequest = st.id
        resetAlertCounters()
        blinkCount = 0
        seiValue = -1
        seiHistory = emptyList()
        calibrationText = "Đang hiệu chuẩn lại..."
    }

    private fun switchStudent() {
        val list = habitStore.students()
        if (list.size < 2) {
            status = "Mới có 1 hồ sơ học sinh, hãy bấm Thêm HS."
            return
        }
        val idx = list.indexOfFirst { it.id == activeStudentId }
        selectStudent(list[(idx + 1).mod(list.size)])
    }

    private fun addStudent(name: String) {
        selectStudent(habitStore.addStudent(name))
    }

    private fun toggleHabitLearn() {
        habitLearnEnabled = !habitLearnEnabled
        habitLearnUi = habitLearnEnabled
        prefs.edit().putBoolean(KEY_HABIT_LEARN, habitLearnEnabled).apply()
    }

    private fun cycleSensitivity() {
        sensLevel = (sensLevel + 1) % 3
        sensitivityUi = sensLevel
        prefs.edit().putInt(KEY_SENSITIVITY, sensLevel).apply()
    }

    // ---- Chạy trên cameraExecutor ----

    /** Xử lý yêu cầu đổi học sinh / xóa hồ sơ do UI gửi sang. */
    private fun processHabitRequests() {
        val switchTo = studentSwitchRequest
        if (switchTo != null) {
            studentSwitchRequest = null
            endHabitSession()
            habitProfile = habitStore.load(switchTo)
            resetPersonalParams()
            lastHabitStatusMs = 0L
            recalibrationRequested = false
            resetCalibration()
        }

        if (habitResetRequested) {
            habitResetRequested = false
            val id = habitProfile?.id
            habitSession = null
            if (id != null) {
                habitStore.clear(id)
                habitProfile = habitStore.load(id)
            }
            resetPersonalParams()
            lastHabitStatusMs = 0L
            recalibrationRequested = false
            resetCalibration()
        }
    }

    private fun resetPersonalParams() {
        thrLookYaw = LOOK_AWAY_YAW_DEG
        thrLookPitch = LOOK_AWAY_PITCH_DEG
        thrGazeH = GAZE_H_DRIFT
        thrGazeV = GAZE_V_DRIFT
        thrBlinkLow = LOW_BLINK_BPM
        thrBlinkHigh = HIGH_BLINK_BPM
        thrPerclos = PERCLOS_ALERT
        thrMar = MAR_YAWN
        habitDetail = ""
        personalSeiMean = Float.NaN
        personalSeiSpread = 0f
        lowVsPersonalStartMs = 0L
        lastSeiValue = -1
    }

    /** Thay ngưỡng mặc định bằng ngưỡng cá nhân, trộn theo độ tin cậy c (0..1). */
    private fun applyPersonalParams(base: HabitBaseline, c: Float) {
        fun mix(default: Float, personal: Float?): Float =
            if (personal == null) default else default * (1f - c) + personal * c

        thrLookYaw = mix(LOOK_AWAY_YAW_DEG, base.threshold(HabitFeature.YAW, 2.5f, 12f, 30f))
        thrLookPitch = mix(LOOK_AWAY_PITCH_DEG, base.threshold(HabitFeature.PITCH, 2.5f, 12f, 30f))
        thrGazeH = mix(GAZE_H_DRIFT, base.threshold(HabitFeature.GAZE_H, 2.5f, 0.10f, 0.30f))
        thrGazeV = mix(GAZE_V_DRIFT, base.threshold(HabitFeature.GAZE_V, 2.5f, 0.14f, 0.40f))

        val bpmMean = base.mean(HabitFeature.BPM)
        val bpmSpread = base.spread(HabitFeature.BPM)
        if (bpmMean != null && bpmSpread != null) {
            thrBlinkLow = mix(LOW_BLINK_BPM, (bpmMean - 2f * bpmSpread).coerceIn(4f, 12f))
            thrBlinkHigh = mix(HIGH_BLINK_BPM, (bpmMean + 2f * bpmSpread).coerceIn(24f, 40f))
        }

        val pMean = base.mean(HabitFeature.PERCLOS)
        val pSpread = base.spread(HabitFeature.PERCLOS)
        if (pMean != null && pSpread != null) {
            thrPerclos = mix(PERCLOS_ALERT, (pMean + 3f * pSpread).coerceIn(10f, 25f))
        }

        val mMean = base.mean(HabitFeature.MAR)
        val mSpread = base.spread(HabitFeature.MAR)
        if (mMean != null && mSpread != null) {
            thrMar = mix(MAR_YAWN, (mMean + 5f * mSpread).coerceIn(0.45f, 0.70f))
        }

        val sMean = base.mean(HabitFeature.SEI)
        val sSpread = base.spread(HabitFeature.SEI)
        if (sMean != null && sSpread != null) {
            personalSeiMean = sMean
            personalSeiSpread = max(sSpread, PERSONAL_SEI_MIN_SPREAD)
        }

        habitDetail = buildString {
            append("Mức cơ sở cá nhân: ")
            base.mean(HabitFeature.BPM)?.let {
                append(String.format(Locale.US, "chớp %.0f lần/phút; ", it))
            }
            base.mean(HabitFeature.DIST)?.let {
                append(String.format(Locale.US, "ngồi cách %.0f cm; ", it))
            }
            append(
                String.format(
                    Locale.US,
                    "cảnh báo quay đầu > %.0f°/%.0f°; liếc mắt > %.2f/%.2f; ",
                    thrLookYaw, thrLookPitch, thrGazeH, thrGazeV
                )
            )
            append(String.format(Locale.US, "PERCLOS cảnh báo > %.0f%%", thrPerclos))
            base.mean(HabitFeature.SEI)?.let {
                append(String.format(Locale.US, "; SEI quen thuộc %.0f", it))
            }
        }
    }

    /** Gọi khi hiệu chuẩn xong: mở phiên mới, áp ngưỡng cá nhân, trộn EAR cơ sở. */
    private fun beginHabitSession(nowMs: Long) {
        val prof = habitProfile
        resetPersonalParams()
        lastHabitSaveMs = nowMs
        lastHabitStatusMs = 0L

        if (prof == null) {
            habitSession = null
            return
        }

        val session = HabitSession(System.currentTimeMillis())
        habitSession = session

        // Ghi lại EAR cơ sở THÔ của phiên này (trước khi trộn) để không tự khuếch đại.
        if (habitLearnEnabled) {
            session.observe(HabitFeature.EAR, (baselineEar1 + baselineEar2) / 2f)
        }

        if (!prof.isReady()) return

        val base = prof.baseline()
        val c = prof.confidence()
        applyPersonalParams(base, c)
        blendEarWithHabit(base, c)
    }

    /**
     * Trộn EAR cơ sở của phiên với EAR điển hình đã học (tối đa 35% khi đủ tin cậy).
     * Nếu lệch quá 30% thì giữ nguyên giá trị mới (có thể vừa đổi kính hoặc điều kiện sáng).
     */
    private fun blendEarWithHabit(base: HabitBaseline, c: Float) {
        val learned = base.mean(HabitFeature.EAR) ?: return
        val current = (baselineEar1 + baselineEar2) / 2f
        if (current <= 0f) return

        val ratio = learned / current
        if (ratio < 0.7f || ratio > 1.3f) return

        val w = 0.35f * c
        val k = (1f - w) + w * ratio
        baselineEar1 *= k
        baselineEar2 *= k
    }

    private fun commitHabitSession() {
        val prof = habitProfile ?: return
        val session = habitSession ?: return
        if (!habitLearnEnabled) return
        if (prof.commit(session.toSummary())) {
            habitStore.save(prof)
        }
    }

    private fun endHabitSession() {
        commitHabitSession()
        habitSession = null
    }

    /** Lưu định kỳ và làm mới dòng trạng thái (mỗi giây một lần). */
    private fun maintainHabit(nowMs: Long) {
        if (nowMs - lastHabitSaveMs >= HABIT_AUTOSAVE_MS) {
            lastHabitSaveMs = nowMs
            commitHabitSession()
        }
        if (nowMs - lastHabitStatusMs >= HABIT_STATUS_REFRESH_MS) {
            lastHabitStatusMs = nowMs
            habitStatus = buildHabitStatus()
            habitHistory = habitProfile?.historyText(5) ?: ""
        }
    }

    private fun buildHabitStatus(): String {
        val prof = habitProfile ?: return "Đang nạp hồ sơ học sinh..."
        val n = prof.sessionCount()
        if (!habitLearnEnabled) {
            return "Học thói quen đang tắt (hồ sơ có $n phiên)."
        }

        val totalMin = prof.totalValidMs() / 60000f
        val sb = StringBuilder()
        if (prof.isReady()) {
            sb.append(
                String.format(
                    Locale.US,
                    "Hồ sơ cá nhân: %d phiên, %.0f phút - đang dùng ngưỡng riêng (tin cậy %.0f%%).",
                    n, totalMin, prof.confidence() * 100f
                )
            )
        } else {
            sb.append(
                String.format(
                    Locale.US,
                    "Đang học thói quen: %d/%d phiên đạt, %.0f/%d phút hợp lệ.",
                    n,
                    HabitConfig.MIN_SESSIONS,
                    totalMin,
                    HabitConfig.MIN_TOTAL_MS / 60000L
                )
            )
        }

        val session = habitSession
        if (session == null) {
            sb.append(" Phiên mới sẽ bắt đầu sau khi hiệu chuẩn xong.")
        } else {
            sb.append(
                String.format(Locale.US, " Phiên này: %.1f phút hợp lệ", session.validMs / 60000f)
            )
            val reason = prof.ineligibleReason(session.toSummary())
            if (reason != null) {
                sb.append(" (chưa đủ điều kiện lưu: $reason)")
            } else {
                sb.append(" (đủ điều kiện, đang được lưu)")
            }
        }
        if (!personalSeiMean.isNaN() && lastSeiValue >= 0) {
            sb.append(
                String.format(
                    Locale.US,
                    " SEI hiện tại %d, thường ngày ~%.0f (%+.0f).",
                    lastSeiValue, personalSeiMean, lastSeiValue - personalSeiMean
                )
            )
        }
        return sb.toString()
    }

    // ======================= BÁO CÁO & ÂM BÁO (UI thread) =======================

    private fun buildReport(): String {
        val s = lastSnapshot ?: return "Chưa có dữ liệu phiên làm việc."

        val minutes = s.sessionSeconds / 60L
        val seconds = s.sessionSeconds % 60L

        return buildString {
            append("BÁO CÁO PHIÊN - FocusChangeAI\n")
            append(String.format(Locale.US, "Thời lượng theo dõi: %02d:%02d\n", minutes, seconds))
            append("SEI hiện tại: ${s.sei ?: "--"} / 100\n")
            append("SEI trung bình: ${s.averageSei ?: "--"} | thấp nhất: ${s.minSei ?: "--"}\n")
            append("Số lần chớp mắt: $blinkCount\n")
            append("BPM hiện tại: ${s.bpm?.let { String.format(Locale.US, "%.1f", it) } ?: "--"}\n")
            append(
                "Thời lượng chớp trung bình: " +
                        (s.avgBlinkMs?.let { String.format(Locale.US, "%.0f ms", it) } ?: "--") + "\n"
            )
            append("Chỉ số mệt mỏi: ${s.fatigueIndex ?: "--"} / 100\n")
            append("Số lần ngáp: ${s.yawnTotal}\n")
            append("Số lần mất tập trung được ghi nhận: ${s.violationsTotal}\n")
            append("Số đợt mất tập trung kéo dài: ${s.chronicEpisodes}\n")
            append("Phân tích mắt: ${s.eyeDetail}\n")
            append("Học sinh: $studentName\n")
            append(s.habitStatus).append("\n")
            if (s.habitDetail.isNotEmpty()) append(s.habitDetail).append("\n")
            if (s.habitHistory.isNotEmpty()) {
                append("Các phiên gần đây:\n")
                append(s.habitHistory).append("\n")
            }
            append("Lưu ý: các chỉ số chỉ mang tính tham khảo, không phải chẩn đoán y tế.")
        }
    }

    private fun shareReport() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Báo cáo FocusChangeAI")
            putExtra(Intent.EXTRA_TEXT, buildReport())
        }

        try {
            startActivity(Intent.createChooser(intent, "Chia sẻ báo cáo"))
        } catch (e: Exception) {
            Log.w(TAG, "Không mở được hộp thoại chia sẻ", e)
            status = "Không thể chia sẻ báo cáo."
        }
    }

    /** Mức âm của từng loại cảnh báo: 1 nhắc nhẹ, 2 cảnh báo, 3 khẩn cấp, 4 nghỉ giải lao. */
    private fun alertLevelOf(kind: Int): Int = when (kind) {
        KIND_DROWSY, KIND_CHRONIC -> 3
        KIND_PERCLOS, KIND_YAWN, KIND_TOO_CLOSE -> 2
        KIND_BREAK -> 4
        else -> 1
    }

    /**
     * Số lần cảnh báo phải xuất hiện thì mới kêu.
     * Buồn ngủ, mất tập trung kéo dài và nhắc nghỉ giải lao kêu ngay từ lần đầu.
     */
    private fun alertSoundAfter(kind: Int): Int = when (kind) {
        KIND_DROWSY, KIND_CHRONIC, KIND_BREAK -> 1
        KIND_LOW_PERSONAL -> ALERT_SOUND_AFTER_HABIT
        else -> ALERT_SOUND_AFTER_NORMAL
    }

    /** Ghi nhận một lần xuất hiện; trả về true nếu đã đủ số lần để kêu. */
    private fun registerAlertOccurrence(kind: Int, nowMs: Long): Boolean {
        val window = if (kind == KIND_LOW_PERSONAL) ALERT_COUNT_WINDOW_HABIT_MS
        else ALERT_COUNT_WINDOW_MS

        val list = alertOccurrences[kind]
        list.add(nowMs)
        while (list.isNotEmpty() && nowMs - list[0] > window) {
            list.removeAt(0)
        }
        return list.size >= alertSoundAfter(kind)
    }

    private fun resetAlertCounters() {
        for (list in alertOccurrences) list.clear()
        lastKindActiveMs.fill(-1_000_000L)
    }

    /**
     * Âm báo theo mức độ, mỗi mức một âm khác nhau để nghe là biết loại cảnh báo:
     * 1 nhắc nhẹ, 2 cảnh báo (bíp đôi), 3 khẩn cấp (âm dài), 4 nghỉ giải lao (âm dịu).
     * Mỗi mức có khoảng nghỉ riêng để không kêu liên tục làm phiền cả lớp.
     */
    private fun playAlertSound(level: Int, nowMs: Long, force: Boolean = false) {
        if (level !in 1..4) return

        if (!force) {
            val allowed = when (soundMode) {
                2 -> true
                1 -> level == 2 || level == 3
                else -> false
            }
            if (!allowed) return

            val minGap = when (level) {
                1 -> 20_000L
                2 -> 15_000L
                3 -> 8_000L
                else -> 60_000L
            }
            if (nowMs - lastToneMs[level] < minGap) return
            // Tránh hai âm chồng lên nhau; âm khẩn cấp luôn được phép chen vào.
            if (level < 3 && nowMs - lastAnyToneMs < 2_000L) return
        }

        lastToneMs[level] = nowMs
        lastAnyToneMs = nowMs

        try {
            val generator = toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).also {
                    toneGenerator = it
                }
            when (level) {
                1 -> generator.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
                2 -> generator.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                3 -> generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
                else -> generator.startTone(ToneGenerator.TONE_PROP_ACK, 400)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Không phát được âm báo", e)
        }
    }

    // ======================= ĐỘ NHẠY =======================

    /** Hệ số thời gian xác nhận: càng nhạy càng báo sớm (0.6 nghĩa là chỉ cần 60% thời gian). */
    private fun sensTime(): Float = when (sensLevel) {
        0 -> 1.5f
        2 -> 0.6f
        else -> 1.0f
    }

    /** Hệ số ngưỡng góc đầu / hướng nhìn: càng nhạy càng báo với độ lệch nhỏ hơn. */
    private fun sensThr(): Float = when (sensLevel) {
        0 -> 1.15f
        2 -> 0.85f
        else -> 1.0f
    }

    /** Hệ số làm mượt SEI: càng nhạy chỉ số càng bám sát diễn biến tức thời. */
    private fun sensSmooth(): Float = when (sensLevel) {
        0 -> 0.7f
        2 -> 1.6f
        else -> 1.0f
    }

    // ======================= TOÁN HỌC HÌNH HỌC =======================

    /** Khoảng cách Euclid trong không gian pixel (tránh méo do ảnh không vuông). */
    private fun pixelDistance(
        lm: Landmarks,
        a: Int,
        b: Int,
        width: Int,
        height: Int
    ): Float {
        val dx = (lm.x(a) - lm.x(b)) * width
        val dy = (lm.y(a) - lm.y(b)) * height
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateEAR(
        lm: Landmarks,
        e: IntArray,
        width: Int,
        height: Int
    ): Float {
        val vertical1 = pixelDistance(lm, e[1], e[5], width, height)
        val vertical2 = pixelDistance(lm, e[2], e[4], width, height)
        val horizontal = pixelDistance(lm, e[0], e[3], width, height)

        if (horizontal < 0.001f) return 1f

        return (vertical1 + vertical2) / (2f * horizontal)
    }

    /**
     * Khoảng cách (cm), CHƯA nhân hệ số người dùng.
     * Gộp 2 nguồn: mống mắt (bền khi quay đầu) + khoảng cách hai khóe mắt
     * (chính xác về pixel nhưng co lại khi quay đầu nên giảm trọng số).
     */
    private fun estimateDistanceCm(
        lm: Landmarks,
        imgW: Int,
        imgH: Int,
        yawDevDeg: Float,
        eyesOpen: Boolean
    ): Float? {
        val f = focalPx()

        var dCorner: Float? = null
        var wCorner = 0f
        val cornerPx = pixelDistance(lm, LM_OUTER_RIGHT_EYE, LM_OUTER_LEFT_EYE, imgW, imgH)
        if (cornerPx >= 1f) {
            dCorner = EYE_OUTER_WIDTH_CM * f / cornerPx
            wCorner = 1f - clamp01((abs(yawDevDeg) - 8f) / 25f) * 0.85f
        }

        var dIris: Float? = null
        if (lm.count >= IRIS_LANDMARKS && eyesOpen) {
            val r = pixelDistance(lm, LM_R_IRIS_H1, LM_R_IRIS_H2, imgW, imgH)
            val l = pixelDistance(lm, LM_L_IRIS_H1, LM_L_IRIS_H2, imgW, imgH)
            val avg = (r + l) / 2f
            // Hai mắt phải cho kích thước gần nhau, nếu không thì landmark đang nhiễu.
            if (avg >= 3f && abs(r - l) / avg < 0.35f) {
                dIris = IRIS_DIAMETER_CM * f / avg
            }
        }

        return when {
            dIris != null && dCorner != null -> {
                val ratio = dCorner / dIris
                if (ratio < 0.7f || ratio > 1.4f) {
                    dIris                      // lệch quá nhiều: tin mống mắt
                } else {
                    (dIris * 1.0f + dCorner * wCorner) / (1.0f + wCorner)
                }
            }
            dIris != null -> dIris
            else -> dCorner
        }
    }

    private fun medianOf(window: ArrayDeque<Float>): Float {
        val s = window.sorted()
        return s[s.size / 2]
    }

    /**
     * Chiếu điểm p lên trục a->b trong không gian pixel.
     * Trả về 0 tại a, 1 tại b (dùng cho vị trí mống mắt trong hốc mắt).
     */
    private fun axisRatio(
        lm: Landmarks,
        p: Int,
        a: Int,
        b: Int,
        width: Int,
        height: Int
    ): Float {
        val abx = (lm.x(b) - lm.x(a)) * width
        val aby = (lm.y(b) - lm.y(a)) * height
        val apx = (lm.x(p) - lm.x(a)) * width
        val apy = (lm.y(p) - lm.y(a)) * height
        val len2 = abx * abx + aby * aby
        if (len2 < 0.000001f) return 0.5f
        return (apx * abx + apy * aby) / len2
    }

    /**
     * Góc Euler từ pháp tuyến mặt phẳng khuôn mặt (dùng cả toạ độ z của MediaPipe).
     * R = vector ngang (má trái-phải), U = vector dọc (cằm-trán), N = R x U hướng về camera.
     * Quy ước: yaw dương = quay sang trái của người dùng, pitch dương = cúi xuống.
     * Giá trị được dùng chủ yếu dưới dạng độ lệch so với đường cơ sở hiệu chuẩn.
     */
    private fun computeHeadPose(lm: Landmarks, width: Int, height: Int) {
        val wf = width.toFloat()
        val hf = height.toFloat()

        val rx = (lm.x(LM_FACE_LEFT) - lm.x(LM_FACE_RIGHT)) * wf
        val ry = (lm.y(LM_FACE_LEFT) - lm.y(LM_FACE_RIGHT)) * hf
        val rz = (lm.z(LM_FACE_LEFT) - lm.z(LM_FACE_RIGHT)) * wf

        val ux = (lm.x(LM_FOREHEAD) - lm.x(LM_CHIN)) * wf
        val uy = (lm.y(LM_FOREHEAD) - lm.y(LM_CHIN)) * hf
        val uz = (lm.z(LM_FOREHEAD) - lm.z(LM_CHIN)) * wf

        val nx = ry * uz - rz * uy
        val ny = rz * ux - rx * uz
        val nz = rx * uy - ry * ux

        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 0.000001f) return

        val fx = nx / len
        val fy = ny / len
        val fz = nz / len

        poseYawAbs = atan2(fx, -fz) * RAD_TO_DEG
        posePitchAbs = atan2(fy, sqrt(fx * fx + fz * fz)) * RAD_TO_DEG
        poseRollAbs = atan2(ry, abs(rx).coerceAtLeast(0.0001f)) * RAD_TO_DEG
    }

    private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    // ======================= CHẤT LƯỢNG TÍN HIỆU =======================

    /** Độ sáng trung bình của kênh Y (lấy mẫu thưa, rất nhẹ). */
    private fun sampleLuma(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        lastIndex: Int
    ): Float {
        var sum = 0L
        var n = 0
        var row = 0

        while (row < height) {
            val base = row * rowStride
            var col = 0
            while (col < width) {
                val idx = (base + col * pixelStride).coerceAtMost(lastIndex)
                sum += (yBytes[idx].toInt() and 0xFF).toLong()
                n++
                col += LUMA_SAMPLE_STEP
            }
            row += LUMA_SAMPLE_STEP
        }

        return if (n > 0) sum.toFloat() / n.toFloat() else 128f
    }

    /**
     * Chất lượng tín hiệu 0-100. Cập nhật signalReliable (ánh sáng + kích thước mặt)
     * dùng để chặn các cảnh báo kém tin cậy, và signalNote (lý do chính).
     */
    private fun computeSignalQuality(
        faceWidthPx: Float,
        yawAbs: Float,
        pitchAbs: Float,
        irisOk: Boolean
    ): Int {
        var q = 100f
        var note = "Tốt"

        if (lumaMean < LUMA_VERY_LOW) {
            q -= 60f
            note = "Ánh sáng quá yếu"
        } else if (lumaMean < LUMA_LOW) {
            q -= 35f
            note = "Ánh sáng yếu"
        } else if (lumaMean > LUMA_HIGH) {
            q -= 20f
            note = "Ảnh quá sáng hoặc ngược sáng"
        }

        if (faceWidthPx < VERY_SMALL_FACE_PX) {
            q -= 50f
            note = "Khuôn mặt quá xa"
        } else if (faceWidthPx < MIN_FACE_PX) {
            q -= 30f
            if (note == "Tốt") note = "Khuôn mặt hơi xa"
        }

        signalReliable = q >= QUALITY_OK

        if (yawAbs > 35f || pitchAbs > 30f) {
            q -= 20f
            if (note == "Tốt") note = "Mặt quay nghiêng nhiều"
        }

        if (!irisOk) {
            q -= 10f
        }

        signalNote = note
        return q.coerceIn(0f, 100f).roundToInt()
    }

    // ======================= TƯ THẾ ĐẦU: CỬA SỔ TRƯỢT & BẤT THƯỜNG =======================

    private fun clearPoseState() {
        poseWindow.clear()
        jerkTimestamps.clear()
        lastJerkMs = 0L
    }

    private fun resetFilters() {
        yawFilter.reset()
        pitchFilter.reset()
        rollFilter.reset()
        gazeHFilter.reset()
        gazeVFilter.reset()
        distanceFilter.reset()
        distWindow.clear()
    }

    private fun updatePoseWindow(
        nowMs: Long,
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
        ear: Float
    ) {
        val last = poseWindow.lastOrNull()
        if (last != null) {
            val dt = nowMs - last.timeMs
            if (dt in 1L..500L) {
                val dMax = max(
                    abs(yawDeg - last.yawDeg),
                    max(abs(pitchDeg - last.pitchDeg), abs(rollDeg - last.rollDeg))
                )
                val speed = dMax * 1000f / dt.toFloat()
                if (speed > JERK_SPEED_DEG_PER_S && nowMs - lastJerkMs >= JERK_MIN_GAP_MS) {
                    jerkTimestamps.add(nowMs)
                    lastJerkMs = nowMs
                }
            }
        }

        poseWindow.addLast(PoseSample(nowMs, yawDeg, pitchDeg, rollDeg, ear))

        while (poseWindow.isNotEmpty() &&
            nowMs - poseWindow.first().timeMs > POSE_WINDOW_MS
        ) {
            poseWindow.removeFirst()
        }

        while (jerkTimestamps.isNotEmpty() &&
            nowMs - jerkTimestamps[0] > JERK_WINDOW_MS
        ) {
            jerkTimestamps.removeAt(0)
        }
    }

    /**
     * Độ lệch chuẩn của tư thế đầu (độ) và của EAR trên cửa sổ trượt.
     * Trả về null nếu chưa đủ dữ liệu.
     */
    private fun poseWindowStats(): Pair<Float, Float>? {
        val n = poseWindow.size
        if (n < FREEZE_MIN_SAMPLES) return null

        val span = poseWindow.last().timeMs - poseWindow.first().timeMs
        if (span < POSE_WINDOW_MS * 9L / 10L) return null

        var sumYaw = 0f
        var sumPitch = 0f
        var sumRoll = 0f
        var sumEar = 0f
        for (s in poseWindow) {
            sumYaw += s.yawDeg
            sumPitch += s.pitchDeg
            sumRoll += s.rollDeg
            sumEar += s.ear
        }

        val inv = 1f / n.toFloat()
        val meanYaw = sumYaw * inv
        val meanPitch = sumPitch * inv
        val meanRoll = sumRoll * inv
        val meanEar = sumEar * inv

        var varYaw = 0f
        var varPitch = 0f
        var varRoll = 0f
        var varEar = 0f
        for (s in poseWindow) {
            val dy = s.yawDeg - meanYaw
            val dp = s.pitchDeg - meanPitch
            val dr = s.rollDeg - meanRoll
            val de = s.ear - meanEar
            varYaw += dy * dy
            varPitch += dp * dp
            varRoll += dr * dr
            varEar += de * de
        }

        val poseStd = max(
            sqrt(varYaw * inv),
            max(sqrt(varPitch * inv), sqrt(varRoll * inv))
        )
        val earStd = sqrt(varEar * inv)

        return poseStd to earStd
    }

    // ======================= ĐỘNG CƠ MẤT TẬP TRUNG TÍCH LŨY =======================

    private fun registerViolation(nowMs: Long, weight: Float) {
        violationEvents.addLast(ViolationEvent(nowMs, weight))
        violationsTotal++
    }

    private fun violationScore(nowMs: Long): Float {
        while (violationEvents.isNotEmpty() &&
            nowMs - violationEvents.first().timeMs > VIOLATION_WINDOW_MS
        ) {
            violationEvents.removeFirst()
        }

        var total = 0f
        for (e in violationEvents) {
            total += e.weight
        }
        return total
    }

    private fun yawnCountRecent(nowMs: Long): Int {
        while (yawnTimestamps.isNotEmpty() &&
            nowMs - yawnTimestamps[0] > YAWN_WINDOW_MS
        ) {
            yawnTimestamps.removeAt(0)
        }
        return yawnTimestamps.size
    }

    private fun pruneBlinks(nowMs: Long) {
        while (blinkTimestamps.isNotEmpty() &&
            nowMs - blinkTimestamps[0] > BPM_WINDOW_MS
        ) {
            blinkTimestamps.removeAt(0)
            if (blinkDurations.isNotEmpty()) {
                blinkDurations.removeAt(0)
            }
        }
    }

    private fun averageBlinkMs(): Float? {
        if (blinkDurations.isEmpty()) return null

        var total = 0L
        for (d in blinkDurations) {
            total += d
        }
        return total.toFloat() / blinkDurations.size.toFloat()
    }

    // ======================= HIỆU CHUẨN & SEI 2.0 =======================

    private fun resetCalibration() {
        endHabitSession()
        eyeEngine.reset()
        lastSeiValue = -1
        lowVsPersonalStartMs = 0L
        calibrated = false
        calFrames = 0
        calWaitStartMs = 0L
        calRejected = 0
        calSumEar1 = 0f
        calSumEar2 = 0f
        calSumYawDeg = 0f
        calSumPitchDeg = 0f
        calSumGazeH = 0f
        calSumGazeV = 0f
        calSumBfr = 0f
        baselineYawDeg = 0f
        baselinePitchDeg = 0f
        baselineGazeH = 0.5f
        baselineGazeV = 0.5f
        baselineBfr = 0.5f
        eyesClosed = false
        closedStartMs = 0L
        lastBlinkMs = 0L
        blinkTimestamps.clear()
        blinkDurations.clear()
        monitoringStartMs = 0L
        smoothedDistanceCm = null
        distanceAlertActive = false
        prevNoseX = Float.NaN
        prevNoseY = Float.NaN
        jitterEma = 0f
        lookAwayStartMs = 0L
        driftStartMs = 0L
        furrowStartMs = 0L
        yawnStartMs = 0L
        yawnCounted = false
        yawnTimestamps.clear()
        violationEvents.clear()
        prevHeadAway = false
        prevDrift = false
        prevDrowsy = false
        prevTooClose = false
        prevConfused = false
        chronicActive = false
        chronicLevelValue = 0
        violationsTotal = 0
        chronicEpisodes = 0
        yawnTotal = 0
        minSeiValue = Int.MAX_VALUE
        fatigueEma = 0f
        workStartMs = 0L
        faceLostSinceMs = 0L
        seiInitialized = false
        seiEma = 0f
        seiSum = 0L
        seiCount = 0L
        clearPoseState()
        resetFilters()
        perclos.reset()
        inattention.reset()
    }

    private fun finishCalibration(nowMs: Long) {
        val n = calFrames.toFloat()
        baselineEar1 = calSumEar1 / n
        baselineEar2 = calSumEar2 / n

        // Mở phiên học thói quen và (nếu đã có hồ sơ) trộn EAR cơ sở với giá trị đã học.
        val earBefore = (baselineEar1 + baselineEar2) / 2f
        beginHabitSession(nowMs)
        val earAfter = (baselineEar1 + baselineEar2) / 2f
        eyeEngine.finishCalibration(if (earBefore > 0f) earAfter / earBefore else 1f)

        closeThreshold1 = baselineEar1 * CLOSE_RATIO
        closeThreshold2 = baselineEar2 * CLOSE_RATIO

        baselineYawDeg = calSumYawDeg / n
        baselinePitchDeg = calSumPitchDeg / n
        baselineGazeH = calSumGazeH / n
        baselineGazeV = calSumGazeV / n
        baselineBfr = (calSumBfr / n).coerceAtLeast(0.01f)

        calibrated = true
        monitoringStartMs = nowMs
        workStartMs = nowMs
        blinkTimestamps.clear()
        blinkDurations.clear()
        eyesClosed = false
        clearPoseState()
        resetFilters()
        perclos.reset()
        inattention.reset()

        Log.d(
            TAG,
            "Hiệu chuẩn xong: baseline1=$baselineEar1 baseline2=$baselineEar2 " +
                    "close1=$closeThreshold1 close2=$closeThreshold2 " +
                    "yaw0=$baselineYawDeg pitch0=$baselinePitchDeg " +
                    "gazeH=$baselineGazeH gazeV=$baselineGazeV bfr=$baselineBfr"
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
        // Vùng lý tưởng bám theo ngưỡng hiện hành (mặc định 12..25, cá nhân hóa khi đã học).
        val lo = thrBlinkLow + 4f
        val hi = thrBlinkHigh - 5f
        return when {
            bpm in lo..hi -> 100f
            bpm < lo -> 40f + (bpm / lo) * 60f
            else -> max(30f, 100f - ((bpm - hi) / 7f) * 30f)
        }
    }

    private fun headScore(
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
        jitter: Float
    ): Float {
        val orientation = 100f -
                clamp01(abs(yawDeg) / 25f) * 50f -
                clamp01(abs(pitchDeg) / 25f) * 30f -
                clamp01(abs(rollDeg) / 25f) * 20f
        val stability = 100f * (1f - clamp01(jitter / 0.05f))
        return (orientation * 0.7f + stability * 0.3f).coerceIn(0f, 100f)
    }

    private fun gazeScore(
        available: Boolean,
        eyesOpen: Boolean,
        offH: Float,
        offV: Float,
        driftLong: Boolean
    ): Float {
        if (!available || !eyesOpen) return 85f

        val base = 100f -
                clamp01(abs(offH) / (thrGazeH * 1.5f)) * 60f -
                clamp01(abs(offV) / (thrGazeV * 1.5f)) * 40f

        return if (driftLong) min(base, 30f) else base.coerceIn(0f, 100f)
    }

    private fun expressionScore(
        yawnConfirmed: Boolean,
        yawnsRecent: Int,
        bfr: Float,
        baseline: Float,
        confused: Boolean
    ): Float {
        val mouthPenalty = if (yawnConfirmed) {
            60f
        } else {
            min(30f, yawnsRecent * 10f)
        }

        val deficit = 1f - bfr / baseline
        val furrowMax = if (confused) 30f else 20f
        val furrowPenalty = clamp01((deficit - 0.05f) / 0.20f) * furrowMax

        return (100f - mouthPenalty - furrowPenalty).coerceIn(0f, 100f)
    }

    /** Chỉ số mệt mỏi tổng hợp 0-100 (PERCLOS, thời lượng chớp, ngáp, BPM, nhắm lâu). */
    private fun updateFatigue(
        perclosValue: Float,
        avgBlink: Float?,
        yawnsRecent: Int,
        bpmValue: Float,
        bpmReliable: Boolean,
        drowsyNow: Boolean
    ): Int {
        var raw = clamp01((perclosValue - 3f) / 17f) * 35f

        if (avgBlink != null) {
            raw += clamp01((avgBlink - 150f) / 250f) * 20f
        }

        raw += min(25f, yawnsRecent * 8f)

        if (bpmReliable && (bpmValue < thrBlinkLow || bpmValue > thrBlinkHigh)) {
            raw += 10f
        }

        if (drowsyNow) {
            raw += 10f
        }

        val target = min(100f, raw)
        fatigueEma += (target - fatigueEma) * frameAlpha(FATIGUE_SMOOTHING)
        return fatigueEma.roundToInt().coerceIn(0, 100)
    }

    private fun updateSei(raw: Float): Int {
        val target = raw.coerceIn(0f, 100f)
        seiEma = if (!seiInitialized) {
            seiInitialized = true
            target
        } else {
            seiEma + (target - seiEma) * frameAlpha(min(0.9f, SEI_SMOOTHING * sensSmooth()))
        }

        val value = seiEma.roundToInt().coerceIn(0, 100)
        seiSum += value.toLong()
        seiCount++
        if (value < minSeiValue) {
            minSeiValue = value
        }
        return value
    }

    private fun averageSei(): Int? {
        return if (seiCount > 0L) {
            (seiSum.toFloat() / seiCount.toFloat()).roundToInt()
        } else {
            null
        }
    }

    private fun minSei(): Int? {
        return if (minSeiValue == Int.MAX_VALUE) null else minSeiValue
    }

    private fun sessionSeconds(nowMs: Long): Long {
        return if (calibrated && monitoringStartMs != 0L) {
            ((nowMs - monitoringStartMs) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }
    }

    /**
     * Hệ số làm mượt theo thời gian thực. perFrameAt30fps là hệ số quen thuộc khi máy chạy 30 FPS;
     * hàm này trả về hệ số tương đương cho khoảng thời gian thực tế giữa hai khung, để SEI và chỉ số
     * mệt mỏi phản ứng cùng tốc độ dù máy chạy 12 hay 30 khung/giây.
     */
    private fun frameAlpha(perFrameAt30fps: Float): Float {
        val a = perFrameAt30fps.coerceIn(0.001f, 0.99f)
        val tauMs = -33.3f / ln(1f - a)
        return (1f - exp(-frameDtMs / tauMs)).coerceIn(0f, 1f)
    }

    private fun updateFps(nowMs: Long) {
        if (prevFrameTimeMs != 0L) {
            val dt = nowMs - prevFrameTimeMs
            if (dt > 0L) {
                frameDtMs = dt.toFloat().coerceIn(5f, 250f)
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

    /** Xóa các bộ đếm tức thời khi mất khuôn mặt (tránh sự kiện giả khi quay lại). */
    private fun clearTransientState() {
        eyesClosed = false
        prevNoseX = Float.NaN
        prevNoseY = Float.NaN
        jitterEma = 0f
        lookAwayStartMs = 0L
        driftStartMs = 0L
        furrowStartMs = 0L
        yawnStartMs = 0L
        yawnCounted = false
        prevHeadAway = false
        prevDrift = false
        prevDrowsy = false
        prevConfused = false
        smoothedDistanceCm = null
        calWaitStartMs = 0L
        clearPoseState()
        resetFilters()
        perclos.pause()
        inattention.pause()
        eyeEngine.pause()
    }

    private fun noFaceSnapshot(
        imgW: Int,
        imgH: Int,
        nowMs: Long,
        processingMs: Float
    ): HudSnapshot {
        clearTransientState()
        if (habitLearnEnabled) habitSession?.tick(nowMs, false, false)

        // Rời khỏi màn hình đủ lâu được tính là đã nghỉ giải lao.
        if (faceLostSinceMs == 0L) {
            faceLostSinceMs = nowMs
        }
        if (calibrated && nowMs - faceLostSinceMs >= BREAK_DETECT_MS) {
            workStartMs = 0L
        }

        var sei: Int? = null
        if (calibrated && seiInitialized) {
            seiEma += (0f - seiEma) * frameAlpha(SEI_NO_FACE_DECAY)
            sei = seiEma.roundToInt().coerceIn(0, 100)
        }

        return HudSnapshot(
            faceDetected = false,
            points = EMPTY_LANDMARKS,
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
            distanceScale = distanceScale,
            yawDeg = null,
            pitchDeg = null,
            rollDeg = null,
            gazeStatus = "--",
            expressionStatus = "--",
            bfr = null,
            mar = null,
            yawnCount = yawnCountRecent(nowMs),
            chronicLevel = chronicLevelValue,
            inattentionPercent = null,
            violationScore = violationScore(nowMs),
            anomaly = null,
            avgBlinkMs = averageBlinkMs(),
            fatigueIndex = if (calibrated) fatigueEma.roundToInt().coerceIn(0, 100) else null,
            signalQuality = 0,
            signalNote = "Không thấy khuôn mặt",
            minSei = minSei(),
            violationsTotal = violationsTotal,
            chronicEpisodes = chronicEpisodes,
            yawnTotal = yawnTotal,
            habitStatus = habitStatus,
            habitDetail = habitDetail,
            habitHistory = habitHistory,
            alertCode = 0,
            eyeDetail = "--"
        )
    }

    private fun buildSnapshot(
        lm: Landmarks,
        imgW: Int,
        imgH: Int,
        nowMs: Long,
        processingMs: Float,
        blend: FloatArray? = null
    ): HudSnapshot {
        processHabitRequests()

        if (recalibrationRequested) {
            recalibrationRequested = false
            resetCalibration()
        }

        updateFps(nowMs)
        maintainHabit(nowMs)

        if (lm.count < MIN_LANDMARKS) {
            return noFaceSnapshot(imgW, imgH, nowMs, processingMs)
        }

        // Khuôn mặt đã quay lại.
        faceLostSinceMs = 0L
        if (calibrated && workStartMs == 0L) {
            workStartMs = nowMs
        }

        // ---- EAR ----
        val ear1 = calculateEAR(lm, RIGHT_EYE, imgW, imgH)
        val ear2 = calculateEAR(lm, LEFT_EYE, imgW, imgH)

        // ---- Tư thế đầu 3D (đưa lên trước để hiệu chỉnh khoảng cách theo góc quay) ----
        computeHeadPose(lm, imgW, imgH)
        val yawRawDev = poseYawAbs - baselineYawDeg
        val pitchRawDev = posePitchAbs - baselinePitchDeg

        // ---- Khoảng cách (tiêu cự thật + mống mắt + khóe mắt, trung vị rồi One Euro) ----
        val faceWidthPx = pixelDistance(lm, LM_OUTER_RIGHT_EYE, LM_OUTER_LEFT_EYE, imgW, imgH)
        val eyesOpenForDist = ear1 > 0.16f && ear2 > 0.16f
        val rawUnscaled = estimateDistanceCm(
            lm,
            imgW,
            imgH,
            if (calibrated) yawRawDev else 0f,
            eyesOpenForDist
        )

        // Hiệu chuẩn: lấy trung vị của nhiều khung thay vì 1 khung nhiễu.
        if (rawUnscaled != null && distanceCalibrationRequested) {
            distCalSamples.add(rawUnscaled)
            if (distCalSamples.size >= DIST_CAL_SAMPLES) {
                val sorted = distCalSamples.sorted()
                val med = sorted[sorted.size / 2]
                val newScale = (REFERENCE_DISTANCE_CM / med).coerceIn(0.3f, 3f)
                distanceScale = newScale
                distanceCalibrationRequested = false
                distCalSamples.clear()
                distWindow.clear()
                smoothedDistanceCm = null
                distanceFilter.reset()
                prefs.edit().putFloat(KEY_DISTANCE_SCALE, newScale).apply()
                Log.d(TAG, "Hiệu chuẩn khoảng cách: hệ số = $newScale")
            }
        } else if (!distanceCalibrationRequested) {
            distCalSamples.clear()
        }

        if (rawUnscaled != null) {
            distWindow.addLast(rawUnscaled * distanceScale)
            if (distWindow.size > DIST_MEDIAN_SIZE) distWindow.removeFirst()
            smoothedDistanceCm = distanceFilter.filter(medianOf(distWindow), nowMs)
        }
        val distance = smoothedDistanceCm

        if (distance != null) {
            distanceAlertActive = if (distanceAlertActive) {
                distance < DISTANCE_RECOVER_CM
            } else {
                distance < MIN_SAFE_DISTANCE_CM
            }
        }

        // ---- Góc tư thế đã lọc cho điểm số ----
        val yawDeg = yawFilter.filter(yawRawDev, nowMs)
        val pitchDeg = pitchFilter.filter(pitchRawDev, nowMs)
        val rollDeg = rollFilter.filter(poseRollAbs, nowMs)

        val faceWidthNorm = abs(lm.x(LM_FACE_LEFT) - lm.x(LM_FACE_RIGHT)).coerceAtLeast(0.0001f)
        val faceHeightNorm = abs(lm.y(LM_CHIN) - lm.y(LM_FOREHEAD)).coerceAtLeast(0.0001f)
        val noseX = lm.x(LM_NOSE_TIP)
        val noseY = lm.y(LM_NOSE_TIP)

        if (!prevNoseX.isNaN()) {
            val mx = (noseX - prevNoseX) / faceWidthNorm
            val my = (noseY - prevNoseY) / faceHeightNorm
            val movement = sqrt(mx * mx + my * my)
            jitterEma = jitterEma * 0.8f + movement * 0.2f
        }
        prevNoseX = noseX
        prevNoseY = noseY

        // ---- Hướng nhìn thô từ mống mắt (468-477), lọc One Euro ----
        val irisAvailable = lm.count >= IRIS_LANDMARKS
        var gazeHRaw = 0.5f
        var gazeVRaw = 0.5f
        if (irisAvailable) {
            val hRight = axisRatio(lm, LM_RIGHT_IRIS, LM_R_EYE_OUTER, LM_R_EYE_INNER, imgW, imgH)
            val hLeft = axisRatio(lm, LM_LEFT_IRIS, LM_L_EYE_INNER, LM_L_EYE_OUTER, imgW, imgH)
            val vRight = axisRatio(lm, LM_RIGHT_IRIS, LM_R_LID_UP, LM_R_LID_LOW, imgW, imgH)
            val vLeft = axisRatio(lm, LM_LEFT_IRIS, LM_L_LID_UP, LM_L_LID_LOW, imgW, imgH)
            gazeHRaw = (hRight + hLeft) / 2f
            gazeVRaw = (vRight + vLeft) / 2f
        }
        val gazeH = gazeHFilter.filter(gazeHRaw, nowMs)
        val gazeV = gazeVFilter.filter(gazeVRaw, nowMs)

        // ---- Vi biểu cảm thô: BFR và MAR ----
        val bfr = pixelDistance(lm, LM_BROW_INNER_R, LM_BROW_INNER_L, imgW, imgH) /
                faceWidthPx.coerceAtLeast(1f)

        val mouthWidthPx = pixelDistance(lm, LM_MOUTH_R, LM_MOUTH_L, imgW, imgH)
        val mar = if (mouthWidthPx < 1f) {
            0f
        } else {
            pixelDistance(lm, LM_MOUTH_UP, LM_MOUTH_LOW, imgW, imgH) / mouthWidthPx
        }

        // ---- Chất lượng tín hiệu ----
        val signalQuality = computeSignalQuality(
            faceWidthPx,
            if (calibrated) abs(yawDeg) else 0f,
            if (calibrated) abs(pitchDeg) else 0f,
            irisAvailable
        )
        val reliable = signalReliable

        var blinkDetected = false
        var currentEyeStatus: String
        var bpm: Float? = null
        var sei: Int? = null
        var perclosOut: Float? = null
        var alert: String? = null
        var anomaly: String? = null
        var gazeStatus = "Đang hiệu chuẩn"
        var expressionStatus = "Đang hiệu chuẩn"
        var inattentionPct: Float? = null
        var fatigueOut: Int? = null
        var alertCode = 0
        var eyeDetail = "Đang hiệu chuẩn"

        if (!calibrated) {
            // ---- Pha hiệu chuẩn: chỉ nhận khung hợp lệ và tín hiệu đủ tốt ----
            if (calWaitStartMs == 0L) {
                calWaitStartMs = nowMs
            }
            val relaxed = calFrames < CAL_RELAX_MIN_FRAMES &&
                    nowMs - calWaitStartMs >= CAL_RELAX_AFTER_MS
            val minEar = if (relaxed) CAL_RELAXED_MIN_EAR else CALIBRATION_MIN_EAR

            // Đầu phải đứng yên tương đối so với các khung đã nhận, để đường cơ sở (tư thế, hướng nhìn)
            // không bị lệch khi học sinh vừa quay đi hoặc đang cúi trong lúc hiệu chuẩn.
            val poseStable = calFrames < 8 ||
                    (abs(poseYawAbs - calSumYawDeg / calFrames) < 10f &&
                            abs(posePitchAbs - calSumPitchDeg / calFrames) < 10f)

            if (reliable && ear1 > minEar && ear2 > minEar && poseStable) {
                calRejected = 0
                calSumEar1 += ear1
                calSumEar2 += ear2
                calSumYawDeg += poseYawAbs
                calSumPitchDeg += posePitchAbs
                calSumGazeH += gazeH
                calSumGazeV += gazeV
                calSumBfr += bfr
                eyeEngine.addCalibrationSample(lm, imgW, imgH, blend)
                calFrames++

                if (calFrames >= CALIBRATION_FRAMES) {
                    finishCalibration(nowMs)
                }
            } else if (reliable) {
                // Bị loại liên tục khoảng 3 giây (đổi tư thế ngồi): bỏ dữ liệu cũ và hiệu chuẩn lại từ đầu.
                calRejected++
                if (calRejected > 90) {
                    calRejected = 0
                    calFrames = 0
                    calSumEar1 = 0f
                    calSumEar2 = 0f
                    calSumYawDeg = 0f
                    calSumPitchDeg = 0f
                    calSumGazeH = 0f
                    calSumGazeV = 0f
                    calSumBfr = 0f
                    eyeEngine.reset()
                }
            }
            currentEyeStatus = "Đang hiệu chuẩn"
        } else {
            val sT = sensTime()
            val sH = sensThr()
            eyeEngine.setSensitivity(sensLevel)

            // ---- Phân tích mắt nâng cấp: hợp nhất hình học 5 cặp mí + blendshape, bù góc nghiêng/cúi ----
            val poseOkForEyes = reliable &&
                    abs(yawDeg) <= EYE_MAX_YAW_DEG && abs(pitchDeg) <= EYE_MAX_PITCH_DEG
            val eye = eyeEngine.update(
                lm, imgW, imgH, nowMs,
                yawDeg, pitchDeg, gazeV - baselineGazeV,
                blend, poseOkForEyes
            )

            eyesClosed = eye.closed
            if (eye.closed) closedStartMs = eye.closedStartMs
            if (eye.blinkDurationMs > 0L) {
                blinkDetected = true
                blinkTimestamps.add(nowMs)
                blinkDurations.add(eye.blinkDurationMs)
                lastBlinkMs = nowMs
            }

            eyeDetail = if (!eye.valid) {
                "Góc nghiêng lớn hoặc tín hiệu kém: tạm bỏ qua phân tích mắt"
            } else {
                String.format(
                    Locale.US,
                    "độ mở %.2f | nhiễu %.2f | nheo %.0f%% | lệch 2 mắt %.0f%% | %s%s",
                    eye.openness,
                    eye.sigma,
                    eye.squintPct,
                    eye.asymPct,
                    if (eye.usingBlendshapes) "AI blendshape + hình học" else "hình học (chưa bật blendshape)",
                    if (eye.noisy) " | kính/chói gây nhiễu" else ""
                )
            }

            // ---- PERCLOS (chỉ tính khi phân tích mắt hợp lệ) ----
            if (eye.valid) perclos.record(nowMs, eyesClosed) else perclos.pause()
            val perclosValue = perclos.percent(nowMs)
            perclosOut = perclosValue

            // ---- Cửa sổ trượt 60 giây (BPM) ----
            pruneBlinks(nowMs)

            val elapsed = nowMs - monitoringStartMs
            val windowMs = elapsed.coerceIn(BPM_MIN_WINDOW_MS, BPM_WINDOW_MS)
            val bpmValue = blinkTimestamps.size * 60_000f / windowMs.toFloat()
            bpm = bpmValue
            val bpmReliable = elapsed >= BPM_RELIABLE_AFTER_MS

            val closedDuration = if (eyesClosed) nowMs - closedStartMs else 0L
            val drowsyNow = eyesClosed && closedDuration >= (DROWSY_CLOSURE_MS * sT).toLong()

            currentEyeStatus = when {
                drowsyNow -> "Nhắm lâu - có thể buồn ngủ"
                eyesClosed -> "Đang nhắm"
                !eye.valid -> "Tạm bỏ qua (góc nghiêng lớn hoặc tín hiệu kém)"
                eye.squintAlert -> "Mắt khép hoặc nheo một phần kéo dài"
                else -> "Đang mở hoặc chuyển trạng thái"
            }

            // ---- Đầu quay lệch khỏi màn hình ----
            val headAwayNow =
                abs(yawDeg) > thrLookYaw * sH || abs(pitchDeg) > thrLookPitch * sH
            if (headAwayNow) {
                if (lookAwayStartMs == 0L) {
                    lookAwayStartMs = nowMs
                }
            } else {
                lookAwayStartMs = 0L
            }
            val lookAwayLong =
                lookAwayStartMs != 0L && nowMs - lookAwayStartMs >= (LOOK_AWAY_MS * sT).toLong()

            // ---- Cửa sổ trượt tư thế (góc thô): đóng băng / giật bất thường ----
            updatePoseWindow(nowMs, yawRawDev, pitchRawDev, poseRollAbs, (ear1 + ear2) / 2f)
            val stats = poseWindowStats()
            val noBlinkMs = nowMs - max(lastBlinkMs, monitoringStartMs)
            val frozen = reliable && stats != null &&
                    stats.first < FREEZE_POSE_STD_DEG &&
                    stats.second < FREEZE_EAR_STD &&
                    noBlinkMs >= FREEZE_NO_BLINK_MS
            val jerky = jerkTimestamps.size >= JERK_COUNT_ALERT

            anomaly = when {
                frozen -> "⚠ Tư thế bất động bất thường, không chớp mắt (nghi ảnh in / hình tĩnh)."
                jerky -> "⚠ Chuyển động đầu giật bất thường."
                else -> null
            }

            // ---- Hướng nhìn và Visual Drift ----
            val eyesOpenForGaze = eye.valid && eye.openness >= 0.75f
            val offH = gazeH - baselineGazeH
            val offV = gazeV - baselineGazeV
            val driftNow = irisAvailable && reliable && eyesOpenForGaze &&
                    (abs(offH) > thrGazeH * sH || abs(offV) > thrGazeV * sH)

            if (driftNow) {
                if (driftStartMs == 0L) {
                    driftStartMs = nowMs
                }
            } else {
                driftStartMs = 0L
            }
            val driftMs = if (driftStartMs == 0L) 0L else nowMs - driftStartMs
            val driftLong = driftMs >= (VISUAL_DRIFT_MS * sT).toLong()

            val gazeDir = if (abs(offH) / thrGazeH >= abs(offV) / thrGazeV) {
                if (offH > 0f) "trái" else "phải"
            } else {
                if (offV > 0f) "xuống" else "lên"
            }

            gazeStatus = when {
                !irisAvailable -> "Không có dữ liệu mống mắt"
                !reliable -> "Tín hiệu kém, tạm bỏ qua"
                !eyesOpenForGaze -> "Mắt đang nhắm"
                driftLong -> String.format(
                    Locale.US,
                    "Visual Drift: lệch sang %s %.1f giây",
                    gazeDir,
                    driftMs / 1000f
                )
                driftNow -> "Đang liếc sang $gazeDir"
                else -> "Nhìn vào màn hình"
            }

            // ---- Vi biểu cảm: nhíu mày (BFR) và ngáp (MAR) ----
            val furrowNow = abs(yawDeg) < HEAD_FRONTAL_YAW_DEG &&
                    bfr < baselineBfr * BFR_FURROW_RATIO
            if (furrowNow) {
                if (furrowStartMs == 0L) {
                    furrowStartMs = nowMs
                }
            } else {
                furrowStartMs = 0L
            }
            val confusedSustained =
                furrowStartMs != 0L && nowMs - furrowStartMs >= (BFR_SUSTAIN_MS * sT).toLong()

            // Miệng mở rộng (blendshape jawOpen) cũng tính là ngáp: nhận ra cử chỉ nhanh và chắc hơn hình học.
            val jawOpen = if (blend != null && blend.size > 4) blend[4] else 0f
            val yawnNow = mar > thrMar || jawOpen > 0.55f
            if (yawnNow) {
                if (yawnStartMs == 0L) {
                    yawnStartMs = nowMs
                    yawnCounted = false
                }
            } else {
                yawnStartMs = 0L
                yawnCounted = false
            }
            val yawnConfirmed = yawnNow && nowMs - yawnStartMs >= (YAWN_MIN_MS * sT).toLong()
            if (yawnConfirmed && !yawnCounted) {
                yawnCounted = true
                yawnTimestamps.add(nowMs)
                yawnTotal++
                registerViolation(nowMs, V_YAWN)
            }
            val yawnsRecent = yawnCountRecent(nowMs)

            expressionStatus = when {
                yawnConfirmed -> "Ngáp (dấu hiệu mệt mỏi)"
                confusedSustained -> "Nhíu mày kéo dài (bối rối / căng thẳng)"
                furrowNow -> "Hơi nhíu mày"
                else -> "Bình thường"
            }

            // ---- Ghi nhận vi phạm theo sườn lên (mỗi đợt tính một lần) ----
            if (lookAwayLong && !prevHeadAway) registerViolation(nowMs, V_HEAD_AWAY)
            prevHeadAway = lookAwayLong

            if (driftLong && !prevDrift) registerViolation(nowMs, V_VISUAL_DRIFT)
            prevDrift = driftLong

            if (drowsyNow && !prevDrowsy) registerViolation(nowMs, V_DROWSY)
            prevDrowsy = drowsyNow

            if (distanceAlertActive && !prevTooClose) registerViolation(nowMs, V_TOO_CLOSE)
            prevTooClose = distanceAlertActive

            if (confusedSustained && !prevConfused) registerViolation(nowMs, V_CONFUSED)
            prevConfused = confusedSustained

            // ---- Động cơ mất tập trung tích lũy ----
            val inattentiveNow = headAwayNow || driftNow || yawnConfirmed ||
                    (eyesClosed && closedDuration >= 500L)
            inattention.record(nowMs, inattentiveNow)
            val inattentionRatio = inattention.percent(nowMs)
            inattentionPct = inattentionRatio

            val vScore = violationScore(nowMs)

            val wasChronic = chronicActive
            chronicActive = if (chronicActive) {
                !(vScore < CHRONIC_OFF &&
                        inattentionRatio < INATTENTION_RATIO_ALERT * 0.75f)
            } else {
                vScore >= CHRONIC_ON || inattentionRatio >= INATTENTION_RATIO_ALERT
            }
            if (!wasChronic && chronicActive) {
                chronicEpisodes++
            }

            chronicLevelValue = when {
                chronicActive -> 2
                vScore >= CHRONIC_WATCH -> 1
                else -> 0
            }

            // ---- Chỉ số mệt mỏi tổng hợp ----
            fatigueOut = updateFatigue(
                perclosValue,
                averageBlinkMs(),
                yawnsRecent,
                bpmValue,
                bpmReliable,
                drowsyNow
            )

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

            // ---- Chỉ số SEI 2.0 ----
            val posture = headScore(yawDeg, pitchDeg, rollDeg, jitterEma)
            val dist = distanceScore(distance)
            val gaze = gazeScore(irisAvailable && reliable, eyesOpenForGaze, offH, offV, driftLong)
            val eyes = (
                    eyeScore(closedDuration, eyesClosed) -
                            clamp01((perclosValue - 5f) / 25f) * 50f -
                            clamp01(eye.squintPct / 60f) * 15f
                    ).coerceAtLeast(0f)
            val blinkRate = bpmScore(bpmValue, bpmReliable)
            val expression = expressionScore(
                yawnConfirmed,
                yawnsRecent,
                bfr,
                baselineBfr,
                confusedSustained
            )

            var raw = posture * W_POSTURE +
                    dist * W_DISTANCE +
                    gaze * W_GAZE +
                    eyes * W_EYES +
                    blinkRate * W_BPM +
                    expression * W_EXPRESSION

            if (distanceAlertActive) {
                raw -= SEI_TOO_CLOSE_PENALTY
            }

            when (chronicLevelValue) {
                2 -> raw -= SEI_CHRONIC_PENALTY
                1 -> raw -= SEI_WATCH_PENALTY
            }

            if (frozen) {
                raw = min(raw, SEI_ANOMALY_CAP)
            }

            val seiNow = updateSei(raw)
            sei = seiNow
            lastSeiValue = seiNow

            // ---- So với thói quen: SEI tụt thấp hơn mức thường ngày của chính học sinh ----
            val belowPersonal = reliable && !personalSeiMean.isNaN() &&
                    seiNow < personalSeiMean - LOW_VS_PERSONAL_K * personalSeiSpread
            if (belowPersonal) {
                if (lowVsPersonalStartMs == 0L) lowVsPersonalStartMs = nowMs
            } else {
                lowVsPersonalStartMs = 0L
            }
            val lowVsPersonal =
                lowVsPersonalStartMs != 0L && nowMs - lowVsPersonalStartMs >= LOW_VS_PERSONAL_MS

            // ---- Học thói quen: chỉ lấy khung hợp lệ, không bất thường, không trong đợt mất tập trung kéo dài ----
            val hs = habitSession
            if (hs != null && habitLearnEnabled) {
                val habitValid = reliable && !frozen && !jerky && chronicLevelValue < 2 &&
                        !drowsyNow && !eyesClosed
                hs.tick(nowMs, true, habitValid)
                if (habitValid) {
                    if (bpmReliable) hs.observe(HabitFeature.BPM, bpmValue)
                    averageBlinkMs()?.let { hs.observe(HabitFeature.BLINK_MS, it) }
                    if (distance != null) hs.observe(HabitFeature.DIST, distance)
                    hs.observe(HabitFeature.YAW, yawDeg)
                    hs.observe(HabitFeature.PITCH, pitchDeg)
                    if (irisAvailable && eyesOpenForGaze) {
                        hs.observe(HabitFeature.GAZE_H, offH)
                        hs.observe(HabitFeature.GAZE_V, offV)
                    }
                    hs.observe(HabitFeature.MAR, mar)
                    if (abs(yawDeg) < HEAD_FRONTAL_YAW_DEG) hs.observe(HabitFeature.BFR, bfr)
                    hs.observe(HabitFeature.PERCLOS, perclosValue)
                    hs.observe(HabitFeature.SEI, seiNow.toFloat())
                    hs.addQuality(signalQuality.toFloat())
                }
            }

            // ---- Cảnh báo ưu tiên (khoảng cách / mãn tính / bất thường có dòng riêng) ----
            alert = when {
                !reliable ->
                    "⚠ Tín hiệu kém ($signalNote) - kết quả có thể không chính xác."
                drowsyNow ->
                    "⚠ Nhắm mắt quá lâu - bạn có thể đang buồn ngủ!"
                perclosValue >= thrPerclos ->
                    "⚠ Dấu hiệu mệt mỏi (PERCLOS cao) - hãy nghỉ ngơi."
                driftLong ->
                    "⚠ Visual Drift: ánh mắt rời màn hình quá 3 giây."
                lookAwayLong ->
                    "⚠ Đầu bạn đang quay lệch khỏi màn hình."
                yawnConfirmed ->
                    "⚠ Bạn đang ngáp - dấu hiệu mệt mỏi."
                bpmReliable && bpmValue < thrBlinkLow ->
                    "⚠ Chớp mắt quá ít - hãy chớp mắt thường xuyên hơn."
                eye.squintAlert ->
                    "⚠ Mắt khép hoặc nheo một phần kéo dài - có thể chói, mỏi hoặc khó chịu. Hãy nghỉ mắt và chỉnh độ sáng."
                eye.asymAlert ->
                    "ℹ Hai mắt mở không đều trong thời gian dài - hãy nghỉ mắt; nếu hay lặp lại, hãy báo người lớn hoặc giáo viên."
                eye.incompleteAlert ->
                    "ℹ Chớp mắt không hoàn toàn - mắt dễ khô, hãy chớp mắt chậm và đầy đủ."
                bpmReliable && bpmValue > thrBlinkHigh ->
                    "ℹ Chớp mắt rất nhiều - mắt có thể đang khô hoặc bị kích ứng, hãy nghỉ mắt."
                breakReminder ->
                    "⏰ Đã 20 phút: hãy nhìn xa 6 m trong 20 giây (quy tắc 20-20-20)."
                confusedSustained ->
                    "ℹ Bạn có vẻ đang căng thẳng hoặc bối rối với nội dung."
                lowVsPersonal ->
                    "ℹ Mức tập trung đang thấp hơn thường ngày của bạn (thường khoảng ${personalSeiMean.roundToInt()})."
                eye.noisy ->
                    "ℹ Kính phản chiếu hoặc ánh sáng chói đang gây nhiễu - hãy nghiêng màn hình, giảm chói hoặc tăng ánh sáng đều."
                else -> null
            }

            // Loại cảnh báo đang hiện (0 = không có / không kêu), cùng thứ tự ưu tiên với chuỗi alert.
            alertCode = when {
                !reliable -> 0
                drowsyNow -> KIND_DROWSY
                perclosValue >= thrPerclos -> KIND_PERCLOS
                driftLong -> KIND_DRIFT
                lookAwayLong -> KIND_LOOK_AWAY
                yawnConfirmed -> KIND_YAWN
                bpmReliable && bpmValue < thrBlinkLow -> KIND_LOW_BLINK
                eye.squintAlert -> KIND_SQUINT
                eye.asymAlert -> KIND_ASYMMETRY
                eye.incompleteAlert -> KIND_INCOMPLETE_BLINK
                bpmReliable && bpmValue > thrBlinkHigh -> KIND_HIGH_BLINK
                breakReminder -> KIND_BREAK
                confusedSustained -> 0
                lowVsPersonal -> KIND_LOW_PERSONAL
                eye.noisy -> 0
                else -> 0
            }
        }

        frameCounter++
        if (frameCounter % LOG_EVERY_N_FRAMES == 0L) {
            Log.d(
                "BlinkDebug",
                "EAR1=$ear1, EAR2=$ear2, closed=$eyesClosed, " +
                        "dist=$distance, bpm=$bpm, perclos=$perclosOut, sei=$sei"
            )
            Log.d(
                "PoseDebug",
                "yaw=$yawDeg pitch=$pitchDeg roll=$rollDeg gaze=($gazeH,$gazeV) " +
                        "bfr=$bfr mar=$mar chronic=$chronicLevelValue anomaly=$anomaly " +
                        "quality=$signalQuality luma=$lumaMean"
            )
            Log.d(
                "DistDebug",
                "raw=$rawUnscaled scale=$distanceScale focalPx=${focalPx()} " +
                        "stream=${streamW}x$streamH smoothed=$distance"
            )
            Log.d(
                "FaceSpeed",
                "MediaPipe: ${"%.1f".format(Locale.US, processingMs)} ms, " +
                        "FPS: ${"%.1f".format(Locale.US, fpsEma)}"
            )
        }

        return HudSnapshot(
            faceDetected = true,
            points = lm,
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
            perclosPercent = perclosOut,
            alertMessage = alert,
            sessionSeconds = sessionSeconds(nowMs),
            averageSei = averageSei(),
            fps = fpsEma,
            distanceScale = distanceScale,
            yawDeg = yawDeg,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            gazeStatus = gazeStatus,
            expressionStatus = expressionStatus,
            bfr = bfr,
            mar = mar,
            yawnCount = yawnCountRecent(nowMs),
            chronicLevel = chronicLevelValue,
            inattentionPercent = inattentionPct,
            violationScore = violationScore(nowMs),
            anomaly = anomaly,
            avgBlinkMs = averageBlinkMs(),
            fatigueIndex = fatigueOut,
            signalQuality = signalQuality,
            signalNote = signalNote,
            minSei = minSei(),
            violationsTotal = violationsTotal,
            chronicEpisodes = chronicEpisodes,
            yawnTotal = yawnTotal,
            habitStatus = habitStatus,
            habitDetail = habitDetail,
            habitHistory = habitHistory,
            alertCode = alertCode,
            eyeDetail = eyeDetail
        )
    }

    /** Chạy trên UI thread: cập nhật các dòng chữ của khung thông số (đã giới hạn tần suất). */
    private fun applyHudText(s: HudSnapshot) {
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

        headPoseText = if (s.yawDeg != null && s.pitchDeg != null && s.rollDeg != null) {
            String.format(
                Locale.US,
                "Yaw %+.1f° (+ = trái) | Pitch %+.1f° (+ = cúi) | Roll %+.1f°",
                s.yawDeg,
                s.pitchDeg,
                s.rollDeg
            )
        } else {
            "--"
        }

        gazeText = s.gazeStatus
        expressionText = s.expressionStatus

        expressionMetricsText = if (s.bfr != null && s.mar != null) {
            String.format(
                Locale.US,
                "BFR %.2f | MAR %.2f | Ngáp (5 phút): %d",
                s.bfr,
                s.mar,
                s.yawnCount
            )
        } else {
            "--"
        }

        inattentionText = if (s.inattentionPercent != null) {
            String.format(
                Locale.US,
                "%.0f%% trong 60 giây | Vi phạm tích lũy: %.1f",
                s.inattentionPercent,
                s.violationScore
            )
        } else {
            "--"
        }

        fatigueText = if (s.fatigueIndex != null) {
            val blinkPart = s.avgBlinkMs?.let {
                String.format(Locale.US, " | Chớp TB %.0f ms", it)
            } ?: ""
            "${s.fatigueIndex} / 100 (${fatigueLabelOf(s.fatigueIndex)})$blinkPart"
        } else {
            "--"
        }

        signalText = if (s.faceDetected) {
            "${s.signalQuality} / 100 (${s.signalNote})"
        } else {
            "--"
        }

        chronicLevel = s.chronicLevel
        chronicText = when (s.chronicLevel) {
            2 -> "⛔ CẢNH BÁO MẤT TẬP TRUNG KÉO DÀI - hãy nghỉ ngơi hoặc tập trung lại."
            1 -> "⚠ Cần chú ý: các lần mất tập trung đang tăng lên."
            else -> ""
        }
        anomalyText = s.anomaly ?: ""
        habitText = s.habitStatus
        habitDetailText = s.habitDetail
        habitHistoryText = s.habitHistory
        eyeDetailText = s.eyeDetail

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

    /** Chạy trên UI thread: áp dụng ảnh chụp trạng thái lên giao diện. */
    private fun applySnapshot(s: HudSnapshot) {
        lastSnapshot = s

        faceCount = if (s.faceDetected) 1 else 0
        landmarkPoints = s.points
        frameWidth = s.frameWidth
        frameHeight = s.frameHeight

        if (s.blinkDetected) {
            blinkCount++
        }

        // Chữ chỉ cần làm mới ~8 lần/giây; vẽ lại toàn bộ khung thông số 30 lần/giây là lãng phí pin.
        val nowText = SystemClock.elapsedRealtime()
        if (nowText - lastUiTextMs >= UI_TEXT_REFRESH_MS) {
            lastUiTextMs = nowText
            applyHudText(s)
        }

        // Lịch sử SEI: mỗi giây một mẫu, giữ HISTORY_SIZE mẫu gần nhất.
        val nowUi = SystemClock.elapsedRealtime()
        val seiNow = s.sei
        val currentSecond = nowUi / 1000L
        if (seiNow != null && currentSecond != lastHistorySecond) {
            lastHistorySecond = currentSecond
            val old = seiHistory
            val trimmed = if (old.size >= HISTORY_SIZE) {
                old.drop(old.size - HISTORY_SIZE + 1)
            } else {
                old
            }
            seiHistory = trimmed + seiNow
        }

        // Âm báo khi vừa bước vào trạng thái nghiêm trọng.
        val chronicRising = s.chronicLevel == 2 && lastChronicLevelUi != 2
        val tooCloseRising = s.tooClose && !lastTooCloseUi
        // Đếm số lần xuất hiện của từng loại cảnh báo, đủ số lần mới kêu.
        val code = s.alertCode
        if (code > 0) {
            val isNew = nowUi - lastKindActiveMs[code] > ALERT_REARM_MS
            lastKindActiveMs[code] = nowUi
            if (isNew) {
                if (registerAlertOccurrence(code, nowUi)) {
                    playAlertSound(alertLevelOf(code), nowUi)
                }
            } else if (code == KIND_DROWSY) {
                // Vẫn đang nhắm mắt lâu: nhắc lại theo chu kỳ.
                playAlertSound(3, nowUi)
            }
        }
        if (chronicRising && registerAlertOccurrence(KIND_CHRONIC, nowUi)) {
            playAlertSound(3, nowUi)
        }
        if (tooCloseRising && registerAlertOccurrence(KIND_TOO_CLOSE, nowUi)) {
            playAlertSound(2, nowUi)
        }
        lastChronicLevelUi = s.chronicLevel
        lastTooCloseUi = s.tooClose
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
            val hint = if (s.faceDetected && s.signalQuality < QUALITY_OK) {
                " - ${s.signalNote}"
            } else {
                ""
            }
            "Đang hiệu chuẩn ${s.calibrationProgress}/$CALIBRATION_FRAMES - " +
                    "hãy nhìn thẳng, mở mắt tự nhiên$hint"
        }
    }

    /** Chuyển danh sách điểm mốc của MediaPipe sang mảng phẳng tái sử dụng bộ đệm. */
    private fun toLandmarks(
        list: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): Landmarks {
        val needed = list.size * 3
        // Mảng mới mỗi khung (UI thread đọc song song); chỉ một lần cấp phát phẳng thay cho ~1.400 đối tượng.
        val data = FloatArray(needed)
        for (i in list.indices) {
            val p = list[i]
            data[i * 3] = p.x()
            data[i * 3 + 1] = p.y()
            data[i * 3 + 2] = p.z()
        }
        return Landmarks(data, list.size)
    }

    /**
     * Lấy 5 hệ số blendshape: [nháy trái, nháy phải, nheo trái, nheo phải, mở hàm].
     * Trả về null nếu FaceLandmarker chưa bật setOutputFaceBlendshapes(true) -
     * khi đó phân tích mắt tự rơi về chế độ hình học.
     */
    private fun extractBlend(
        result: com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
    ): FloatArray? {
        return try {
            val all = result.faceBlendshapes()
            if (!all.isPresent) return null
            val list = all.get().firstOrNull() ?: return null

            var blinkL = -1f
            var blinkR = -1f
            var squintL = 0f
            var squintR = 0f
            var jawOpen = 0f
            for (c in list) {
                when (c.categoryName()) {
                    "eyeBlinkLeft" -> blinkL = c.score()
                    "eyeBlinkRight" -> blinkR = c.score()
                    "eyeSquintLeft" -> squintL = c.score()
                    "eyeSquintRight" -> squintR = c.score()
                    "jawOpen" -> jawOpen = c.score()
                }
            }
            if (blinkL < 0f || blinkR < 0f) null
            else floatArrayOf(blinkL, blinkR, squintL, squintR, jawOpen)
        } catch (e: Exception) {
            null
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

            val faceList = result.faceLandmarks().firstOrNull()
            val landmarks = if (faceList != null) {
                toLandmarks(faceList)
            } else {
                EMPTY_LANDMARKS
            }

            // Toàn bộ phép toán khoảng cách / EAR / tư thế / gaze / SEI chạy tại đây (cameraExecutor).
            val snapshot = buildSnapshot(
                lm = landmarks,
                imgW = imgW,
                imgH = imgH,
                nowMs = SystemClock.elapsedRealtime(),
                processingMs = processingMs,
                blend = extractBlend(result)
            )

            runOnUiThread {
                applySnapshot(snapshot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phân tích ảnh", e)

            runOnUiThread {
                status = "Lỗi nhận diện: ${e.message}"
                landmarkPoints = EMPTY_LANDMARKS
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

        // Ghi kích thước khung gốc để tính tiêu cự pixel.
        streamW = width
        streamH = height

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

        // Độ sáng trung bình để đánh giá chất lượng tín hiệu.
        lumaMean = sampleLuma(width, height, yRowStride, yPixelStride, yLast)

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

    override fun onStop() {
        // Lưu phiên đang học khi rời màn hình (chạy trên cameraExecutor để không tranh chấp dữ liệu).
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            try {
                cameraExecutor.execute { commitHabitSession() }
            } catch (e: Exception) {
                Log.w(TAG, "Không lưu được phiên khi dừng", e)
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        previewView = null

        cameraProvider?.unbindAll()
        cameraProvider = null

        if (::cameraExecutor.isInitialized) {
            try {
                cameraExecutor.execute { endHabitSession() }
            } catch (e: Exception) {
                Log.w(TAG, "Không chốt được phiên học thói quen", e)
            }
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

        try {
            toneGenerator?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Không giải phóng được ToneGenerator", e)
        }
        toneGenerator = null

        super.onDestroy()
    }
}

// =====================================================================
// HỒ SƠ THÓI QUEN TỪNG HỌC SINH (gộp chung vào một file)
// =====================================================================

/**
 * Cấu hình học thói quen. Muốn thử nhanh khi phát triển: hạ MIN_SESSION_MS xuống 20_000L
 * và MIN_TOTAL_MS xuống 60_000L.
 */
internal object HabitConfig {
    /** Một phiên chỉ được lưu khi có ít nhất ngần này thời gian "hợp lệ". */
    const val MIN_SESSION_MS = 3L * 60L * 1000L

    /** Tỉ lệ thời gian thấy khuôn mặt tối thiểu trong phiên. */
    const val MIN_FACE_RATIO = 0.70f

    /** Tỉ lệ khung hợp lệ / khung thấy mặt tối thiểu. */
    const val MIN_VALID_RATIO = 0.50f

    /** Chất lượng tín hiệu trung bình tối thiểu (0-100). */
    const val MIN_QUALITY = 60f

    /** SEI trung bình tối thiểu: thấp hơn nghĩa là phiên không phản ánh việc học bình thường. */
    const val MIN_SESSION_SEI = 40f

    /** Số phiên đạt chuẩn tối thiểu và tổng thời gian hợp lệ tối thiểu để coi là "đã có hồ sơ". */
    const val MIN_SESSIONS = 3
    const val MIN_TOTAL_MS = 15L * 60L * 1000L

    const val MAX_SESSIONS = 40
    const val MIN_FRAMES_PER_FEATURE = 30
    const val RECENCY_DECAY = 0.9
    const val MAX_WEIGHT_MINUTES = 30.0
}

/** Các đặc trưng được học riêng cho từng học sinh. */
internal object HabitFeature {
    const val BPM = 0
    const val BLINK_MS = 1
    const val DIST = 2
    const val YAW = 3
    const val PITCH = 4
    const val GAZE_H = 5
    const val GAZE_V = 6
    const val MAR = 7
    const val BFR = 8
    const val EAR = 9
    const val PERCLOS = 10
    const val SEI = 11
    const val COUNT = 12

    /** Giới hạn cắt (winsorize) từng quan sát để một khung nhiễu không làm lệch hồ sơ. */
    val LO = floatArrayOf(0f, 40f, 10f, -60f, -60f, -0.5f, -0.6f, 0f, 0.1f, 0f, 0f, 0f)
    val HI = floatArrayOf(60f, 1000f, 150f, 60f, 60f, 0.5f, 0.6f, 1.2f, 1.5f, 0.6f, 100f, 100f)

    /** Độ rộng tối thiểu để ngưỡng không bị "co" về 0 khi dữ liệu còn ít. */
    val MIN_SPREAD = floatArrayOf(2f, 20f, 3f, 2f, 2f, 0.02f, 0.03f, 0.02f, 0.01f, 0.01f, 1.5f, 3f)
}

/** Thống kê chạy (Welford): trung bình và độ lệch chuẩn mà không lưu từng mẫu. */
internal class RunningStat {
    var n = 0
        private set
    var mean = 0.0
        private set
    private var m2 = 0.0

    fun add(x: Double) {
        n++
        val d = x - mean
        mean += d / n
        m2 += d * (x - mean)
    }

    fun std(): Double = if (n > 1) sqrt(m2 / n) else 0.0
}

/** Tóm tắt một phiên học: trung bình, độ lệch chuẩn và số mẫu của từng đặc trưng. */
internal class SessionSummary(
    val id: Long,
    val validMs: Long,
    val faceRatio: Float,
    val validRatio: Float,
    val quality: Float,
    val mean: FloatArray,
    val std: FloatArray,
    val count: IntArray
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("validMs", validMs)
        o.put("fr", faceRatio.toDouble())
        o.put("vr", validRatio.toDouble())
        o.put("q", quality.toDouble())
        o.put("m", mean.toJsonArray())
        o.put("s", std.toJsonArray())
        val n = JSONArray()
        for (c in count) n.put(c)
        o.put("n", n)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SessionSummary? {
            return try {
                val c = HabitFeature.COUNT
                val m = o.getJSONArray("m")
                val s = o.getJSONArray("s")
                val n = o.getJSONArray("n")
                if (m.length() != c || s.length() != c || n.length() != c) return null

                SessionSummary(
                    id = o.getLong("id"),
                    validMs = o.getLong("validMs"),
                    faceRatio = o.getDouble("fr").toFloat(),
                    validRatio = o.getDouble("vr").toFloat(),
                    quality = o.getDouble("q").toFloat(),
                    mean = FloatArray(c) { m.getDouble(it).toFloat() },
                    std = FloatArray(c) { s.getDouble(it).toFloat() },
                    count = IntArray(c) { n.getInt(it) }
                )
            } catch (e: Exception) {
                Log.w("HabitProfile", "Bỏ qua phiên lỗi định dạng", e)
                null
            }
        }
    }
}

private fun FloatArray.toJsonArray(): JSONArray {
    val a = JSONArray()
    for (v in this) a.put(v.toDouble())
    return a
}

/**
 * Bộ tích lũy của phiên đang chạy (chỉ dùng trên cameraExecutor).
 * Mỗi khung hợp lệ gọi observe(); tick() cộng thời gian.
 */
internal class HabitSession(val id: Long) {
    private val stats = Array(HabitFeature.COUNT) { RunningStat() }
    private val quality = RunningStat()
    private var lastTickMs = 0L

    var activeMs = 0L
        private set
    var faceMs = 0L
        private set
    var validMs = 0L
        private set

    fun tick(nowMs: Long, faceVisible: Boolean, valid: Boolean) {
        val dt = if (lastTickMs == 0L) 0L else (nowMs - lastTickMs).coerceIn(0L, 200L)
        lastTickMs = nowMs
        activeMs += dt
        if (faceVisible) faceMs += dt
        if (valid) validMs += dt
    }

    fun observe(feature: Int, value: Float) {
        if (value.isNaN() || value.isInfinite()) return
        val v = value.coerceIn(HabitFeature.LO[feature], HabitFeature.HI[feature])
        stats[feature].add(v.toDouble())
    }

    fun addQuality(q: Float) {
        quality.add(q.toDouble())
    }

    fun toSummary(): SessionSummary {
        val c = HabitFeature.COUNT
        val mean = FloatArray(c)
        val std = FloatArray(c)
        val cnt = IntArray(c)
        for (f in 0 until c) {
            cnt[f] = stats[f].n
            mean[f] = stats[f].mean.toFloat()
            std[f] = stats[f].std().toFloat()
        }
        val faceRatio = if (activeMs > 0L) faceMs.toFloat() / activeMs.toFloat() else 0f
        val validRatio = if (faceMs > 0L) validMs.toFloat() / faceMs.toFloat() else 0f
        return SessionSummary(
            id = id,
            validMs = validMs,
            faceRatio = faceRatio,
            validRatio = validRatio,
            quality = quality.mean.toFloat(),
            mean = mean,
            std = std,
            count = cnt
        )
    }
}

/** Mức cơ sở đã học: giá trị điển hình + độ dao động tự nhiên của từng đặc trưng. */
internal class HabitBaseline(
    private val means: FloatArray,
    private val spreads: FloatArray,
    private val ok: BooleanArray
) {
    fun mean(f: Int): Float? = if (ok[f]) means[f] else null
    fun spread(f: Int): Float? = if (ok[f]) spreads[f] else null

    /** |mean| + k * spread, kẹp trong [lo, hi]. Trả về null nếu chưa có dữ liệu. */
    fun threshold(f: Int, k: Float, lo: Float, hi: Float): Float? {
        if (!ok[f]) return null
        return (abs(means[f]) + k * spreads[f]).coerceIn(lo, hi)
    }
}

/** Hồ sơ thói quen của một học sinh: danh sách các phiên đạt chuẩn. */
internal class HabitProfile(val id: String, var name: String) {
    val sessions = ArrayList<SessionSummary>()

    fun sessionCount(): Int = sessions.size

    fun totalValidMs(): Long {
        var t = 0L
        for (s in sessions) t += s.validMs
        return t
    }

    fun isReady(): Boolean =
        sessions.size >= HabitConfig.MIN_SESSIONS &&
                totalValidMs() >= HabitConfig.MIN_TOTAL_MS

    /** Độ tin cậy 0..1: càng nhiều phiên, ngưỡng cá nhân càng được ưu tiên hơn ngưỡng mặc định. */
    fun confidence(): Float {
        if (!isReady()) return 0f
        return ((sessions.size - 2) / 5f).coerceIn(0.2f, 1f)
    }

    /** Lý do phiên chưa đủ điều kiện để học (null nếu đạt). */
    fun ineligibleReason(s: SessionSummary): String? = when {
        s.validMs < HabitConfig.MIN_SESSION_MS ->
            "chưa đủ ${HabitConfig.MIN_SESSION_MS / 60000L} phút hợp lệ"
        s.faceRatio < HabitConfig.MIN_FACE_RATIO ->
            "ít khi thấy khuôn mặt"
        s.validRatio < HabitConfig.MIN_VALID_RATIO ->
            "tín hiệu hoặc tư thế không ổn định, hoặc mất tập trung kéo dài"
        s.quality < HabitConfig.MIN_QUALITY ->
            "chất lượng tín hiệu thấp (ánh sáng, khoảng cách)"
        s.count[HabitFeature.SEI] > 0 && s.mean[HabitFeature.SEI] < HabitConfig.MIN_SESSION_SEI ->
            "mức tập trung trung bình thấp, không đại diện cho thói quen học"
        else -> null
    }

    /**
     * Thêm hoặc cập nhật phiên (cùng id thì ghi đè, vì phiên được lưu định kỳ).
     * Phiên không đạt chuẩn sẽ bị gỡ nếu trước đó đã lưu.
     * Trả về true nếu hồ sơ có thay đổi.
     */
    fun commit(s: SessionSummary): Boolean {
        val pos = sessions.indexOfFirst { it.id == s.id }
        if (ineligibleReason(s) != null) {
            if (pos >= 0) {
                sessions.removeAt(pos)
                return true
            }
            return false
        }
        if (pos >= 0) sessions[pos] = s else sessions.add(s)
        while (sessions.size > HabitConfig.MAX_SESSIONS) sessions.removeAt(0)
        return true
    }

    /** Lịch sử các phiên gần nhất kèm xu hướng SEI (dùng cho màn hình và báo cáo). */
    fun historyText(maxLines: Int): String {
        if (sessions.isEmpty()) return "Chưa có phiên nào đạt chuẩn."

        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US)
        val sb = StringBuilder()
        val from = max(0, sessions.size - maxLines)
        for (i in sessions.size - 1 downTo from) {
            val s = sessions[i]
            sb.append(fmt.format(java.util.Date(s.id)))
            sb.append(" - ").append(s.validMs / 60000L).append(" phút hợp lệ")
            if (s.count[HabitFeature.SEI] > 0) {
                sb.append(", SEI TB ").append(s.mean[HabitFeature.SEI].roundToInt())
            }
            if (s.count[HabitFeature.BPM] > 0) {
                sb.append(", chớp ").append(s.mean[HabitFeature.BPM].roundToInt()).append("/phút")
            }
            sb.append('\n')
        }

        if (sessions.size >= 4) {
            val recent = avgSei(sessions.size - 3, sessions.size)
            val previous = avgSei(max(0, sessions.size - 6), sessions.size - 3)
            if (recent != null && previous != null) {
                val diff = recent - previous
                val word = when {
                    diff >= 5f -> "đang tăng"
                    diff <= -5f -> "đang giảm"
                    else -> "ổn định"
                }
                sb.append(String.format(Locale.US, "Xu hướng SEI: %s (%+.0f so với các phiên trước)", word, diff))
            }
        }
        return sb.toString().trimEnd()
    }

    private fun avgSei(fromIdx: Int, toIdx: Int): Float? {
        var sum = 0f
        var n = 0
        for (i in fromIdx until toIdx) {
            val s = sessions[i]
            if (s.count[HabitFeature.SEI] > 0) {
                sum += s.mean[HabitFeature.SEI]
                n++
            }
        }
        return if (n > 0) sum / n else null
    }

    /**
     * Mức cơ sở: trung bình có trọng số (phiên dài hơn và gần đây hơn nặng hơn),
     * loại phiên ngoại lai (MAD) khi đã có từ 5 phiên.
     * spread = căn(phương sai giữa các phiên + phương sai trong phiên).
     */
    fun baseline(): HabitBaseline {
        val c = HabitFeature.COUNT
        val means = FloatArray(c)
        val spreads = FloatArray(c)
        val ok = BooleanArray(c)
        val n = sessions.size

        for (f in 0 until c) {
            val minCount = if (f == HabitFeature.EAR) 1 else HabitConfig.MIN_FRAMES_PER_FEATURE
            val idx = ArrayList<Int>()
            for (i in 0 until n) {
                if (sessions[i].count[f] >= minCount) idx.add(i)
            }
            if (idx.isEmpty()) continue

            var keep: List<Int> = idx
            if (idx.size >= 5) {
                val vals = idx.map { sessions[it].mean[f] }.sorted()
                val med = vals[vals.size / 2]
                val dev = idx.map { abs(sessions[it].mean[f] - med) }.sorted()
                val mad = max(dev[dev.size / 2], HabitFeature.MIN_SPREAD[f] * 0.5f)
                val limit = 3.5f * 1.4826f * mad
                val filtered = idx.filter { abs(sessions[it].mean[f] - med) <= limit }
                if (filtered.size >= 3) keep = filtered
            }

            val w = DoubleArray(keep.size)
            var sumW = 0.0
            var sumM = 0.0
            for ((j, i) in keep.withIndex()) {
                val s = sessions[i]
                val age = n - 1 - i
                val minutes = min(s.validMs / 60000.0, HabitConfig.MAX_WEIGHT_MINUTES)
                w[j] = max(minutes, 0.5) * HabitConfig.RECENCY_DECAY.pow(age)
                sumW += w[j]
                sumM += w[j] * s.mean[f]
            }
            val mean = sumM / sumW

            var between = 0.0
            var within = 0.0
            for ((j, i) in keep.withIndex()) {
                val s = sessions[i]
                val d = s.mean[f] - mean
                between += w[j] * d * d
                within += w[j] * s.std[f] * s.std[f]
            }
            val spread = sqrt(between / sumW + within / sumW).toFloat()

            means[f] = mean.toFloat()
            spreads[f] = max(spread, HabitFeature.MIN_SPREAD[f])
            ok[f] = true
        }
        return HabitBaseline(means, spreads, ok)
    }
}

internal data class HabitStudent(val id: String, val name: String)

/**
 * Quản lý danh sách học sinh (SharedPreferences) và lưu hồ sơ từng em ra file JSON
 * trong bộ nhớ riêng của ứng dụng. Không gửi dữ liệu ra ngoài.
 */
internal class HabitStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("focus_habit_prefs", Context.MODE_PRIVATE)

    private companion object {
        const val TAG = "HabitStore"
        const val KEY_STUDENTS = "students"
        const val KEY_CURRENT = "current_student"
    }

    fun students(): List<HabitStudent> {
        val out = ArrayList<HabitStudent>()
        val raw = prefs.getString(KEY_STUDENTS, null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(HabitStudent(o.getString("id"), o.getString("name")))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Danh sách học sinh lỗi, tạo lại", e)
                out.clear()
            }
        }
        if (out.isEmpty()) {
            out.add(HabitStudent("hs1", "Học sinh 1"))
            persist(out)
        }
        return out
    }

    private fun persist(list: List<HabitStudent>) {
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("name", s.name)
            arr.put(o)
        }
        prefs.edit().putString(KEY_STUDENTS, arr.toString()).apply()
    }

    fun currentId(): String {
        val list = students()
        val cur = prefs.getString(KEY_CURRENT, null)
        return list.firstOrNull { it.id == cur }?.id ?: list[0].id
    }

    fun setCurrent(id: String) {
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    fun nameOf(id: String): String = students().firstOrNull { it.id == id }?.name ?: id

    fun addStudent(name: String): HabitStudent {
        val list = students().toMutableList()
        val clean = name.trim().take(24).ifEmpty { "Học sinh ${list.size + 1}" }
        val st = HabitStudent("hs" + System.currentTimeMillis(), clean)
        list.add(st)
        persist(list)
        return st
    }

    private fun file(id: String): File = File(context.filesDir, "habit_$id.json")

    fun load(id: String): HabitProfile {
        val profile = HabitProfile(id, nameOf(id))
        val f = file(id)
        if (!f.exists()) return profile
        try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("sessions") ?: return profile
            for (i in 0 until arr.length()) {
                SessionSummary.fromJson(arr.getJSONObject(i))?.let { profile.sessions.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không đọc được hồ sơ $id, bắt đầu hồ sơ mới", e)
            profile.sessions.clear()
        }
        return profile
    }

    fun save(p: HabitProfile) {
        try {
            val root = JSONObject()
            root.put("id", p.id)
            root.put("name", p.name)
            val arr = JSONArray()
            for (s in p.sessions) arr.put(s.toJson())
            root.put("sessions", arr)

            val f = file(p.id)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không lưu được hồ sơ ${p.id}", e)
        }
    }

    fun clear(id: String) {
        try {
            file(id).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Không xóa được hồ sơ $id", e)
        }
    }
}


// =====================================================================
// CÔNG CỤ PHÂN TÍCH MẮT NÂNG CAO (kính, góc chéo, nheo mắt, lệch hai mắt)
// =====================================================================

/** Kết quả phân tích mắt của một khung hình (đối tượng tái sử dụng, chỉ đọc trên cameraExecutor). */
private class EyeFrame {
    var valid = false
    var openness = 1f
    var closed = false
    var closedStartMs = 0L
    var blinkDurationMs = 0L
    var blinkIncomplete = false
    var noisy = false
    var sigma = 0f
    var squintPct = 0f
    var asymPct = 0f
    var incompleteRatio = Float.NaN
    var squintAlert = false
    var asymAlert = false
    var incompleteAlert = false
    var usingBlendshapes = false
}

/**
 * Độ mở mắt hợp nhất, bền với kính và góc nhìn chéo.
 *
 * 1. Hình học: EAR 5 cặp điểm mí (trung bình cắt tỉa, bỏ điểm cao nhất/thấp nhất để loại điểm bị chói kính),
 *    chuẩn hóa theo đường cơ sở từng mắt, bù co ngắn do quay đầu (cos yaw) và mí hạ khi nhìn xuống.
 * 2. Blendshape (nếu có): mô hình học sâu của MediaPipe, ít bị ảnh hưởng bởi kính và góc nghiêng.
 * 3. Hợp nhất có kiểm tra bất đồng, lọc One Euro, ngưỡng đóng/mở tự thích nghi theo mức nhiễu.
 * 4. Máy trạng thái chớp mắt theo thời gian thực (không phụ thuộc FPS), có trễ hai ngưỡng.
 * 5. Chỉ số khó chịu: nheo mắt kéo dài, hai mắt mở không đều, chớp không hoàn toàn.
 */
private class EyeEngine {

    private companion object {
        val R_UP = intArrayOf(161, 160, 159, 158, 157)
        val R_LOW = intArrayOf(163, 144, 145, 153, 154)
        val L_UP = intArrayOf(388, 387, 386, 385, 384)
        val L_LOW = intArrayOf(390, 373, 374, 380, 381)
        const val R_OUT = 33
        const val R_IN = 133
        const val L_IN = 362
        const val L_OUT = 263

        const val CLOSE_O = 0.60f
        const val OPEN_O = 0.78f
        const val MIN_CLOSE_DWELL_MS = 40L
        const val MIN_BLINK_MS = 25L
        const val MAX_BLINK_MS = 1_000L
        const val INCOMPLETE_MIN_O = 0.45f
        const val INCOMPLETE_MAX_DT_MS = 55f
        const val ASYM_EXTRA_THRESHOLD = 0.25f
        const val PARTIAL_LO = 0.45f
        const val PARTIAL_HI = 0.80f
        const val DEG2RAD = 0.017453292f
        const val NOISY_ON = 0.12f
        const val NOISY_OFF = 0.09f
        const val NOISY_HOLD_MS = 10_000L
        const val SQUINT_ALERT_PCT = 40f
        const val ASYM_ALERT_PCT = 50f
        const val INCOMPLETE_ALERT_RATIO = 0.6f
        const val RING = 20
    }

    val out = EyeFrame()

    /** Độ nhạy: 0 thấp (chỉ nhận chớp sâu), 1 vừa, 2 cao (nhận cả chớp nông). */
    fun setSensitivity(level: Int) {
        closeO = when (level) {
            0 -> 0.54f
            2 -> 0.66f
            else -> CLOSE_O
        }
        openO = closeO + 0.18f
    }

    private val tmp = FloatArray(5)
    private val openFilter = OneEuroFilter(4.0f, 1.0f)
    private val squintRatio = WindowRatio(60, 20_000L)
    private val asymRatio = WindowRatio(60, 30_000L)

    // Hiệu chuẩn
    private val calE1 = ArrayList<Float>()
    private val calE2 = ArrayList<Float>()
    private val calBs = ArrayList<Float>()
    private val calAsym = ArrayList<Float>()
    private var ready = false
    private var calBase1 = 0.3f
    private var calBase2 = 0.3f
    private var base1 = 0.3f
    private var base2 = 0.3f
    private var bsBase = 0.08f
    private var bsAsymBase = 0f
    private var closeO = CLOSE_O
    private var openO = OPEN_O

    // Trạng thái chớp mắt
    private var closed = false
    private var candStart = 0L
    private var candMin = 1f
    private var closedStart = 0L
    private var minDuring = 1f

    // Nhiễu và lọc khung lỗi
    private var sigma = 0.03f
    private var noisyStart = 0L
    private var prevWr = 0f
    private var prevWl = 0f
    private var glitchStreak = 0
    private var lastUpdateMs = 0L
    private var dtEma = 33f

    // Chớp không hoàn toàn
    private val ring = BooleanArray(RING)
    private var ringIdx = 0
    private var ringCount = 0

    private fun d(lm: Landmarks, a: Int, b: Int, w: Int, h: Int): Float {
        val dx = (lm.x(a) - lm.x(b)) * w
        val dy = (lm.y(a) - lm.y(b)) * h
        return sqrt(dx * dx + dy * dy)
    }

    /** EAR 5 cặp điểm, trung bình cắt tỉa. Trả về NaN nếu hình học không hợp lệ. */
    private fun ear5(
        lm: Landmarks,
        up: IntArray,
        low: IntArray,
        a: Int,
        b: Int,
        w: Int,
        h: Int
    ): Float {
        var sum = 0f
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until 5) {
            val v = d(lm, up[i], low[i], w, h)
            tmp[i] = v
            sum += v
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val hor = d(lm, a, b, w, h)
        if (hor < 1f) return Float.NaN
        return ((sum - mn - mx) / 3f) / hor
    }

    private fun percentile(list: List<Float>, p: Float): Float {
        val s = list.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    fun reset() {
        calE1.clear()
        calE2.clear()
        calBs.clear()
        calAsym.clear()
        ready = false
        sigma = 0.03f
        noisyStart = 0L
        ringIdx = 0
        ringCount = 0
        squintRatio.reset()
        asymRatio.reset()
        pause()
    }

    /** Mất khuôn mặt hoặc khởi động lại luồng: xóa trạng thái tức thời, giữ hiệu chuẩn. */
    fun pause() {
        closed = false
        candStart = 0L
        openFilter.reset()
        squintRatio.pause()
        asymRatio.pause()
        prevWr = 0f
        prevWl = 0f
        glitchStreak = 0
        lastUpdateMs = 0L
        out.valid = false
        out.closed = false
    }

    fun addCalibrationSample(lm: Landmarks, w: Int, h: Int, bs: FloatArray?) {
        val e1 = ear5(lm, R_UP, R_LOW, R_OUT, R_IN, w, h)
        val e2 = ear5(lm, L_UP, L_LOW, L_IN, L_OUT, w, h)
        if (e1.isNaN() || e2.isNaN()) return
        calE1.add(e1)
        calE2.add(e2)
        if (bs != null) {
            calBs.add((bs[0] + bs[1]) / 2f)
            calAsym.add(abs(bs[0] - bs[1]))
        }
    }

    /**
     * Chốt đường cơ sở: lấy phân vị 70 (loại các khung chớp mắt/nheo lọt vào lúc hiệu chuẩn).
     * scale là hệ số trộn với giá trị đã học trong hồ sơ thói quen.
     */
    fun finishCalibration(scale: Float) {
        if (calE1.size < 5) {
            ready = false
            return
        }
        calBase1 = percentile(calE1, 0.70f) * scale
        calBase2 = percentile(calE2, 0.70f) * scale
        base1 = calBase1
        base2 = calBase2
        bsBase = if (calBs.size >= 5) percentile(calBs, 0.5f).coerceIn(0f, 0.5f) else 0.08f
        // Mắt hai bên vốn không đối xứng hoàn toàn: chỉ coi là lệch khi vượt mức lệch tự nhiên đã đo lúc hiệu chuẩn.
        bsAsymBase = if (calAsym.size >= 5) percentile(calAsym, 0.5f).coerceIn(0f, 0.3f) else 0f
        ready = true
        sigma = 0.03f
        noisyStart = 0L
        ringIdx = 0
        ringCount = 0
        squintRatio.reset()
        asymRatio.reset()
        pause()
    }

    private fun recordBlink(incomplete: Boolean) {
        ring[ringIdx] = incomplete
        ringIdx = (ringIdx + 1) % RING
        if (ringCount < RING) ringCount++
    }

    private fun incompleteRatio(): Float {
        if (ringCount < 8) return Float.NaN
        var n = 0
        for (i in 0 until ringCount) if (ring[i]) n++
        return n.toFloat() / ringCount.toFloat()
    }

    fun update(
        lm: Landmarks,
        w: Int,
        h: Int,
        nowMs: Long,
        yawDev: Float,
        pitchDev: Float,
        gazeVDev: Float,
        bs: FloatArray?,
        poseOk: Boolean
    ): EyeFrame {
        val o = out
        o.blinkDurationMs = 0L
        o.blinkIncomplete = false
        o.usingBlendshapes = bs != null

        if (!ready) {
            o.valid = false
            o.closed = false
            return o
        }

        val e1 = ear5(lm, R_UP, R_LOW, R_OUT, R_IN, w, h)
        val e2 = ear5(lm, L_UP, L_LOW, L_IN, L_OUT, w, h)
        val wr = d(lm, R_OUT, R_IN, w, h)
        val wl = d(lm, L_IN, L_OUT, w, h)

        if (lastUpdateMs != 0L) {
            val dtFrame = (nowMs - lastUpdateMs).toFloat()
            if (dtFrame in 1f..300f) dtEma = dtEma * 0.95f + dtFrame * 0.05f
        }
        lastUpdateMs = nowMs

        // 1) Loại khung landmark "nhảy" bất thường (thường do chói kính hoặc mất dấu một khung).
        var glitch = e1.isNaN() || e2.isNaN()
        if (!glitch && prevWr > 0f && prevWl > 0f) {
            val rr = wr / prevWr
            val rl = wl / prevWl
            if (rr > 1.3f || rr < 0.77f || rl > 1.3f || rl < 0.77f) glitch = true
        }
        if (glitch) {
            glitchStreak++
            if (glitchStreak > 8) {
                // Hình học mới ổn định lâu hơn 8 khung: chấp nhận làm mốc mới.
                prevWr = 0f
                prevWl = 0f
                glitchStreak = 0
            }
            o.valid = false
            o.closed = closed
            return o
        }
        glitchStreak = 0
        prevWr = wr
        prevWl = wl

        // 2) Góc quá nghiêng hoặc tín hiệu kém: không kết luận trạng thái mắt.
        if (!poseOk) {
            closed = false
            candStart = 0L
            openFilter.reset()
            o.valid = false
            o.closed = false
            return o
        }

        // 3) Độ mở hình học đã bù: co ngắn khi quay đầu và mí hạ khi nhìn xuống.
        val cosY = cos(min(abs(yawDev), 50f) * 0.9f * DEG2RAD)
        val down = max(pitchDev, 0f) * 0.006f + max(gazeVDev, 0f) * 0.5f
        val expected = (1f - down).coerceIn(0.7f, 1f)
        val c1 = e1 * cosY / expected
        val c2 = e2 * cosY / expected
        val og1 = (c1 / base1).coerceIn(0f, 1.5f)
        val og2 = (c2 / base2).coerceIn(0f, 1.5f)
        val og = (og1 + og2) / 2f

        // 4) Blendshape (nếu có) và hợp nhất có kiểm tra bất đồng giữa hai nguồn.
        var fused = og
        var squintBs = 0f
        var asymBs = 0f
        if (bs != null) {
            val blink = (bs[0] + bs[1]) / 2f
            val ob = (1f - (blink - bsBase) / (1f - bsBase).coerceAtLeast(0.5f)).coerceIn(0f, 1.3f)
            fused = if (abs(og - ob) > 0.45f) {
                // Hai nguồn lệch mạnh: hình học thường bị chói kính làm sai, tin blendshape hơn.
                0.8f * ob + 0.2f * og
            } else {
                0.65f * ob + 0.35f * og
            }
            squintBs = (bs[2] + bs[3]) / 2f
            asymBs = abs(bs[0] - bs[1]) - bsAsymBase
        }

        // 5) Lọc thời gian và ước lượng nhiễu khi mắt đang mở ổn định.
        val f = openFilter.filter(fused, nowMs)
        if (!closed && f > 0.85f) {
            sigma = sigma * 0.98f + abs(fused - f) * 1.25f * 0.02f
        }
        sigma = sigma.coerceIn(0f, 0.3f)

        if (sigma > NOISY_ON) {
            if (noisyStart == 0L) noisyStart = nowMs
        } else if (sigma < NOISY_OFF) {
            noisyStart = 0L
        }
        val noisy = noisyStart != 0L && nowMs - noisyStart >= NOISY_HOLD_MS

        // 6) Ngưỡng đóng/mở tự thích nghi: càng nhiễu (kính, chói) càng khắt khe để tránh chớp giả.
        val closeThr = (closeO - 1.5f * sigma).coerceIn(0.40f, closeO)
        val openThr = max(closeThr + 0.12f, openO - sigma).coerceAtMost(0.88f)

        // 7) Máy trạng thái chớp mắt theo thời gian thực.
        if (!closed) {
            if (f < closeThr) {
                if (candStart == 0L) {
                    candStart = nowMs
                    candMin = f
                }
                candMin = min(candMin, f)
                val dwell = nowMs - candStart
                // Đóng chắc chắn nếu đủ lâu, hoặc đóng rất sâu (bắt được cú chớp 1 khung khi FPS thấp).
                if (dwell >= MIN_CLOSE_DWELL_MS || f < closeThr - 0.15f) {
                    closed = true
                    closedStart = candStart
                    minDuring = candMin
                }
            } else {
                candStart = 0L
            }
        } else {
            minDuring = min(minDuring, f)
            if (f > openThr) {
                val dur = nowMs - closedStart
                if (dur in MIN_BLINK_MS..MAX_BLINK_MS) {
                    o.blinkDurationMs = dur
                    // FPS thấp (>55 ms/khung) có thể bỏ lỡ đáy của cú chớp: không kết luận "không hoàn toàn".
                    if (dtEma <= INCOMPLETE_MAX_DT_MS) {
                        o.blinkIncomplete = minDuring > INCOMPLETE_MIN_O
                        recordBlink(o.blinkIncomplete)
                    }
                }
                closed = false
                candStart = 0L
            }
        }

        // 8) Chỉ số khó chịu: nheo mắt kéo dài, hai mắt mở không đều.
        var partial = !closed && f >= PARTIAL_LO && f < PARTIAL_HI
        if (!partial && !closed && squintBs > 0.45f && f < 0.9f) partial = true
        squintRatio.record(nowMs, partial)

        val frontal = abs(yawDev) < 15f
        val asymFlag = if (bs != null) asymBs > ASYM_EXTRA_THRESHOLD else abs(og1 - og2) > 0.35f
        asymRatio.record(nowMs, frontal && !closed && f > 0.35f && asymFlag)

        val sq = squintRatio.percent(nowMs)
        val asym = asymRatio.percent(nowMs)
        val inc = incompleteRatio()

        // 9) Đường cơ sở theo dõi chậm: bám theo ánh sáng/kính thay đổi nhưng không "trôi" theo mệt mỏi.
        if (f > 0.8f && !closed) {
            if (c1 > base1) {
                if (c1 < base1 * 1.35f) base1 += 0.02f * (c1 - base1)
            } else if (c1 > base1 * 0.85f) {
                base1 -= 0.0004f * (base1 - c1)
            }
            if (c2 > base2) {
                if (c2 < base2 * 1.35f) base2 += 0.02f * (c2 - base2)
            } else if (c2 > base2 * 0.85f) {
                base2 -= 0.0004f * (base2 - c2)
            }
            base1 = base1.coerceIn(calBase1 * 0.85f, calBase1 * 1.25f)
            base2 = base2.coerceIn(calBase2 * 0.85f, calBase2 * 1.25f)
        }

        o.valid = true
        o.openness = f
        o.closed = closed
        o.closedStartMs = closedStart
        o.sigma = sigma
        o.noisy = noisy
        o.squintPct = sq
        o.asymPct = asym
        o.incompleteRatio = inc
        o.squintAlert = sq >= SQUINT_ALERT_PCT
        o.asymAlert = asym >= ASYM_ALERT_PCT
        o.incompleteAlert = !inc.isNaN() && inc >= INCOMPLETE_ALERT_RATIO
        return o
    }
}