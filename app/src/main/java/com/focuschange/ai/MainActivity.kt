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

class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "FocusChangeAI"
        const val PREFS_NAME = "focus_change_prefs"
        // Đổi key: bỏ hệ số cũ đã được hiệu chuẩn theo công thức sai.
        const val KEY_DISTANCE_SCALE = "distance_scale_v2"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_SOUND_MODE = "sound_mode"
        const val KEY_SENSITIVITY = "sensitivity_level"
        const val KEY_ENHANCE = "enhance_mode"
        const val KEY_HABIT_LEARN = "habit_learn_enabled"
        const val HABIT_AUTOSAVE_MS = 30_000L
        const val HABIT_STATUS_REFRESH_MS = 1_000L

        // SEI thấp hơn mức thường ngày của chính học sinh (độ lệch 1.5 lần dao động riêng) trong 45 giây.
        const val LOW_VS_PERSONAL_MS = 45_000L
        const val LOW_VS_PERSONAL_K = 1.5f
        const val PERSONAL_SEI_MIN_SPREAD = 8f

        /** Tổng số ngưỡng có thể thay bằng ngưỡng cá nhân (để HUD nói rõ bao nhiêu cái là mặc định).
         *  Chính bằng số lần gọi mix() trong applyPersonalParams: 4 tư thế/hướng nhìn + 2 nhịp chớp
         *  + PERCLOS + MAR. Nếu thêm ngưỡng cá nhân mới thì phải sửa con số này theo. */
        const val TOTAL_PERSONAL_THRESHOLDS = 8

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

        // cửa sổ hiệu chuẩn khoảng cách: 8 giây ngồi yên là đủ 20 khung ở 3 FPS trở lên.
        const val DIST_CAL_WINDOW_MS = 8_000L
        const val DIST_MEDIAN_SIZE = 9

        // Thư ký khoảng cách. Khóe mắt (8,9 cm ~ 89 px @50 cm) nhiễu ~1,6%;
        // mống mắt (1,17 cm ~ 12 px @50 cm) nhiễu ~12% nên chỉ dùng để bắt ngoại lai.
        const val DIST_MAX_YAW_DEG = 40f
        const val IRIS_MIN_PIXELS = 6f
        const val IRIS_MAX_DISAGREE = 0.15f
        const val IRIS_AGREE_LO = 0.75f
        const val IRIS_AGREE_HI = 1.35f

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

        /** Nhịp khung hình mong đợi của luồng camera (~30 FPS), dùng làm tham chiếu cho WindowRatio. */
        const val RATIO_REF_FRAME_MS = 33L
        const val PERCLOS_ALERT = 15f
        // PERCLOS chuẩn trong tài liệu buồn ngủ là P80: mí che >= 80% đồng tử.
        // openness = độ mở mắt so với đường cơ sở của chính người này,
        // nên P80 tương ứng openness <= 0.20 và P70 tương ứng <= 0.30.
        const val PERCLOS_P70_OPEN = 0.30f
        const val PERCLOS_P80_OPEN = 0.20f

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

        /**
         * Thời gian giữ một thông báo ngắn trên dòng trạng thái. Dòng "có mặt / chưa có mặt"
         * được viết lại mỗi 120 ms, nên nếu nó ghi đè ngay thì bản tóm tắt ghi CSV ("NGỪNG
         * DÙNG FILE NÀY: ..."), lỗi chia sẻ báo cáo hay nhắc quyền camera biến mất trước khi
         * người đứng cạnh giám khảo kịp đọc hết.
         */
        const val STATUS_HOLD_MS = 20_000L

        // ---------------- Bảng "Chuẩn bị trình diễn" ----------------
        // Trước khi lên sân khấu: bấm một nút, ngồi yên 20 giây, app tự đo xem máy có theo kịp
        // không và chỉ ra đúng chỗ phải sửa. Không con số nào trong bảng này là suy đoán.
        const val PREFLIGHT_MS = 20_000L
        const val PREFLIGHT_FPS_FAIL = 12f
        const val PREFLIGHT_FPS_WARN = 20f
        const val PREFLIGHT_QUALITY_WARN = 60
        const val PREFLIGHT_LATENCY_WARN_MS = 80f
        const val PREFLIGHT_FACE_RATIO_MIN = 0.85f
        const val PREFLIGHT_BATTERY_MIN = 25

        // Histogram độ trễ xử lý mỗi khung (vẽ bằng Canvas, không cần thư viện).
        const val LATENCY_BINS = 12
        const val LATENCY_BIN_MS = 15f
        const val LATENCY_SAMPLES = 240

        const val BREAKDOWN_REFRESH_MS = 400L
        const val THRESHOLD_REFRESH_MS = 1_000L

        // Dòng sự kiện 60 giây: mỗi loại một dải nằm ngang, newest ở cạnh phải.
        const val TL_BAND_ALERT = 0
        const val TL_BAND_CHRONIC = 1
        const val TL_BAND_NO_FACE = 2
        const val TL_BAND_BLINK = 3
        const val TL_BAND_TOO_CLOSE = 4
        const val TL_BAND_COUNT = 5
        const val TL_WINDOW_SEC = 60L
        const val TL_MAX_EVENTS = 400
        const val CSV_TAIL_LINES = 10

        const val LOG_EVERY_N_FRAMES = 30
    }

    // @Volatile: tạo trên UI thread, đọc trên cameraExecutor, và giờ được set về null bởi
    // chính cameraExecutor (xem onDestroy) - ba thread cùng chạm vào một con trỏ native.
    @Volatile
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
    // `status` đi qua một property để mỗi lần đổi chữ tự cắm thêm thời gian được giữ: dòng
    // trạng thái chu kỳ (có mặt / chưa có mặt) chạy mỗi 120 ms và nếu nó cứ ghi đè thẳng thì
    // bản tóm tắt ghi CSV hay nhắc quyền camera mất hút trước khi người bên cạnh đọc kịp.
    private var statusText by mutableStateOf("Đang khởi tạo MediaPipe...")
    private var statusHoldUntilMs = 0L
    private var status: String
        get() = statusText
        set(value) {
            if (value != statusText) {
                statusHoldUntilMs = SystemClock.elapsedRealtime() + STATUS_HOLD_MS
            }
            statusText = value
        }
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
    private var personHintText by mutableStateOf<String?>(null)
    private var calibrationText by mutableStateOf("Đang chờ khuôn mặt để hiệu chuẩn...")
    private var bpmText by mutableStateOf("--")
    private var seiValue by mutableIntStateOf(-1)

    private var perclosText by mutableStateOf("--")
    private var perclosP70Text by mutableStateOf("--")
    private var perclosP80Text by mutableStateOf("--")
    private var loggingText by mutableStateOf("Ghi CSV: tắt")
    private var falseAlertCount by mutableIntStateOf(0)
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
    private var enhanceUi by mutableStateOf(0)
    private var cameraMirrored by mutableStateOf(true)
    private var imageDetailText by mutableStateOf("--")
    private var habitLearnUi by mutableStateOf(true)
    private var showAddStudent by mutableStateOf(false)
    private var showResetHabit by mutableStateOf(false)
    private var newStudentName by mutableStateOf("")

    // ---------------- Bảng điều khiển mở rộng ----------------
    // 0 = thông số theo dõi, 1 = chuẩn bị trình diễn, 2 = chỉ số và ngưỡng,
    // 3 = diễn giải cho giám khảo, 4 = kịch bản demo.
    private var panelTab by mutableIntStateOf(0)

    private var preflightRunning by mutableStateOf(false)
    private var preflightProgress by mutableIntStateOf(0)
    private var preflightVerdict by mutableStateOf("")
    private var preflightLines by mutableStateOf(emptyList<String>())

    private var seiBreakdownText by mutableStateOf("--")
    private var thresholdRows by mutableStateOf("")
    private var latencyBars by mutableStateOf(emptyList<Int>())
    private var latencySummary by mutableStateOf("--")
    private var demoDone by mutableIntStateOf(0)
    private var glossaryOpen by mutableIntStateOf(-1)
    private var presenterMode by mutableStateOf(false)
    private var showCsvTail by mutableStateOf(false)
    private var firebaseText by mutableStateOf("Firebase: chưa bật")
    private var csvTailLines by mutableStateOf(emptyList<String>())
    private var csvTailNote by mutableStateOf("")
    private var timelineMarks by mutableStateOf(emptyList<Pair<Float, Int>>())

    // Dòng sự kiện 60 giây gần nhất: mỗi loại một dải, điểm mới nhất nằm bên phải.
    private class TimedEvent(val second: Long, val band: Int)

    private val timelineEvents = ArrayDeque<TimedEvent>()
    private val timelineLastSec = LongArray(TL_BAND_COUNT) { -1L }

    // Máy đếm cho bài kiểm tra 20 giây. Chỉ UI thread chạm vào (gọi từ applySnapshot),
    // nên không cần @Volatile.
    private var pfStartMs = 0L
    private var pfFrames = 0
    private var pfFpsSum = 0f
    private var pfFpsMin = Float.MAX_VALUE
    private var pfProcSum = 0f
    private var pfProcMax = 0f
    private var pfQualSum = 0
    private var pfQualN = 0
    private var pfQualMin = 100
    private var pfNoFaceFrames = 0
    private var pfNoDistFrames = 0
    private var pfAlertFrames = 0
    private var pfSeiSum = 0L
    private var pfSeiN = 0L
    private var pfPerclosReady = false
    private var pfCalibrated = false
    private var preflightWatchdog: Runnable? = null
    private var csvReading = false

    // Histogram độ trễ: mảng đệm thường, chỉ bản bất biến mới đưa vào state.
    private val latencyCounts = IntArray(LATENCY_BINS)

    // Ngăn của từng mẫu trong vòng tròn LATENCY_SAMPLES. Có vòng tròn này mới xoá đúng mẫu
    // cũ nhất; nếu không, histogram chỉ phình to và "trung bình X ms" là trung bình của cả
    // phiên, không phải của 240 khung gần nhất như chữ trên HUD hứa.
    private val latencyBins = IntArray(LATENCY_SAMPLES)
    private var latencyWriteIdx = 0
    private var latencyFilled = 0

    // Bảng phân tách SEI do cameraExecutor dựng, UI thread chỉ đọc tham chiếu chuỗi.
    @Volatile
    private var seiBreakdown = "--"
    private var lastBreakdownMs = 0L
    private var lastThresholdMs = 0L
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

    // Thời điểm bấm "Chỉnh 50 cm" theo đồng hồ elapsedRealtime. Phải @Volatile vì UI thread
    // ghi còn cameraExecutor đọc để quyết định mẫu còn dùng được hay đã quá cũ.
    @Volatile
    private var distanceCalRequestedMs = 0L

    @Volatile
    private var distanceScale = 1f

    /**
     * Hệ số đọc từ pref cũ `distance_scale_v2`. Từ bản này hệ số thật sống trong hồ sơ từng em
     * (HabitProfile.distanceScale); pref cũ chỉ còn là giá trị cứu cho hồ sơ chưa từng hiệu chuẩn
     * trên bản mới, và bị ghi đè ngay lần hiệu chuẩn kế tiếp.
     */
    @Volatile
    private var legacyDistanceScale = 1f

    /** Đổi hồ sơ là đổi luôn hệ số + chữ ký bề rộng mặt, không được mang số của em khác sang. */
    private fun applyProfileCalibration() {
        val prof = habitProfile
        val stored = prof?.distanceScale
        distanceScale = if (stored != null && stored.isFinite() && stored > 0f) {
            stored.coerceIn(0.3f, 3f)
        } else {
            legacyDistanceScale
        }
        personGuard.reset()
        personHintPlain = null
        personHintKind = 0
        distanceScaleText = String.format(Locale.US, "x%.2f", distanceScale)
    }

    private val personGuard = PersonGuard()

    /**
     * Dòng nhắc "có vẻ đã đổi người", do cameraExecutor viết và HudSnapshot chép lại.
     * Không đi qua chuỗi cảnh báo: chọn sai hồ sơ là lỗi vận hành, không phải biểu hiện mất tập
     * trung, và ghi nó vào máy đếm cảnh báo sẽ làm bẩn số liệu.
     */
    @Volatile
    private var personHintPlain: String? = null

    /** 0 = không nhắc, 1 = phép thử liên tục (mất khi sạch), 2 = hiệu chuẩn vừa lệch (giữ lại). */
    @Volatile
    private var personHintKind = 0

    // Hiệu chuẩn EAR + đường cơ sở cá nhân
    // Ba biến này thường chỉ cameraExecutor chạm tới, NHƯNG loggerMeta() đọc chúng trên UI
    // thread để viết meta.txt. Không có @Volatile thì file môi trường của phiên ghi CSV có
    // thể chép lại giá trị của phiên trước - và meta.txt chính là thứ dùng để tái lập số liệu.
    @Volatile
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

    @Volatile
    private var baselineEar1 = 0f

    @Volatile
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
    // Khoảng cách thay đổi rất chậm so với góc đầu, nên cutoff thấp hơn nhiều.
    private val distanceFilter = OneEuroFilter(0.25f, 0.02f)

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

    // Xử lý ảnh mức thấp: CLAHE thích ứng và lọc nhiễu nhẹ trên kênh sáng, trước khi chuyển sang RGB.
    private val enhancer = ImageEnhancer()
    private val faceBox = FloatArray(4)
    private var faceBoxMs = 0L
    private var imageDetail = "--"
    private var lastImageDetailMs = 0L

    // 0 = tự động theo ánh sáng, 1 = tắt (để so sánh A/B chất lượng nhận diện)
    @Volatile
    private var enhanceMode = 0

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

    @Volatile
    private var personalThresholdCount = 0

    /**
     * Phần trăm trọng số của hồ sơ riêng trong ngưỡng hiện hành (chính là c của
     * HabitProfile.confidence, 0 khi chưa đủ phiên). Bảng "Chỉ số" in ra để không ai hiểu nhầm
     * "hồ sơ riêng" nghĩa là 100% số của em đó: ở 4 phiên thì cá nhân chỉ chiếm 20%.
     */
    @Volatile
    private var personalBlend = 0f
    private var lowVsPersonalStartMs = 0L
    private var lastSeiValue = -1

    @Volatile
    private var habitLearnEnabled = true

    @Volatile
    private var studentSwitchRequest: String? = null

    @Volatile
    private var habitResetRequested = false

    // Ngưỡng hiện hành: mặc định, sẽ được thay bằng ngưỡng cá nhân khi hồ sơ đã đủ dữ liệu.
    // cameraExecutor ghi, UI thread đọc (bảng "Chỉ số"), nên mỗi cái phải là @Volatile.
    @Volatile private var thrLookYaw = LOOK_AWAY_YAW_DEG
    @Volatile private var thrLookPitch = LOOK_AWAY_PITCH_DEG
    @Volatile private var thrGazeH = GAZE_H_DRIFT
    @Volatile private var thrGazeV = GAZE_V_DRIFT
    @Volatile private var thrBlinkLow = LOW_BLINK_BPM
    @Volatile private var thrBlinkHigh = HIGH_BLINK_BPM
    @Volatile private var thrPerclos = PERCLOS_ALERT
    @Volatile private var thrMar = MAR_YAWN

    // Đúng thứ tự sáu thành phần trong buildSeiBreakdown.
    private val seiWeights = floatArrayOf(
        W_POSTURE, W_DISTANCE, W_GAZE, W_EYES, W_BPM, W_EXPRESSION
    )

    // Máy trạng thái chớp mắt
    private var eyesClosed = false
    private var closedStartMs = 0L
    private var lastBlinkMs = 0L
    private val blinkTimestamps = mutableListOf<Long>()
    private val blinkDurations = mutableListOf<Long>()
    private var monitoringStartMs = 0L

    // PERCLOS và tỉ lệ mất tập trung
    private val perclos =
        WindowRatio(RATIO_BUCKETS, RATIO_MIN_SAMPLE_MS, refFrameMs = RATIO_REF_FRAME_MS)
    private val inattention =
        WindowRatio(RATIO_BUCKETS, RATIO_MIN_SAMPLE_MS, refFrameMs = RATIO_REF_FRAME_MS)
    private val perclosP70 =
        WindowRatio(RATIO_BUCKETS, RATIO_MIN_SAMPLE_MS, refFrameMs = RATIO_REF_FRAME_MS)
    private val perclosP80 =
        WindowRatio(RATIO_BUCKETS, RATIO_MIN_SAMPLE_MS, refFrameMs = RATIO_REF_FRAME_MS)

    // Độ mở mắt thô của khung hình gần nhất, để dành ghi ra CSV. Biến eye chỉ
    // sống trong một nhánh if nên phải cất tạm ra đây.
    private var lastOpenness = Float.NaN
    private var lastSigma = Float.NaN
    private var lastEyeValid = false

    private val dataLogger = DataLogger()

    // Nút bấm đổi trên UI thread, vòng khung hình đọc trên cameraExecutor. Không @Volatile thì
    // máy có thể chạy cả phiên mà không thấy lệnh bật ghi: file CSV trống mà màn hình báo "đang ghi".
    @Volatile
    private var loggingEnabled = false
    private val csvBuf = StringBuilder(256)

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

        // Firebase chỉ để đồng bộ bản tổng kết khi một phiên kết thúc. Thiếu file cấu hình thì
        // start() tự đánh dấu đã tắt và app vẫn đo bình thường.
        FirebaseSync.start(applicationContext)
        firebaseText = FirebaseSync.statusText()

        legacyDistanceScale = prefs.getFloat(KEY_DISTANCE_SCALE, 1f).coerceIn(0.3f, 3f)
        distanceScale = legacyDistanceScale
        distanceScaleText = String.format(Locale.US, "x%.2f", distanceScale)
        soundMode = prefs.getInt(
            KEY_SOUND_MODE,
            if (prefs.getBoolean(KEY_SOUND, true)) 2 else 0
        )

        sensLevel = prefs.getInt(KEY_SENSITIVITY, 1).coerceIn(0, 2)
        sensitivityUi = sensLevel
        enhanceMode = prefs.getInt(KEY_ENHANCE, 0).coerceIn(0, 1)
        enhanceUi = enhanceMode

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

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { cycleEnhance() }) {
                                    Text(
                                        if (enhanceUi == 0) "Tăng cường ảnh: Tự động"
                                        else "Tăng cường ảnh: Tắt"
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
                            Text(text = "Xử lý ảnh: $imageDetailText")

                            Text(
                                text = "Khoảng cách: $distanceText (hệ số $distanceScaleText của hồ sơ này)",
                                color = if (distanceAlert) Color.Red
                                else MaterialTheme.colorScheme.onSurface
                            )

                            personHintText?.let {
                                Text(
                                    text = it,
                                    color = Color(0xFFFFA000),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Text(text = "Tư thế đầu: $headPoseText")
                            Text(text = "Hướng nhìn: $gazeText")
                            Text(text = "Vi biểu cảm: $expressionText")
                            Text(text = "Chỉ số: $expressionMetricsText")
                            Text(text = "Mất tập trung: $inattentionText")

                            Text(text = "Mệt mỏi (tỉ lệ nhắm mắt): $perclosText")
                            Text(text = "PERCLOS P70/P80: $perclosP70Text / $perclosP80Text")
                            Text(
                                text = loggingText,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(text = "Chỉ số mệt mỏi: $fatigueText")
                            Text(text = "Chất lượng tín hiệu: $signalText")
                            Text(text = "Hiệu chuẩn: $calibrationText")
                            Text(text = "Phiên làm việc: $sessionText")
                            Text(text = "Thời gian nhận diện: $processingText ($fpsText FPS)")
                            Text(
                                text = "Mọi xử lý và lưu trữ đều ở trên máy này. App không tự gửi đi đâu; chỉ khi bạn bấm Chia sẻ báo cáo, phần bạn tự đặt tên mới đi kèm.",
                                style = MaterialTheme.typography.bodySmall
                            )

                            Spacer(Modifier.height(10.dp))

                            // ---- Bảng điều khiển: chọn một trong năm khung ----
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                PanelTabs.forEachIndexed { index, title ->
                                    TextButton(onClick = { panelTab = index }) {
                                        Text(
                                            text = if (panelTab == index) "▸ $title" else title,
                                            color = if (panelTab == index) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { presenterMode = !presenterMode }) {
                                    Text(if (presenterMode) "Chế độ trình chiếu: BẬT" else "Chế độ trình chiếu: tắt")
                                }
                                TextButton(onClick = { panelTab = 2 }) {
                                    Text("Xem phân tích điểm")
                                }
                            }

                            if (presenterMode) {
                                val presenterSei = if (seiValue < 0) "--" else "$seiValue"
                                Text(
                                    text = "SEI $presenterSei / 100",
                                    style = MaterialTheme.typography.displaySmall,
                                    color = seiColorOf(seiValue)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Mệt mỏi (tỉ lệ nhắm mắt)", style = MaterialTheme.typography.bodySmall)
                                        Text(perclosText, style = MaterialTheme.typography.headlineSmall)
                                        Text("Mất tập trung 60 giây", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            inattentionText.substringBefore('|'),
                                            style = MaterialTheme.typography.headlineSmall
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text("Khoảng cách", style = MaterialTheme.typography.bodySmall)
                                        Text(distanceText, style = MaterialTheme.typography.headlineSmall)
                                        Text("Máy", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "$fpsText FPS - $processingText",
                                            style = MaterialTheme.typography.headlineSmall
                                        )
                                    }
                                }
                                Text(
                                    text = "Mắt: $eyeStatus | $signalText",
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Text(
                                    "Chế độ này chỉ phóng to chữ cho giám khảo đứng xa, không thay đổi phép đo.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            when (panelTab) {
                                1 -> {
                                    Text(
                                        "Kiểm tra 20 giây trước khi lên bàn thi",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        "Đặt máy đúng chỗ sẽ đặt, ngồi yên như lúc học. App đo nhịp khung hình, " +
                                            "độ trễ, ánh sáng, hiệu chuẩn, quyền camera, pin, chỗ trống và âm báo - " +
                                            "rồi chỉ rõ mục nào chưa đạt.",
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { startPreflight() },
                                            enabled = !preflightRunning
                                        ) {
                                            Text(if (preflightRunning) "Đang đo..." else "Chạy kiểm tra")
                                        }
                                        TextButton(onClick = { clearPreflight() }) {
                                            Text("Xóa kết quả")
                                        }
                                    }

                                    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                                        val p = preflightProgress.coerceIn(0, 100) / 100f
                                        drawLine(
                                            color = if (p < 1f) Color(0xFF2979FF) else Color(0xFF43A047),
                                            start = Offset(0f, 5f),
                                            end = Offset(size.width * p, 5f),
                                            strokeWidth = 10f
                                        )
                                    }

                                    if (preflightVerdict.isNotEmpty()) {
                                        Text(
                                            text = preflightVerdict,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (preflightVerdict.startsWith("CHƯA")) {
                                                Color.Red
                                            } else {
                                                Color(0xFF43A047)
                                            }
                                        )
                                    }

                                    preflightLines.forEach { line ->
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = when (line.take(2)) {
                                                "OK" -> MaterialTheme.colorScheme.onSurface
                                                "!!" -> Color(0xFFEF6C00)
                                                "XX" -> Color.Red
                                                else -> MaterialTheme.colorScheme.primary
                                            }
                                        )
                                    }
                                }

                                2 -> {
                                    Text(
                                        "Điểm SEI được tính từ đâu",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(seiBreakdownText, style = MaterialTheme.typography.bodySmall)

                                    Spacer(Modifier.height(8.dp))

                                    Text(
                                        "Độ trễ xử lý một khung hình",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(latencySummary, style = MaterialTheme.typography.bodySmall)
                                    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                                        val bars = latencyBars
                                        if (bars.isEmpty()) return@Canvas

                                        val step = size.width / bars.size
                                        for (i in bars.indices) {
                                            val h = size.height * bars[i] / 100f
                                            drawLine(
                                                color = if (i * LATENCY_BIN_MS > PREFLIGHT_LATENCY_WARN_MS) {
                                                    Color(0xFFEF6C00)
                                                } else {
                                                    Color(0xFF26A69A)
                                                },
                                                start = Offset(i * step + step * 0.15f, size.height),
                                                end = Offset(i * step + step * 0.15f, size.height - h),
                                                strokeWidth = step * 0.7f
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    Text(
                                        "Mọi ngưỡng đang chi phối kết quả",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(thresholdRows, style = MaterialTheme.typography.bodySmall)

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { sharePanelReport() }) {
                                            Text("Xuất bảng kiểm định")
                                        }
                                        Button(onClick = { toggleCsvLogging() }) {
                                            Text(if (loggingEnabled) "Dừng ghi CSV" else "Ghi CSV đối chiếu")
                                        }
                                    }

                                    Text(
                                        firebaseText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (FirebaseSync.enabled) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            Color.Red
                                        }
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    Text(
                                        "Dòng sự kiện 60 giây gần nhất",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        "Nhìn hàng dọc mà biết ngay cảnh báo nào vừa nổ vì gì: mỗi loại một dải, " +
                                            "mới nhất nằm sát cạnh phải.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                                        val marks = timelineMarks
                                        val bandH = size.height / TL_BAND_COUNT

                                        for (b in 0..TL_BAND_COUNT) {
                                            drawLine(
                                                color = Color.Gray.copy(alpha = 0.25f),
                                                start = Offset(0f, b * bandH),
                                                end = Offset(size.width, b * bandH),
                                                strokeWidth = 1f
                                            )
                                        }

                                        for (mark in marks) {
                                            val x = size.width * mark.first
                                            val top = mark.second * bandH + 3f
                                            drawLine(
                                                color = TimelineBands[mark.second].second,
                                                start = Offset(x, top),
                                                end = Offset(x, (mark.second + 1) * bandH - 3f),
                                                strokeWidth = 3f
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TimelineBands.forEach { band ->
                                            Text(
                                                text = band.first,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = band.second
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { toggleCsvTail() }) {
                                            Text(if (showCsvTail) "Ẩn dữ liệu thô" else "Xem dòng CSV cuối")
                                        }
                                        TextButton(onClick = { readCsvTail() }) {
                                            Text("Đọc lại")
                                        }
                                    }

                                    if (showCsvTail) {
                                        Text(csvTailNote, style = MaterialTheme.typography.bodySmall)
                                        csvTailLines.forEach { line ->
                                            Text(
                                                text = line,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (line.startsWith("t_ms")) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                        Text(
                                            csvColumnLegend(),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                3 -> {
                                    Text(
                                        "Diễn giải từng chỉ số",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        "Bấm vào một dòng để mở rộng. Phần nói rõ cái gì chưa kiểm chứng " +
                                            "quan trọng hơn phần làm cho số liệu trông chắc chắn.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    PanelGlossary.forEachIndexed { index, entry ->
                                        TextButton(
                                            onClick = {
                                                glossaryOpen = if (glossaryOpen == index) -1 else index
                                            }
                                        ) {
                                            Text(
                                                text = if (glossaryOpen == index) {
                                                    "- ${entry.first}"
                                                } else {
                                                    "+ ${entry.first}"
                                                }
                                            )
                                        }
                                        if (glossaryOpen == index) {
                                            Text(
                                                entry.second,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                4 -> {
                                    Text(
                                        "Kịch bản tập trước buổi thi",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    PanelDemoSteps.forEachIndexed { index, step ->
                                        val done = (demoDone and (1 shl index)) != 0
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = { toggleDemoStep(index) }) {
                                                Text(if (done) "[X]" else "[ ]")
                                            }
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    step.first,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    "-> ${step.second}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (done) {
                                                        Color(0xFF43A047)
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    val doneCount = PanelDemoSteps.indices.count {
                                        (demoDone and (1 shl it)) != 0
                                    }
                                    Text(
                                        String.format(
                                            Locale.US,
                                            "Đã tập %d/%d bước. Bước 5 (mất khuôn mặt) là bước dễ hỏng nhất trên sân khấu.",
                                            doneCount,
                                            PanelDemoSteps.size
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
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
                                    // Phải ghi mốc thời gian TRƯỚC rồi mới bật cờ: hai biến này
                                    // đọc trên cameraExecutor, và nếu một khung xen giữa hai lần
                                    // ghi thì nó thấy "đã xin" với mốc của lần xin trước (hoặc 0),
                                    // kết luận là lời xin quá cũ, tắt cờ - nút bấm biến mất im
                                    // lặng mà không hề hiệu chuẩn.
                                    distanceCalRequestedMs = SystemClock.elapsedRealtime()
                                    distanceCalibrationRequested = true
                                    if (faceCount == 0) {
                                        status = "Chỉnh 50 cm: chưa thấy khuôn mặt, hãy ngồi thẳng trong khung hình"
                                    }
                                }
                            ) {
                                Text("Chỉnh 50 cm")
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { toggleCsvLogging() }
                            ) {
                                Text(if (loggingEnabled) "Dừng ghi CSV" else "Ghi CSV")
                            }

                            Button(
                                onClick = {
                                    if (logFalseAlert()) {
                                        falseAlertCount++
                                    } else {
                                        status = "Không ghi được báo cáo sai"
                                    }
                                }
                            ) {
                                Text("Cảnh báo sai ($falseAlertCount)")
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
                                            (if (cameraMirrored) 1f - lm.x(index) else lm.x(index)) * fw * scale + offsetX,
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

                // Ưu tiên camera trước (điện thoại). Thiết bị không có camera trước (máy tính bảng, Chromebook,
                // giả lập trên máy tính, webcam gắn ngoài) thì dùng camera sau hoặc camera bất kỳ đang có.
                val hasFront = hasCameraSafe(provider, CameraSelector.DEFAULT_FRONT_CAMERA)
                val hasBack = !hasFront && hasCameraSafe(provider, CameraSelector.DEFAULT_BACK_CAMERA)
                if (!hasFront && !hasBack && provider.availableCameraInfos.isEmpty()) {
                    throw IllegalStateException("Không tìm thấy camera nào trên thiết bị")
                }
                val selector = when {
                    hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
                    hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
                    else -> CameraSelector.Builder().build()
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    analysis
                )

                cameraStarted = true

                runOnUiThread {
                    cameraMirrored = hasFront
                    status = if (hasFront) {
                        "Camera trước đang hoạt động."
                    } else {
                        "Camera đang hoạt động (không phải camera trước)."
                    }
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

    private fun hasCameraSafe(provider: ProcessCameraProvider, selector: CameraSelector): Boolean {
        return try {
            provider.hasCamera(selector)
        } catch (e: Exception) {
            false
        }
    }

    private fun loadCameraIntrinsics() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager

            // Lượt 1: camera trước (điện thoại). Lượt 2: camera bất kỳ (máy tính bảng, giả lập, webcam ngoài).
            for (frontOnly in booleanArrayOf(true, false)) {
                for (id in cm.cameraIdList) {
                    val c = cm.getCameraCharacteristics(id)
                    if (frontOnly && c.get(CameraCharacteristics.LENS_FACING) !=
                        CameraCharacteristics.LENS_FACING_FRONT
                    ) continue

                    val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.firstOrNull() ?: continue
                    val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue

                    sensorFocalMm = f
                    sensorPhysWmm = max(size.width, size.height)
                    sensorPhysHmm = min(size.width, size.height)
                    Log.d(TAG, "Camera $id: f=${f}mm sensor=${size.width}x${size.height}mm")
                    return
                }
            }
            Log.d(TAG, "Không có thông số tiêu cự: dùng giá trị mặc định, hãy bấm Chỉnh 50 cm")
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

    private fun cycleEnhance() {
        enhanceMode = if (enhanceMode == 0) 1 else 0
        enhanceUi = enhanceMode
        prefs.edit().putInt(KEY_ENHANCE, enhanceMode).apply()
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
            applyProfileCalibration()
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
                val old = habitProfile
                habitStore.clear(id)
                habitProfile = habitStore.load(id)
                // "Xóa hồ sơ" có nghĩa là quên các phiên đã học, không phải quên luôn lần hiệu
                // chuẩn khoảng cách: đó là phép đo vật lý, xóa xong bắt người dùng hiệu chuẩn lại
                // bằng tay mà không nói cho họ biết.
                old?.let { prev ->
                    if (prev.hasCalibration()) {
                        habitProfile?.distanceScale = prev.distanceScale
                        habitProfile?.calRawDistanceCm = prev.calRawDistanceCm
                        habitProfile?.calibratedAtMs = prev.calibratedAtMs
                    }
                }
            }
            applyProfileCalibration()
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
        personalThresholdCount = 0
        personalBlend = 0f
        habitDetail = ""
        personalSeiMean = Float.NaN
        personalSeiSpread = 0f
        lowVsPersonalStartMs = 0L
        lastSeiValue = -1
    }

    /** Thay ngưỡng mặc định bằng ngưỡng cá nhân, trộn theo độ tin cậy c (0..1). */
    private fun applyPersonalParams(base: HabitBaseline, c: Float) {
        personalThresholdCount = 0
        personalBlend = c

        fun mix(default: Float, personal: Float?): Float {
            if (personal == null) return default
            personalThresholdCount++
            return default * (1f - c) + personal * c
        }

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
            append(
                String.format(
                    Locale.US,
                    ". %d/%d ngưỡng có trộn cá nhân, cá nhân chỉ chiếm %.0f%% nên %d%% vẫn là mặc định" +
                        "; %d ngưỡng chưa có dữ liệu để trộn.",
                    personalThresholdCount,
                    TOTAL_PERSONAL_THRESHOLDS,
                    c * 100f,
                    (1f - c) * 100f,
                    TOTAL_PERSONAL_THRESHOLDS - personalThresholdCount
                )
            )
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
        val summary = habitSession?.toSummary()
        commitHabitSession()
        habitSession = null
        if (summary != null) uploadSessionToCloud(summary)
    }

    /**
     * Kết thúc một phiên là gửi ĐÚNG MỘT bản ghi lên Firestore, chứa số trung bình của cả phiên
     * chứ không phải từng khung hình. Phiên không đủ điều kiện vào hồ sơ vẫn được gửi và bị đánh
     * dấu không đủ điều kiện kèm lý do: dữ liệu loại ra cũng là dữ liệu, giấu đi thì bảng tổng
     * hợp trên đám mây sẽ chỉ còn những phiên đẹp.
     */
    private fun uploadSessionToCloud(s: SessionSummary) {
        if (!FirebaseSync.enabled) return
        val prof = habitProfile ?: return
        var alerts = 0
        for (list in alertOccurrences) alerts += list.size
        val report = SessionReport(
            studentId = prof.id,
            studentName = studentName,
            summary = s,
            alertsTotal = alerts,
            falseAlerts = falseAlertCount,
            csvRows = dataLogger.rows,
            avgFps = fpsEma,
            device = android.os.Build.MODEL,
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "?",
            endedAtWallMs = System.currentTimeMillis(),
            ineligibleReason = prof.ineligibleReason(s)
        )
        FirebaseSync.upload(report) { ok ->
            // Firestore báo kết quả trên luồng chính nên chạm thẳng vào state của HUD được.
            firebaseText = FirebaseSync.statusText()
            if (!ok) {
                status = "Không gửi được lên Firebase (${FirebaseSync.lastError}) - " +
                    "số liệu vẫn còn nguyên trong máy."
            }
        }
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
                    "Hồ sơ cá nhân: %d phiên, %.0f phút - %d/%d ngưỡng riêng, %d mặc định (tin cậy %.0f%%).",
                    n, totalMin, personalThresholdCount, TOTAL_PERSONAL_THRESHOLDS,
                    TOTAL_PERSONAL_THRESHOLDS - personalThresholdCount, prof.confidence() * 100f
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
     *
     * Chỉ dùng MỘT thước đo là khoảng hai khóe mắt, bù triệt phối cảnh bằng 1/cos(yaw).
     * Mống mắt không còn tham gia tính trung bình — nó chỉ được dùng làm cổng loại
     * khung hình nhiễu. Lý do: ở 640x480 và 50 cm, khóe mắt trải ra ~89 px (nhiễu điểm
     * ~1,6%) trong khi mống mắt chỉ ~12 px (~12%). Trước đây hai nguồn được trộn đều
     * trọng số, nên thước nhiễu hơn lại chi phối; và khi mắt nhắm hàm trả về riêng mống
     * mắt, làm cửa sổ trung vị nhảy qua lại giữa hai ước lượng có sai lệch hệ thống khác nhau.
     *
     * `yawAbsDeg` phải là góc quay ĐẦU TUYỆT ĐỐI so với trục ống kính, không phải độ lệch
     * khỏi tư thế nghỉ của em đó. Số hạng cos ở dưới bù đúng cái phần bị ngắn đi vì phối
     * cảnh, mà phần đó chỉ tồn tại khi mặt thật sự quay so với máy ảnh. Trước đây nó nhận
     * `yawRawDev`, nên một em ngồi quay đầu 20 độ vĩnh viễn không được bù gì cả: khoảng cách
     * ước lượng THỪA 6,4% (1,9 cm ở mốc 30 cm), to hơn cả dải trễ 30->32 cm, nghĩa là cảnh
     * báo "quá gần" không bao giờ bắn dù em đó đang ngồi rất gần. Đổi sang góc tuyệt đối còn
     * sửa được một lỗi nữa: quay đầu 30 độ từng làm con số nhảy 15% dù em đó không dời chỗ.
     */
    private fun estimateDistanceCm(
        lm: Landmarks,
        imgW: Int,
        imgH: Int,
        yawAbsDeg: Float,
        eyesOpen: Boolean
    ): Float? {
        val f = focalPx()

        val cornerPx = pixelDistance(lm, LM_OUTER_RIGHT_EYE, LM_OUTER_LEFT_EYE, imgW, imgH)
        if (cornerPx < 1f) return null

        // Cổng loại khung cũng phải xét góc tuyệt đối: chính số hạng 1/cos này phóng đại sai số
        // landmark khi quay sâu, nên góc nào KHÔNG được bù mới là góc cần chặn.
        val yaw = abs(yawAbsDeg)
        if (yaw > DIST_MAX_YAW_DEG) return null

        // Kẹp ở 0.3f: cos(72 độ) ~ 0.31, chặn chia cho 0 nếu ai đó gọi hàm ngoài cổng ở trên.
        val dCorner = EYE_OUTER_WIDTH_CM * f / (cornerPx / cos(yaw / RAD_TO_DEG).coerceAtLeast(0.3f))

        // Đối chiếu bằng mống mắt: hai mắt phải cho kích thước gần nhau và phải
        // khớp khóe mắt trong ~35%. Lệch nghĩa là landmark đang nhiễu -> bỏ khung này.
        if (lm.count >= IRIS_LANDMARKS && eyesOpen) {
            val r = pixelDistance(lm, LM_R_IRIS_H1, LM_R_IRIS_H2, imgW, imgH)
            val l = pixelDistance(lm, LM_L_IRIS_H1, LM_L_IRIS_H2, imgW, imgH)
            val avg = (r + l) / 2f
            if (avg >= IRIS_MIN_PIXELS && abs(r - l) / avg < IRIS_MAX_DISAGREE) {
                val ratio = dCorner / (IRIS_DIAMETER_CM * f / avg)
                if (ratio < IRIS_AGREE_LO || ratio > IRIS_AGREE_HI) return null
            }
        }

        return dCorner
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

        // Ngược sáng (mặt tối hơn nền nhiều) hoặc mặt cháy sáng: landmark mắt kém tin cậy.
        if (enhancer.backlit) {
            q -= 15f
            if (note == "Tốt") note = "Ngược sáng, mặt bị tối"
        }
        if (enhancer.overexposed) {
            q -= 20f
            if (note == "Tốt") note = "Mặt bị cháy sáng"
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
        perclosP70.reset()
        perclosP80.reset()
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
        perclosP70.reset()
        perclosP80.reset()
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
            // updateFps chạy cho MỌI khung được gửi tới, kể cả khung không có mặt, nên khoảng
            // cách lớn hơn WINDOW_STALL_MS nghĩa là máy chẳng nhận được khung nào: app bị đẩy
            // xuống nền, CameraX dừng, có thể hàng chục phút. Đồng hồ phiên (HUD, cột
            // session_s, đồng hồ nhắc nghỉ 20 phút) phải nhảy qua khoảng chết đó, nếu không
            // một lần tắt màn hình giữa giờ ra báo cáo "theo dõi 40 phút" mà chỉ vài phút là
            // có ảnh thật.
            if (dt > WINDOW_STALL_MS && monitoringStartMs != 0L) {
                monitoringStartMs += dt - RATIO_REF_FRAME_MS
            }
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
        // Cờ "quá gần" là kết luận TỪ một phép đo khoảng cách. Mất mặt rồi thì không còn phép
        // đo nào nữa, nên nó phải tắt theo: nếu giữ, mấy khung hình đầu sau khi mặt quay lại
        // chưa kịp có khoảng cách vẫn bị trừ 15 điểm SEI với lý do "đứng quá gần", dù app đang
        // không biết học sinh cách màn hình bao xa.
        distanceAlertActive = false
        // Mẫu hiệu chuẩn khoảng cách chỉ so sánh được với nhau khi khuôn mặt liên tục ở cùng
        // một tư thế. Mất mặt giữa chừng là vứt đi: nếu giữ, trung vị sẽ tính lai hai lần ngồi
        // cách màn hình khác nhau và cho ra một hệ số không mô tả lần nào.
        distCalSamples.clear()
        calWaitStartMs = 0L
        clearPoseState()
        resetFilters()
        perclos.pause()
        perclosP70.pause()
        perclosP80.pause()
        inattention.pause()
        eyeEngine.pause()
    }

    /** Cập nhật hộp bao khuôn mặt (tọa độ chuẩn hóa của ảnh đã xoay), có làm mượt để ROI ổn định. */
    private fun updateFaceBox(lm: Landmarks, nowMs: Long) {
        var x0 = 1f
        var y0 = 1f
        var x1 = 0f
        var y1 = 0f
        for (i in 0 until MIN_LANDMARKS) {
            val x = lm.x(i)
            val y = lm.y(i)
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        if (x1 <= x0 || y1 <= y0) return

        // Nới 35% mỗi phía để ROI gồm cả tóc, cằm, kính và một phần cổ.
        val mw = (x1 - x0) * 0.35f
        val mh = (y1 - y0) * 0.35f
        val nx0 = (x0 - mw).coerceIn(0f, 1f)
        val ny0 = (y0 - mh).coerceIn(0f, 1f)
        val nx1 = (x1 + mw).coerceIn(0f, 1f)
        val ny1 = (y1 + mh).coerceIn(0f, 1f)

        if (faceBoxMs == 0L || nowMs - faceBoxMs > 1_000L) {
            faceBox[0] = nx0
            faceBox[1] = ny0
            faceBox[2] = nx1
            faceBox[3] = ny1
        } else {
            faceBox[0] += 0.35f * (nx0 - faceBox[0])
            faceBox[1] += 0.35f * (ny0 - faceBox[1])
            faceBox[2] += 0.35f * (nx1 - faceBox[2])
            faceBox[3] += 0.35f * (ny1 - faceBox[3])
        }
        faceBoxMs = nowMs
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

        // Mất khuôn mặt không phải là "tập trung bằng 0": giữ "--" thay vì để đồ thị rơi về 0,
        // vì SEI giả thấp này sẽ bị tính vào trung bình/thấp nhất và vào hồ sơ thói quen.
        val sei: Int? = null

        if (loggingEnabled) {
            dataLogger.submit(noFaceCsvLine(nowMs, processingMs, sei))
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
            perclosP70Percent = null,
            perclosP80Percent = null,
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
            eyeDetail = "--",
            imageDetail = imageDetail,
            // Nhắc "mới hiệu chuẩn lệch 20%" là chuyện của người vận hành, nó phải còn hiện trên
            // HUD khi em đó đứng dậy; chỉ phép thử liên tục là hết giá trị khi mất khuôn mặt.
            personHint = if (personHintKind == 2) personHintPlain else null
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
        updateFaceBox(lm, nowMs)
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
        // Ngưỡng đóng phải lấy từ hiệu chuẩn của chính người này (baselineEar x 0,68,
        // thường 0,17-0,22). Hằng số 0,16 cũ thấp hơn ngưỡng thật nên vẫn cho qua
        // những khung mắt đã khép - đúng lúc điểm mốc mống mắt bị sập.
        val eyesOpenForDist = if (calibrated) {
            ear1 > closeThreshold1 && ear2 > closeThreshold2
        } else {
            ear1 > CALIBRATION_MIN_EAR && ear2 > CALIBRATION_MIN_EAR
        }
        val rawUnscaled = estimateDistanceCm(
            lm,
            imgW,
            imgH,
            poseYawAbs,
            eyesOpenForDist
        )

        // Hiệu chuẩn: lấy trung vị của nhiều khung thay vì 1 khung nhiễu.
        if (rawUnscaled != null && distanceCalibrationRequested) {
            if (nowMs - distanceCalRequestedMs > DIST_CAL_WINDOW_MS) {
                // Lời xin hiệu chuẩn quá cũ: DIST_CAL_SAMPLES mẫu thu rải trong
                // DIST_CAL_WINDOW_MS (8 giây), ở những tư thế khác nhau, không còn mô tả "ngồi
                // cách màn hình 50 cm" nữa. Lấy chúng sẽ đổi hệ số khoảng cách sai một cách im
                // lặng rồi mọi con số sau đó trong phiên đều sai theo.
                distanceCalibrationRequested = false
                distCalSamples.clear()
                Log.i(TAG, "Bỏ hiệu chuẩn khoảng cách: quá ${DIST_CAL_WINDOW_MS}ms không đủ mẫu")
            } else {
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
                    // Đổi hệ số là đổi luôn thang đo đang dựng nên cờ "quá gần", nên cờ phải tắt:
                    // nếu giữ, em ngồi đúng 50 cm vẫn bị còi báo tiếp vì cờ học từ thang cũ.
                    distanceAlertActive = false
                    distanceFilter.reset()
                    personGuard.reset()
                    personHintPlain = null
                    personHintKind = 0

                    // Hệ số giờ thuộc về hồ sơ, không thuộc về chiếc điện thoại. Ghi vào
                    // HabitProfile và lưu file luôn: nếu chỉ nhớ trong bộ nhớ thì lần mở app sau
                    // vẫn dùng số của em khác.
                    val prof = habitProfile
                    val storedScale = prof?.distanceScale
                    val calDev = if (storedScale != null && prof != null && prof.hasCalibration()) {
                        PersonGuard.calibrationDeviation(newScale, storedScale)
                    } else {
                        Float.NaN
                    }
                    if (prof != null) {
                        prof.distanceScale = newScale
                        prof.calRawDistanceCm = med
                        prof.calibratedAtMs = System.currentTimeMillis()
                        habitStore.save(prof)
                    }

                    if (calDev.isFinite() && abs(calDev) > PersonGuard.CALIBRATION_REJECT_RATIO) {
                        // Đây là trigger đáng tin nhất: hiệu chuẩn diễn ra ở khoảng cách ĐÃ BIẾT,
                        // nên hệ số nhảy 20% chỉ có thể nghĩa là khuôn mặt khác, hoặc em này ngồi
                        // sai chỗ so với hướng dẫn.
                        personHintPlain = String.format(
                            Locale.US,
                            "Vừa hiệu chuẩn ra hệ số x%.2f, lệch %.0f%% so với hồ sơ %s (x%.2f). " +
                                "Nếu người ngồi là người khác thì phải đổi học sinh, đừng dùng chung ngưỡng.",
                            newScale,
                            calDev * 100f,
                            prof?.name ?: activeStudentId,
                            storedScale ?: Float.NaN
                        )
                        personHintKind = 2
                        Log.w(TAG, "Hiệu chuẩn lệch ${calDev * 100f}% so với hồ sơ ${prof?.id}")
                    }
                    Log.d(TAG, "Hiệu chuẩn khoảng cách: hệ số = $newScale, thô = $med cm")
                }
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

        // ---- Nghi đã đổi người (nhắc người vận hành, không phải cảnh báo mất tập trung) ----
        val suspectPerson = personGuard.observe(
            nowMs,
            rawUnscaled,
            distance,
            habitProfile?.calRawDistanceCm
        )
        val personDev = personGuard.deviation
        if (suspectPerson && personDev.isFinite()) {
            personHintPlain = String.format(
                Locale.US,
                "Khuôn mặt đang ngồi %s chữ ký của hồ sơ %.0f%% trong %d giây ở đúng vị trí tham chiếu. " +
                    "Nếu là người khác thì phải đổi học sinh trước khi theo dõi. (Nhắc vận hành, không tính vào cảnh báo.)",
                if (personDev > 0f) "rộng hơn" else "hẹp hơn",
                abs(personDev) * 100f,
                personGuard.suspectMs(nowMs) / 1000L
            )
            personHintKind = 1
        } else if (personHintKind == 1) {
            personHintPlain = null
            personHintKind = 0
        }

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
        var perclosP70Out: Float? = null
        var perclosP80Out: Float? = null
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
            lastOpenness = eye.openness
            lastSigma = eye.sigma
            lastEyeValid = eye.valid
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

            // ---- PERCLOS: ba ngưỡng song song ----
            if (eye.valid) {
                perclos.record(nowMs, eyesClosed)
                perclosP70.record(nowMs, eye.openness <= PERCLOS_P70_OPEN)
                perclosP80.record(nowMs, eye.openness <= PERCLOS_P80_OPEN)
            } else {
                perclos.pause()
                perclosP70.pause()
                perclosP80.pause()
            }
            // PERCLOS chưa đủ mẫu thì để null: HUD phải hiện "--" chứ không được hiện "0.0 %",
            // vì 0% nghe như "đang tỉnh" trong khi thực ra là chưa đo được gì.
            val perclosSample = perclos.percentOrNull(nowMs)
            val perclosValue = perclosSample ?: 0f
            perclosOut = perclosSample
            perclosP70Out = perclosP70.percentOrNull(nowMs)
            perclosP80Out = perclosP80.percentOrNull(nowMs)

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
            inattentionPct = inattention.percentOrNull(nowMs)

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

            // Giữ lại tổng trước khi trừ: nếu không, bảng phân tách in ra số đã bị trừ hai lần
            // khi có hình phạt, và bị chặn khi phát hiện ảnh đứng yên.
            val weightedSum = raw

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

            // Công khai cách điểm được tính: bảng phân tách chỉ dựng 2,5 lần/giây để không
            // sinh chuỗi mới mỗi khung.
            if (nowMs - lastBreakdownMs >= BREAKDOWN_REFRESH_MS) {
                lastBreakdownMs = nowMs
                val chronicPenalty = when (chronicLevelValue) {
                    2 -> SEI_CHRONIC_PENALTY
                    1 -> SEI_WATCH_PENALTY
                    else -> 0f
                }
                seiBreakdown = buildSeiBreakdown(
                    posture = posture,
                    dist = dist,
                    gaze = gaze,
                    eyes = eyes,
                    blinkRate = blinkRate,
                    expression = expression,
                    weights = seiWeights,
                    closePenalty = if (distanceAlertActive) SEI_TOO_CLOSE_PENALTY else 0f,
                    chronicPenalty = chronicPenalty,
                    frozen = frozen,
                    anomalyCap = SEI_ANOMALY_CAP,
                    weightedSum = weightedSum,
                    finalRaw = raw,
                    sei = seiNow
                )
            }

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
                // `eye.valid` ở đây là chữ "không chớp lỗi" mà bảng Diễn giải đã hứa. Thiếu nó thì
                // một khung mà EyeEngine báo "tôi không kết luận được về mắt" (landmark nhảy, góc quá
                // nghiêng) vẫn được cộng vào validMs, nên tỉ lệ "khung hợp lệ" phình lên đúng lúc
                // tín hiệu kém - và PERCLOS đã được chặn bằng eye.valid từ trước, chỉ còn thời gian là lọt.
                val habitValid = eye.valid && reliable && !frozen && !jerky &&
                        chronicLevelValue < 2 && !drowsyNow && !eyesClosed
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
                    // Chỉ học PERCLOS khi cửa sổ đã đủ mẫu: những giây đầu còn trống mà ghi 0%
                    // thì hồ sơ dạy rằng em này "luôn tỉnh", kéo ngưỡng cảnh báo xuống thấp.
                    perclosSample?.let { hs.observe(HabitFeature.PERCLOS, it) }
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

        if (loggingEnabled) {
            val b = csvBuf
            b.setLength(0)
            b.append(nowMs).append(',')                            // 1  t_ms
            b.append('1').append(',')                              // 2  face
            b.num(ear1)                                            // 3
            b.num(ear2)                                            // 4
            b.num(lastOpenness)                                    // 5
            b.num(lastSigma)                                       // 6
            b.num(if (lastEyeValid) 1f else 0f)                    // 7
            b.num(if (calibrated) baselineEar1 else Float.NaN)     // 8
            b.num(if (calibrated) baselineEar2 else Float.NaN)     // 9
            b.num(if (calibrated) closeThreshold1 else Float.NaN)  // 10
            b.num(if (calibrated) closeThreshold2 else Float.NaN)  // 11
            b.num(yawDeg)                                          // 12
            b.num(pitchDeg)                                        // 13
            b.num(rollDeg)                                         // 14
            b.num(gazeH)                                           // 15
            b.num(gazeV)                                           // 16
            b.num(mar)                                             // 17
            b.num(bfr)                                             // 18
            b.num(rawUnscaled)                                     // 19
            b.num(distance)                                        // 20
            b.num(distanceScale)                                   // 21
            b.num(focalPx())                                       // 22
            b.num(perclosOut)                                      // 23
            b.num(perclosP70Out)                                   // 24
            b.num(perclosP80Out)                                   // 25
            b.num(bpm)                                             // 26
            b.num(averageBlinkMs())                                // 27
            b.num(inattentionPct)                                  // 28
            b.num(sei)                                             // 29
            b.num(fatigueOut)                                      // 30
            b.num(signalQuality.toFloat())                         // 31
            b.append(alertCode).append(',')                        // 32
            b.append(chronicLevelValue).append(',')                // 33
            b.append(sessionSeconds(nowMs)).append(',')            // 34
            b.num(fpsEma)                                          // 35
            b.num(processingMs)                                    // 36
            if (lumaMean.isFinite()) {
                b.append(String.format(Locale.US, "%.2f", lumaMean))
            }
            // Cột cuối không có dấu phẩy nối tiếp; nếu độ sáng không hữu hạn thì để trống.
            dataLogger.submit(b.toString())
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
            perclosP70Percent = perclosP70Out,
            perclosP80Percent = perclosP80Out,
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
            eyeDetail = eyeDetail,
            imageDetail = imageDetail,
            personHint = personHintPlain
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
        personHintText = s.personHint

        bpmText = s.bpm?.let {
            String.format(Locale.US, "%.1f", it)
        } ?: "--"

        perclosText = s.perclosPercent?.let {
            String.format(Locale.US, "%.1f %%", it)
        } ?: "--"

        perclosP70Text = s.perclosP70Percent?.let {
            String.format(Locale.US, "%.1f %%", it)
        } ?: "--"

        perclosP80Text = s.perclosP80Percent?.let {
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
        imageDetailText = s.imageDetail
        seiBreakdownText = seiBreakdown
        refreshPanelExtras()

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

        // Dòng này chạy theo nhịp 120 ms: chỉ được thay chữ khi không còn thông báo nào
        // đang được giữ, nếu không nó xoá sạch mọi cảnh báo ngắn vừa in ra.
        if (SystemClock.elapsedRealtime() >= statusHoldUntilMs) {
            val detection = if (s.faceDetected) {
                "Đã phát hiện khuôn mặt."
            } else {
                "Chưa phát hiện khuôn mặt."
            }
            if (detection != status) {
                status = detection
            }
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

        addLatencySample(s.processingMs)
        recordTimelineEvent(s)
        if (preflightRunning) {
            samplePreflight(s)
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

        // Xử lý ảnh mức thấp trên kênh sáng (đo ánh sáng ở vùng mặt, CLAHE thích ứng khi thiếu sáng).
        // lumaMean là độ sáng GỐC (trước khi tăng cường) nên phản ánh đúng điều kiện chiếu sáng thật.
        val frameMs = SystemClock.elapsedRealtime()
        val boxFresh = faceBoxMs != 0L && frameMs - faceBoxMs < 1_000L
        lumaMean = enhancer.process(
            yBytes, yLast, yRowStride, yPixelStride, width, height,
            image.imageInfo.rotationDegrees,
            faceBox, boxFresh, frameMs, enhanceMode == 0
        )
        if (frameMs - lastImageDetailMs >= 500L) {
            lastImageDetailMs = frameMs
            imageDetail = enhancer.describe()
        }

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
        // Lưu phiên đang học khi rời màn hình (chạy trên cameraExecutor để không tranh chấp dữ
        // liệu), và gửi luôn bản tổng kết: với người dùng, tắt màn hình mới là lúc một buổi đo
        // kết thúc - endHabitSession chỉ chạy khi đổi học sinh hoặc hiệu chuẩn lại. Hai lần gửi
        // cùng một phiên vẫn chỉ thành MỘT bản ghi trên Firestore vì tên tài liệu là tất định.
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            try {
                cameraExecutor.execute {
                    val session = habitSession
                    commitHabitSession()
                    if (session != null) uploadSessionToCloud(session.toSummary())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không lưu được phiên khi dừng", e)
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        previewView = null

        // Ba hàng chục dòng cuối nằm trong bộ đệm của BufferedWriter. Nếu app bị huỷ mà không
        // stop() thì số liệu của phiên vừa đo mất luôn phần đó - đúng phần quan trọng nhất
        // vì nó là những phút cuối phiên.
        if (loggingEnabled) {
            dataLogger.stop()
            loggingEnabled = false
        }
        cancelPreflightWatchdog()

        cameraProvider?.unbindAll()
        cameraProvider = null

        if (::cameraExecutor.isInitialized) {
            try {
                cameraExecutor.execute { endHabitSession() }
                // Đóng model bằng một task xếp CUỐI hàng đợi, không đóng ngay trên UI thread.
                // awaitTermination chỉ chờ hữu hạn, trong khi một khung nặng có thể lâu hơn; nếu
                // close() chạy lúc native vẫn đang detect thì app nhận SIGSEGV - nghĩa là crash
                // đúng lúc quay sang màn hình khác trên sân khấu. Executor một luồng nên task này
                // chắc chắn chạy sau khung cuối cùng.
                cameraExecutor.execute {
                    try {
                        faceLandmarker?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Không đóng được FaceLandmarker", e)
                    }
                    faceLandmarker = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không xếp được việc đóng phiên/model", e)
            }
            cameraExecutor.shutdown()
            try {
                if (!cameraExecutor.awaitTermination(1_200, TimeUnit.MILLISECONDS)) {
                    // Vẫn còn khung đang chạy sau 1,2 giây: bỏ qua việc đóng model (để process tự
                    // giải phóng) còn hơn đóng khi native đang dùng - thủng một lần dùng model
                    // chỉ tốn RAM, còn SIGSEGV là mất cả phiên đang ghi CSV.
                    cameraExecutor.shutdownNow()
                    Log.w(TAG, "CameraExecutor chưa xong, để model đóng khi process kết thúc")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        try {
            toneGenerator?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Không giải phóng được ToneGenerator", e)
        }
        toneGenerator = null

        super.onDestroy()
    }

    // ==================== GHI DỮ LIỆU NGHIÊN CỨU ====================

    /** Một cột số trong CSV. Ô trống nghĩa là "không đo được", khác với 0. */
    private fun StringBuilder.num(v: Float?) {
        // isFinite chứ không phải !isNaN: "Infinity" trong ô CSV không chứa dấu phẩy nên lọt qua
        // kiểm tra số cột, và Excel đọc thành chữ - cả cột trung bình ra lỗi mà không báo.
        if (v != null && v.isFinite()) {
            append(String.format(Locale.US, "%.4f", v))
        }
        append(',')
    }

    private fun StringBuilder.num(v: Int?) {
        if (v != null) {
            append(v)
        }
        append(',')
    }

    /**
     * Bỏ dấu phẩy, nháy kép và ký tự xuống dòng khỏi chữ, để không phá cấu trúc CSV.
     * Xuống dòng quan trọng như dấu phẩy: một dòng nhắn có `\n` là file bị tách đôi, nửa sau
     * Excel đọc thành một bản ghi rag với số cột sai - và không có dấu hiệu nào báo lỗi đó.
     */
    private fun csvSafe(text: String?): String =
        (text ?: "")
            .replace(',', ';')
            .replace('"', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')

    private fun toggleCsvLogging() {
        if (loggingEnabled) {
            dataLogger.stop()
            loggingEnabled = false
            val dropped = dataLogger.droppedRows
            val bad = dataLogger.malformedRows
            loggingText = String.format(Locale.US, "Ghi CSV: tắt (%d dòng)", dataLogger.rows)
            status = when {
                bad > 0L -> String.format(
                    Locale.US,
                    "Đã ghi %d dòng, NGỪNG DÙNG FILE NÀY: %d dòng lệch số cột, %d mẫu mất vì ghi không kịp",
                    dataLogger.rows, bad, dropped
                )
                dropped > 0L -> String.format(
                    Locale.US,
                    "Đã ghi %d dòng, mất %d mẫu vì ghi không kịp",
                    dataLogger.rows, dropped
                )
                else -> String.format(Locale.US, "Đã ghi %d dòng, không mất mẫu", dataLogger.rows)
            }
            return
        }
        val path = dataLogger.start(applicationContext, loggerMeta())
        if (path == null) {
            loggingText = "Ghi CSV: không mở được file"
            return
        }
        loggingEnabled = true
        loggingText = "Ghi CSV: BẬT"
        status = "Đang ghi $path"
    }

    /**
     * Thông tin môi trường của phiên ghi, viết riêng ra meta.txt vì CSV phải sạch
     * để máy đọc được. Thiếu mấy con số này thì số liệu không tái lập được.
     */
    private fun loggerMeta(): String = buildString {
        append("device=").append(android.os.Build.MODEL).append('\n')
        append("android=").append(android.os.Build.VERSION.RELEASE).append('\n')
        append("app=FocusChangeAI\n")
        append("student=").append(csvSafe(studentName)).append('\n')
        append("started=").append(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(java.util.Date())
        ).append('\n')
        append("calibrated=").append(calibrated).append('\n')
        append("baseline_ear1=").append(baselineEar1).append('\n')
        append("baseline_ear2=").append(baselineEar2).append('\n')
        append("distance_scale=").append(distanceScale).append('\n')
        append("stream=").append(streamW).append('x').append(streamH).append('\n')
    }

    /**
     * Ghi lại một cảnh báo mà người bị theo dõi cho là sai, kèm toàn bộ chỉ số của
     * khung hình đó. Đây là nguồn dữ liệu thật rẻ nhất để chỉnh ngưỡng.
     */
    private fun logFalseAlert(): Boolean {
        val s = lastSnapshot ?: return false
        val b = StringBuilder(200)
        b.append(System.currentTimeMillis()).append(',')
        b.append(s.alertCode).append(',')
        b.append(csvSafe(s.alertMessage)).append(',')
        b.num(s.sei)
        b.num(s.perclosPercent)
        b.num(s.perclosP80Percent)
        b.num(s.bpm)
        b.num(s.ear1)
        b.num(s.ear2)
        b.num(s.yawDeg)
        b.num(s.pitchDeg)
        b.num(s.distanceCm)
        b.append(s.signalQuality).append(',')
        b.append(s.sessionSeconds)
        return dataLogger.appendFalseAlert(applicationContext, b.toString())
    }

    /** Dòng CSV cho khung hình mất mặt: chỉ thì giờ, sei và môi trường còn nghĩa. */
    private fun noFaceCsvLine(nowMs: Long, processingMs: Float, seiValue: Int?): String =
        buildNoFaceCsvRow(
            columns = dataLogger.columnNames,
            out = csvBuf,
            nowMs = nowMs,
            sei = seiValue,
            chronic = chronicLevelValue,
            sessionSec = sessionSeconds(nowMs),
            fps = fpsEma,
            procMs = processingMs,
            luma = lumaMean
        ).toString()

    // Bảng phân tách SEI và bộ đọc dòng cuối file nằm trong FocusCore.kt (mã thuần, test
    // được trên máy tính bằng HabitTimingTest).

    // ==================== BÀI KIỂM TRA TRƯỚC BUỔI TRÌNH DIỄN ====================

    /**
     * Bấm một lần: đặt điện thoại đúng chỗ sẽ đặt trên bàn, ngồi yên 20 giây.
     * App đo đúng những thứ hay hỏng trên sân khấu (máy chậm, thiếu sáng, chưa hiệu chuẩn,
     * hết pin, không ghi được file) thay vì đoán.
     */
    private fun startPreflight() {
        // Bấm liên tiếp không được khởi động lại phép đo: mỗi lần bấm xoá sạch 20 giây đã tích
        // luỹ, và trên sân khấu thì người bấm chỉ thấy "Đang đo..." mãi không ra kết quả.
        if (preflightRunning) {
            return
        }
        pfStartMs = SystemClock.elapsedRealtime()
        pfFrames = 0
        pfFpsSum = 0f
        pfFpsMin = Float.MAX_VALUE
        pfProcSum = 0f
        pfProcMax = 0f
        pfQualSum = 0
        pfQualN = 0
        pfQualMin = 100
        pfNoFaceFrames = 0
        pfNoDistFrames = 0
        pfAlertFrames = 0
        pfSeiSum = 0L
        pfSeiN = 0L
        pfPerclosReady = false
        pfCalibrated = false
        preflightProgress = 0
        preflightRunning = true
        preflightLines = emptyList()
        preflightVerdict = "Đang đo trong 20 giây - cứ ngồi thẳng, nhìn màn hình..."

        // Máy đếm chỉ tiến khi có khung hình mới. Nếu camera chết giữa chừng thì trọng tài
        // này chốt kết quả, để bảng không treo chữ "Đang đo..." ngay trên sân khấu.
        cancelPreflightWatchdog()
        val watchdog = Runnable {
            preflightWatchdog = null
            if (preflightRunning) {
                finishPreflight()
            }
        }
        preflightWatchdog = watchdog
        window.decorView.postDelayed(watchdog, PREFLIGHT_MS + 3_000L)
    }

    private fun cancelPreflightWatchdog() {
        val w = preflightWatchdog ?: return
        preflightWatchdog = null
        window.decorView.removeCallbacks(w)
    }

    private fun clearPreflight() {
        cancelPreflightWatchdog()
        preflightRunning = false
        preflightProgress = 0
        preflightLines = emptyList()
        preflightVerdict = ""
    }

    private fun samplePreflight(s: HudSnapshot) {
        val elapsed = SystemClock.elapsedRealtime() - pfStartMs
        if (elapsed >= PREFLIGHT_MS) {
            finishPreflight()
            return
        }

        pfFrames++
        pfFpsSum += s.fps
        if (s.fps < pfFpsMin) {
            pfFpsMin = s.fps
        }
        pfProcSum += s.processingMs
        if (s.processingMs > pfProcMax) {
            pfProcMax = s.processingMs
        }
        // Chất lượng tín hiệu chỉ có nghĩa khi thấy khuôn mặt; khung mất mặt được gắn 0 điểm,
        // tính gộp vào thì chỉ cần quay đi vài giây là bài kiểm tra báo "thiếu sáng" sai.
        if (s.faceDetected) {
            pfQualSum += s.signalQuality
            pfQualN++
            if (s.signalQuality < pfQualMin) {
                pfQualMin = s.signalQuality
            }
        }
        if (!s.faceDetected) {
            pfNoFaceFrames++
        }
        if (s.distanceCm == null) {
            pfNoDistFrames++
        }
        if (s.alertCode > 0) {
            pfAlertFrames++
        }
        val sei = s.sei
        if (sei != null) {
            pfSeiSum += sei.toLong()
            pfSeiN++
        }
        // null nghĩa là cửa sổ chưa đủ mẫu; 0% thật (ngồi tỉnh, mở mắt to) cũng là đã đo được.
        if (s.perclosPercent != null) {
            pfPerclosReady = true
        }
        if (s.calibrated) {
            pfCalibrated = true
        }

        val pct = ((elapsed * 100L) / PREFLIGHT_MS).toInt().coerceIn(0, 100)
        if (pct != preflightProgress) {
            preflightProgress = pct
        }
    }

    /** Một dòng kết quả: kind = 0 ổn, 1 cần lưu ý, 2 không đạt. */
    private fun preflightRow(kind: Int, text: String): String {
        val mark = when (kind) {
            0 -> "OK  "
            1 -> "!!  "
            else -> "XX  "
        }
        return "$mark$text"
    }

    private fun finishPreflight() {
        cancelPreflightWatchdog()
        preflightRunning = false
        preflightProgress = 100

        val rows = ArrayList<String>()
        var fails = 0
        var warns = 0

        fun add(kind: Int, text: String) {
            rows.add(preflightRow(kind, text))
            if (kind == 2) fails++ else if (kind == 1) warns++
        }

        // Phần đo được trong 20 giây vừa rồi.
        if (pfFrames == 0) {
            add(2, "Không nhận được khung hình nào - camera chưa chạy hoặc chưa được cấp quyền.")
        } else {
            val avgFps = pfFpsSum / pfFrames
            val lowFps = if (pfFpsMin == Float.MAX_VALUE) 0f else pfFpsMin
            add(
                when {
                    avgFps < PREFLIGHT_FPS_FAIL -> 2
                    avgFps < PREFLIGHT_FPS_WARN -> 1
                    else -> 0
                },
                String.format(
                    Locale.US,
                    "Nhịp khung hình trung bình %.1f FPS (thấp nhất %.1f). Dưới %.0f là không theo kịp mắt.",
                    avgFps, lowFps, PREFLIGHT_FPS_FAIL
                )
            )

            val avgProc = pfProcSum / pfFrames
            add(
                when {
                    pfProcMax > PREFLIGHT_LATENCY_WARN_MS * 2 -> 2
                    pfProcMax > PREFLIGHT_LATENCY_WARN_MS -> 1
                    else -> 0
                },
                String.format(
                    Locale.US,
                    "Độ trễ xử lý: trung bình %.1f ms, cao nhất %.1f ms trên %d khung.",
                    avgProc, pfProcMax, pfFrames
                )
            )

            if (pfQualN == 0) {
                add(2, "Không có khung nào thấy khuôn mặt - chưa đo được chất lượng tín hiệu.")
            } else {
                val avgQual = pfQualSum / pfQualN
                add(
                    when {
                        avgQual < QUALITY_OK -> 2
                        avgQual < PREFLIGHT_QUALITY_WARN -> 1
                        else -> 0
                    },
                    String.format(
                        Locale.US,
                        "Chất lượng tín hiệu trung bình %d/100 (thấp nhất %d) trên %d khung có mặt. Thấp thường do thiếu sáng hoặc khuôn mặt quá nhỏ.",
                        avgQual, pfQualMin, pfQualN
                    )
                )
            }

            val faceFrames = pfFrames - pfNoFaceFrames
            val faceRatio = faceFrames.toFloat() / pfFrames
            add(
                when {
                    faceRatio < 0.5f -> 2
                    faceRatio < PREFLIGHT_FACE_RATIO_MIN -> 1
                    else -> 0
                },
                String.format(
                    Locale.US,
                    "Giữ được khuôn mặt trong %.0f%% số khung (%d/%d). Mất khuôn mặt không bị tính thành mất tập trung.",
                    faceRatio * 100f, faceFrames, pfFrames
                )
            )

            val distRatio = (pfFrames - pfNoDistFrames).toFloat() / pfFrames
            add(
                if (distRatio < 0.6f) 1 else 0,
                String.format(Locale.US, "Đo được khoảng cách ở %.0f%% số khung.", distRatio * 100f)
            )

            add(
                if (pfPerclosReady) 0 else 1,
                if (pfPerclosReady) {
                    "PERCLOS đã đủ mẫu để hiển thị."
                } else {
                    "PERCLOS chưa đủ mẫu số - cần nhịp khung đều và trên 3 giây dữ liệu."
                }
            )

            add(
                if (pfCalibrated) 0 else 2,
                if (pfCalibrated) {
                    "Đường cơ sở mắt đã hiệu chuẩn xong."
                } else {
                    "Chưa hiệu chuẩn được đường cơ sở mắt - ngồi thẳng, đủ sáng và giữ im vài giây ngay đầu phiên."
                }
            )

            if (pfSeiN > 0) {
                add(
                    0,
                    String.format(
                        Locale.US,
                        "SEI trong bài kiểm tra: trung bình %d trên %d khung có điểm.",
                        pfSeiSum / pfSeiN, pfSeiN
                    )
                )
            }
            if (pfAlertFrames > 0) {
                add(
                    1,
                    String.format(
                        Locale.US,
                        "Có %d khung bật cảnh báo trong lúc ngồi yên - kiểm tra lại tư thế hoặc ánh sáng.",
                        pfAlertFrames
                    )
                )
            }
        }

        // Phần môi trường: kiểm lại tại thời điểm kết thúc. Những mục này cũng phải được
        // tính vào kết luận - nếu chỉ in ra thì có thể đỏ "chưa cấp quyền camera" ngay dưới
        // dòng "SẴN SÀNG TRÌNH DIỄN".
        add(0, "---- Kiểm tra máy và thiết bị ----")
        preflightStaticChecks { kind, text -> add(kind, text) }

        preflightLines = rows
        preflightVerdict = when {
            fails > 0 -> "CHƯA SẴN SÀNG: $fails mục không đạt, $warns mục cần lưu ý."
            warns > 0 -> "ĐƯỢC, nhưng còn $warns mục cần lưu ý ở trên."
            else -> "SẴN SÀNG TRÌNH DIỄN.\nMáy này đo đủ nhanh và đủ sáng cho 20 giây vừa rồi."
        }
        status = "Kiểm tra trước trình diễn: ${if (fails > 0) "$fails mục không đạt" else "đạt"}."
    }

    /** Những mục không phụ thuộc nhịp khung hình: quyền, model, chỗ trống, pin, âm báo. */
    private fun preflightStaticChecks(emit: (Int, String) -> Unit) {
        var kind = 0

        fun row(text: String) {
            emit(kind, text)
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        kind = if (granted) 0 else 2
        row(if (granted) "Quyền camera đã được cấp." else "CHƯA cấp quyền camera - app chỉ hiện khung đen.")

        kind = if (faceLandmarker != null) 0 else 2
        row(
            if (faceLandmarker != null) {
                "Model FaceLandmarker đã nạp (478 điểm mốc)."
            } else {
                "Model chưa nạp được - kiểm tra face_landmarker.task trong tài nguyên."
            }
        )

        kind = if (cameraStarted) 0 else 2
        row(
            if (cameraStarted) {
                "Pipeline camera đang chạy, có số liệu mới."
            } else {
                "Camera chưa khởi động nên không đo được gì - kiểm tra quyền hoặc mở lại app."
            }
        )

        val dir = getExternalFilesDir(null)
        kind = 0
        if (dir == null) {
            kind = 2
            row("Không lấy được thư mục lưu riêng của app - không ghi được CSV.")
        } else {
            val probe = File(dir, "preflight.tmp")
            val freeMb = dir.usableSpace / (1024L * 1024L)
            kind = try {
                probe.writeText("ok")
                probe.delete()
                if (freeMb < 100L) 1 else 0
            } catch (e: Exception) {
                2
            }
            row(
                String.format(
                    Locale.US,
                    "Thư mục dữ liệu %s: còn %d MB, ghi được bình thường.",
                    dir.name, freeMb
                )
            )
        }

        val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val battery = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        when {
            battery < 0 -> kind = 1
            battery < 10 -> kind = 2
            battery < PREFLIGHT_BATTERY_MIN -> kind = 1
            else -> kind = 0
        }
        row(
            if (battery < 0) {
                "Không đọc được mức pin - tự kiểm tra trước khi lên sân khấu."
            } else {
                String.format(Locale.US, "Mức pin còn lại: %d%%.", battery)
            }
        )

        kind = if (soundMode > 0) 0 else 1
        row(
            if (soundMode > 0) {
                "Âm báo đang bật (${if (soundMode == 2) "đầy đủ" else "chỉ quan trọng"}) - phát thử một tiếng để chắc chắn loa không tắt."
            } else {
                "Âm báo đang TẮT - giám khảo sẽ không nghe thấy gì nếu bạn định demo cảnh báo bằng tiếng."
            }
        )

        kind = 0
        row(
            String.format(
                Locale.US,
                "Tăng cường ảnh: %s | Độ nhạy: %s | Âm báo mức %d.",
                if (enhanceMode == 0) "tự động" else "tắt",
                when (sensLevel) { 0 -> "thấp"; 2 -> "cao"; else -> "vừa" },
                soundMode
            )
        )
    }

    // ==================== HISTOGRAM ĐỘ TRỄ ====================

    /** Đếm mẫu vào 12 ngăn x 15 ms, giữ đúng 240 khung gần nhất. */
    private fun addLatencySample(ms: Float) {
        if (!ms.isFinite() || ms < 0f) {
            return
        }
        val bin = (ms / LATENCY_BIN_MS).toInt().coerceIn(0, LATENCY_BINS - 1)
        if (latencyFilled == LATENCY_SAMPLES) {
            // Vòng tròn đã đầy: mẫu nằm ở vị trí latencyWriteIdx chính là mẫu cũ nhất, phải
            // trừ đúng nó. Trừ bừa ở ngăn nào cũng được thì ngăn thấp luôn đầy sớm nên luôn
            // bị xoá trước, histogram tự lệch dần sang phía chậm.
            latencyCounts[latencyBins[latencyWriteIdx]]--
        } else {
            latencyFilled++
        }
        latencyCounts[bin]++
        latencyBins[latencyWriteIdx] = bin
        latencyWriteIdx = (latencyWriteIdx + 1) % LATENCY_SAMPLES
    }

    private fun latencySnapshot(): Pair<List<Int>, String> {
        val bars = ArrayList<Int>(LATENCY_BINS)
        var maxCount = 1
        for (i in 0 until LATENCY_BINS) {
            if (latencyCounts[i] > maxCount) maxCount = latencyCounts[i]
        }
        for (i in 0 until LATENCY_BINS) {
            bars.add(latencyCounts[i] * 100 / maxCount)
        }
        var seen = 0
        var weighted = 0f
        for (i in 0 until LATENCY_BINS) {
            seen += latencyCounts[i]
            weighted += latencyCounts[i] * (i + 0.5f) * LATENCY_BIN_MS
        }
        val summary = if (seen == 0) {
            "--"
        } else {
            String.format(
                Locale.US,
                "%d khung | trung bình %.0f ms | mỗi ngăn %.0f ms | chậm nhất > %.0f ms",
                seen,
                weighted / seen,
                LATENCY_BIN_MS,
                LATENCY_BINS * LATENCY_BIN_MS
            )
        }
        return bars to summary
    }

    // ==================== NGƯỠNG HIỆN HÀNH ====================

    /**
     * Liệt kê từng con số đang chi phối kết quả, kèm nguồn (mặc định hay hồ sơ riêng) và
     * tình trạng kiểm chứng. Viết ra để không ai - kể cả tôi - nói quá được độ tin của nó.
     */
    private fun buildThresholdRows(): String = buildString {
        fun line(name: String, current: Float, default: Float, note: String, unit: String = "") {
            // "hồ sơ riêng" một mình là chữ dễ gây hiểu nhầm nhất trong toàn bộ bảng này: ngưỡng
            // trộn thì không phải 100% số của em đó, nên phải in kèm đúng phần trăm trọng số.
            val src = when {
                current == default -> "mặc định"
                personalBlend <= 0f -> "hồ sơ riêng"
                else -> String.format(Locale.US, "trộn %.0f%% hồ sơ", personalBlend * 100f)
            }
            appendLine(
                String.format(
                    Locale.US,
                    "%-26s %7.2f %-4s [%s] - %s",
                    name, current, unit, src, note
                )
            )
        }

        appendLine(
            String.format(
                Locale.US,
                "Ngưỡng đang dùng cho $studentName (hồ sơ riêng đang chiếm %.0f%%):",
                personalBlend * 100f
            )
        )
        line("Mắt gần đóng (cột perclos_p60)", thrPerclos, PERCLOS_ALERT, "trên mức này thì báo buồn ngủ", "%")
        line("Ngang - mất tập trung", thrLookYaw, LOOK_AWAY_YAW_DEG, "góc yaw duy trì 2 giây", "°")
        line("Dọc - mất tập trung", thrLookPitch, LOOK_AWAY_PITCH_DEG, "góc pitch duy trì 2 giây", "°")
        line("Lệch nhìn ngang", thrGazeH, GAZE_H_DRIFT, "tỉ lệ trên nửa bề rộng mống mắt")
        line("Lệch nhìn dọc", thrGazeV, GAZE_V_DRIFT, "tỉ lệ trên nửa bề cao mống mắt")
        line("Chớp dưới mức", thrBlinkLow, LOW_BLINK_BPM, "bpm", "/phút")
        line("Chớp trên mức", thrBlinkHigh, HIGH_BLINK_BPM, "bpm", "/phút")
        line("Ngáp (MAR)", thrMar, MAR_YAWN, "độ mở miệng so với bề rộng")
        line("Mất tập trung 60 s", INATTENTION_RATIO_ALERT, INATTENTION_RATIO_ALERT, "tỉ lệ thời gian ngoài tầm nhìn", "%")

        appendLine()
        appendLine("Ngưỡng cố định (không đổi theo hồ sơ):")
        appendLine(String.format(Locale.US, "  Nhắm mắt liên tục %.2f s thì báo buồn ngủ", DROWSY_CLOSURE_MS / 1000f))
        appendLine(String.format(Locale.US, "  P70/P80: độ mở <= %.2f / %.2f so với đường cơ sở", PERCLOS_P70_OPEN, PERCLOS_P80_OPEN))
        appendLine(String.format(Locale.US, "  Ngưỡng nheo mắt %.0f%%, lệch hai mắt %.0f%%", EyeEngine.SQUINT_ALERT_PCT, EyeEngine.ASYM_ALERT_PCT))
        // Đổi số khung ra giây phải dùng nhịp thật của máy. Giả sử 30 FPS thì trên máy chạy
        // 18 FPS sẽ ghi "2 s" trong khi học sinh phải ngồi 3,3 s.
        val calFps = fpsEma
        if (calFps > 1f) {
            appendLine(
                String.format(
                    Locale.US,
                    "  Giữ khuôn mặt %d khung (≈ %.1f s ở nhịp %.0f FPS hiện tại) để hoàn tất hiệu chuẩn",
                    CALIBRATION_FRAMES, CALIBRATION_FRAMES / calFps, calFps
                )
            )
        } else {
            appendLine("  Giữ khuôn mặt $CALIBRATION_FRAMES khung để hoàn tất hiệu chuẩn (chưa có nhịp khung hình để đổi ra giây).")
        }
        appendLine(
            String.format(
                Locale.US,
                "  Quét lại mỗi %d ms, trần dt %d ms, khoảng dừng %d ms",
                UI_TEXT_REFRESH_MS, RATIO_REF_FRAME_MS, WINDOW_STALL_MS
            )
        )
        appendLine()
        appendLine("Cửa sổ PERCLOS: $RATIO_BUCKETS bucket x 1 giây, đủ $RATIO_MIN_SAMPLE_MS ms mẫu mới hiện số.")
        appendLine("Trọng số SEI: tư thế ${(W_POSTURE * 100).toInt()}% | khoảng cách ${(W_DISTANCE * 100).toInt()}% | hướng nhìn ${(W_GAZE * 100).toInt()}% | mắt ${(W_EYES * 100).toInt()}% | chớp ${(W_BPM * 100).toInt()}% | biểu cảm ${(W_EXPRESSION * 100).toInt()}%")
        val personal = personalThresholdCount
        appendLine()
        appendLine(
            String.format(
                Locale.US,
                "%d/%d ngưỡng đang lấy từ hồ sơ riêng của bạn; %d vẫn là mặc định chung cho mọi người.",
                personal, TOTAL_PERSONAL_THRESHOLDS, TOTAL_PERSONAL_THRESHOLDS - personal
            )
        )
        append("Độ tin của hồ sơ xem ở dòng 'Hồ sơ cá nhân' phía trên. Chưa ngưỡng nào được đối chiếu với quan sát của giáo viên.")
    }

    /**
     * Làm mới các số chỉ sống trong bảng phụ. Gọi từ applyHudText nên đã được kẹp nhịp
     * ~8 lần/giây; bảng ngưỡng và histogram chỉ dựng khi đang xem tab "Chỉ số" để không
     * tốn công cho màn hình đang bị che.
     */
    private fun refreshPanelExtras() {
        if (panelTab != 2) {
            return
        }
        // applyHudText chạy ~8 lần/giây; bảng chữ dài chỉ cần một lần/giây.
        val nowUi = SystemClock.elapsedRealtime()
        if (nowUi - lastThresholdMs < THRESHOLD_REFRESH_MS) {
            return
        }
        lastThresholdMs = nowUi

        val snapshot = latencySnapshot()
        latencyBars = snapshot.first
        latencySummary = snapshot.second
        timelineMarks = buildTimelineMarks()
        thresholdRows = buildThresholdRows()
    }

    /**
     * Ghi lại sự kiện theo giây. Mỗi loại chỉ một điểm trong một giây: nếu không, một cơn
     * buồn ngủ kéo dài sẽ vẽ 1800 vạch chồng lên nhau và biểu đồ thành một mảng màu.
     */
    private fun recordTimelineEvent(s: HudSnapshot) {
        val sec = SystemClock.elapsedRealtime() / 1000L
        pushEvent(sec, TL_BAND_ALERT, s.alertCode > 0)
        pushEvent(sec, TL_BAND_CHRONIC, s.chronicLevel >= 2)
        pushEvent(sec, TL_BAND_NO_FACE, !s.faceDetected)
        pushEvent(sec, TL_BAND_BLINK, s.blinkDetected)
        pushEvent(sec, TL_BAND_TOO_CLOSE, s.tooClose)
    }

    private fun pushEvent(sec: Long, band: Int, active: Boolean) {
        if (!active || timelineLastSec[band] == sec) {
            return
        }
        timelineLastSec[band] = sec
        timelineEvents.addLast(TimedEvent(sec, band))
        while (timelineEvents.size > TL_MAX_EVENTS) {
            timelineEvents.removeFirst()
        }
    }

    /** Đổi sự kiện thành vị trí 0..1 trên trục thời gian, bỏ những gì đã ngoài cửa sổ. */
    private fun buildTimelineMarks(): List<Pair<Float, Int>> {
        val nowSec = SystemClock.elapsedRealtime() / 1000L
        val out = ArrayList<Pair<Float, Int>>()
        for (e in timelineEvents) {
            val age = nowSec - e.second
            if (age in 0L until TL_WINDOW_SEC) {
                out.add((1f - age.toFloat() / TL_WINDOW_SEC.toFloat()) to e.band)
            }
        }
        return out
    }

    // ==================== XEM DỮ LIỆU THÔ ====================

    /**
     * Đọc vài dòng cuối của file ghi. Việc này đụng vào flash nên làm trên thread riêng rồi
     * đưa kết quả về UI thread; nếu đọc ngay trong khung vẽ thì mỗi lần bấm là máy khựng.
     */
    private fun toggleCsvTail() {
        showCsvTail = !showCsvTail
        if (showCsvTail) {
            readCsvTail()
        }
    }

    private fun readCsvTail() {
        if (csvReading) {
            return
        }
        csvReading = true
        csvTailNote = "Đang đọc file..."
        val ctx = applicationContext
        Thread {
            val result: Pair<List<String>, String> = try {
                val f = dataLogger.framesFile(ctx)
                when {
                    f == null -> emptyList<String>() to "Không xác định được thư mục dữ liệu của app."
                    !f.exists() -> emptyList<String>() to
                        "Chưa có file nào. Bấm 'Ghi CSV đối chiếu', ngồi yên một phút rồi bấm lại nút này."
                    else -> {
                        val lines = readTailLines(f, CSV_TAIL_LINES)
                        when (lines) {
                            // null = file có tồn tại nhưng đụng vào không được. Nói thẳng là không
                            // đọc được còn hơn để bảng trống trơn - dễ bị hiểu thành "chưa ghi gì".
                            null -> emptyList<String>() to
                                "Không đọc được ${f.name}. File có thể vừa bị xoá hoặc quyền truy cập " +
                                "bị thu hồi giữa chừng. Thử tắt bật lại nút ghi."
                            else -> {
                                val header = dataLogger.header
                                val note = String.format(
                                    Locale.US,
                                    "%s - %d byte, %d dòng trên đĩa, bộ đếm trong máy %d dòng.",
                                    f.name, f.length(), dataLogger.linesOnDisk(ctx), dataLogger.rows
                                ) + if (dataLogger.malformedRows > 0L) {
                                    String.format(
                                        Locale.US,
                                        " %d dòng bị chặn vì lệch số cột.",
                                        dataLogger.malformedRows
                                    )
                                } else {
                                    ""
                                }
                                val shown = if (lines.isEmpty()) {
                                    listOf(header)
                                } else {
                                    listOf(header) + lines
                                }
                                val tailNote = if (lines.isEmpty()) {
                                    "$note Mới có dòng tiêu đề, chưa có khung hình nào được ghi xuống."
                                } else {
                                    note
                                }
                                shown to tailNote
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                emptyList<String>() to "Không đọc được file: ${e.message}"
            }
            runOnUiThread {
                csvReading = false
                csvTailLines = result.first
                csvTailNote = result.second
            }
        }.start()
    }

    /** Chữ giải thích cho từng cột, viết ra để không ai phải đoán khi mở file trên máy tính. */
    private fun csvColumnLegend(): String = buildString {
        appendLine("Đơn vị và ý nghĩa của các cột hay bị đọc sai:")
        appendLine("  t_ms          thời gian từ lúc bật máy, không phải giờ tường thuật")
        appendLine("  face          1 = khung này thấy khuôn mặt, 0 = không thấy (SEI để trống)")
        appendLine("  openness      độ mở mắt chia cho đường cơ sở của chính người đó, 1.0 = mở tự nhiên")
        appendLine("  base_ear1/2   đường cơ sở EAR mà phiên hiệu chuẩn vừa lập")
        appendLine("  perclos_p60     tỉ lệ % thời gian mắt GẦN ĐÓNG (độ mở <= ~0.60 đường cơ sở), cửa sổ 60 giây")
        appendLine("                  đây KHÔNG phải P60 chuẩn trong tài liệu buồn ngủ (P60 là mí che >= 60% đồng tử)")
        appendLine("  perclos_p70/80  tỉ lệ % thời gian độ mở <= 0.30 / 0.20 đường cơ sở, cửa sổ 60 giây")
        appendLine("  dist_raw_cm   khoảng cách chưa nhân hệ số hiệu chỉnh 50 cm")
        appendLine("  quality       0-100 độ tin của khung hình; dưới 50 thì mọi cột khác chỉ là tham khảo")
        appendLine("  alert_code    loại cảnh báo ở khung đó, 0 = không có")
        appendLine("  proc_ms       thời gian xử lý một khung, dùng để suy nhịp khung hình")
        appendLine("  luma          độ sáng trung bình khung (0-255), dùng để giải thích chất lượng thấp")
        append("\n")
        append(
            String.format(
                Locale.US,
                "File có %d cột; thứ tự đúng với dòng tiêu đề ở trên. %d dòng gần nhất được hiển thị.",
                dataLogger.header.split(',').size,
                CSV_TAIL_LINES
            )
        )
    }

    // ==================== KỊCH BẢN TRÌNH DIỄN ====================

    private fun toggleDemoStep(index: Int) {
        demoDone = demoDone xor (1 shl index)
    }

    // ==================== DIỄN GIẢI CHỈ SỐ ====================

    /** Bảng chụp nhanh mọi con số đang hiển thị, để dán vào bài thuyết minh. */
    private fun buildPanelReport(): String = buildString {
        appendLine("FOCUSCHANGEAI - BẢNG KIỂM ĐỊNH NHANH")
        appendLine("Học sinh: $studentName")
        appendLine("Thời điểm: ${java.text.SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.US).format(java.util.Date())}")
        appendLine("Phiên: ${sessionText}")
        appendLine("SEI: ${if (seiValue < 0) "--" else "$seiValue/100"} | Mệt mỏi: $fatigueText")
        appendLine("Tỉ lệ nhắm mắt: $perclosText | PERCLOS P70/P80: $perclosP70Text / $perclosP80Text")
        appendLine("Mất tập trung: $inattentionText")
        appendLine("Chớp: $bpmText bpm, $blinkCount lần | EAR: $ear1Text / $ear2Text")
        appendLine("Tư thế: $headPoseText | Hướng nhìn: $gazeText")
        appendLine("Khoảng cách: $distanceText | Chất lượng: $signalText")
        appendLine("Máy: $fpsText FPS, $processingText")
        appendLine(
            String.format(
                Locale.US,
                "File ghi: %s, %d dòng, %d mẫu mất, %d dòng lệch cột",
                if (loggingEnabled) "đang ghi" else "tắt",
                dataLogger.rows, dataLogger.droppedRows, dataLogger.malformedRows
            )
        )
        appendLine(FirebaseSync.statusText())
        appendLine()
        appendLine(seiBreakdown)
        appendLine()
        appendLine(buildThresholdRows())
        appendLine()
        appendLine(habitDetailText)
        appendLine("Ghi chú trung thực: SEI là chỉ số tham chiếu, chưa hồi quy với quan sát lớp học.")
    }

    private fun sharePanelReport() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FocusChangeAI - bang kiem dinh")
            putExtra(Intent.EXTRA_TEXT, buildPanelReport())
        }
        try {
            startActivity(Intent.createChooser(intent, "Chia sẻ bảng kiểm định"))
        } catch (e: Exception) {
            Log.w(TAG, "Không mở được hộp thoại chia sẻ bảng kiểm định", e)
            status = "Không chia sẻ được bảng kiểm định."
        }
    }
}

/** Năm khung của bảng điều khiển, thứ tự khớp với when (panelTab). */
private val PanelTabs = listOf("Theo dõi", "Chuẩn bị", "Chỉ số", "Diễn giải", "Kịch bản")

/**
 * Kịch bản demo: mỗi dòng là một thao tác và phản ứng đúng mà HUD phải thể hiện.
 * Dùng để tập trước và để đối chiếu khi có gì đó không như dự kiến trên sân khấu.
 */
private val PanelDemoSteps: List<Pair<String, String>> = listOf(
    "Ngồi thẳng, cách màn hình 40-50 cm, đủ sáng" to
        "SEI tăng lên vùng 70-90, chất lượng tín hiệu > 70, khoảng cách hiển thị 4x-5x cm.",
    "Nhìn sang bên 30 độ trong 3 giây" to
        "Dòng 'Tư thế đầu' đổi góc yaw, sau 2 giây xuất hiện cảnh báo mất tập trung, điểm vi phạm tăng.",
    "Nhắm nghiền một mắt 2 giây" to
        "EAR mắt đó tụt, độ mở mắt trung bình giảm, tỉ lệ nhắm mắt tăng; nếu giữ lâu thì HUD chuyển sang 'Đang nhắm' và có cảnh báo buồn ngủ.",
    "Giả vờ ngáp (há miệng rộng)" to
        "MAR vượt 0.55, bộ đếm ngáp tăng, chỉ số mệt mỏi nhích lên trong vài giây.",
    "Nghiêng người ra khỏi tầm camera 5 giây" to
        "SEI hiển thị '--' và ĐỒ THỊ KHÔNG rơi về 0, không có báo đỏ 'mất tập trung kéo dài'.",
    "Bấm 'Cảnh báo sai' ngay sau một cảnh báo" to
        "Bộ đếm tăng, dòng nhãn được ghi vào file false_alerts. Hai file dùng hai mốc giờ khác nhau (dòng ảnh tính từ lúc bật máy, dòng nhãn là giờ đồng hồ), nên đối chiếu theo thứ tự thời gian chứ không so con số timestamp.",
    "Bấm 'Ghi CSV', theo dõi 2 phút rồi dừng" to
        "HUD báo số dòng đã ghi và số mẫu bị mất; file nằm trong thư mục riêng của app.",
    "Bấm 'Chia sẻ báo cáo'" to
        "Mở bảng chọn chia sẻ với văn bản tóm tắt; không có ảnh, không có dữ liệu người khác."
)

/**
 * Diễn giải từng chỉ số cho giám khảo. Mỗi mục nói rõ cái gì đo được, cái gì chỉ là giả định,
 * vì nói quá một câu ở đây dễ hơn sửa lại một thuật toán.
 */
private val PanelGlossary: List<Pair<String, String>> = listOf(
    "SEI 2.0 (điểm chú ý)" to
        "Trung bình có trọng số của sáu thành phần: tư thế đầu 20%, khoảng cách 15%, hướng nhìn 20%, " +
        "trạng thái mắt 20%, nhịp chớp 10%, vi biểu cảm 15%. Điểm được làm mượt theo nhịp khung hình để " +
        "một khung nhiễu không làm nhảy số. Đây là chỉ số tham chiếu: trọng số là lựa chọn hợp lý " +
        "chưa được hồi quy với quan sát thực tế của giáo viên.",
    "PERCLOS" to
        "PERCLOS chuẩn là tỉ lệ thời gian mí che đồng tử (P60, P70, P80), tính trong cửa sổ trượt 60 " +
        "giây; tài liệu buồn ngủ hay dùng P80. App này KHÔNG đo được độ che đồng tử, nên ước lượng " +
        "bằng độ mở mắt chia cho đường cơ sở của chính người được đo: dòng 'Tỉ lệ nhắm mắt' là thời " +
        "gian độ mở <= ~0.60, hai dòng P70/P80 là <= 0.30 và <= 0.20. Đó là cách gần đúng, phải nói " +
        "là 'PERCLOS ước lượng qua độ mở mắt' chứ không phải PERCLOS chuẩn.",
    "EAR (Eye Aspect Ratio)" to
        "Tỉ số giữa chiều cao và bề rộng của ổ mắt tính từ 6 điểm mốc. Người mở mắt bình thường có " +
        "EAR cao, khi nhắm mí thì EAR giảm gần một nửa. App không dùng ngưỡng tuyệt đối mà dùng đường " +
        "cơ sở riêng: mỗi người có shape mắt khác nhau (kéo mí một, kính), nên giá trị relax lúc hiệu " +
        "chuẩn mới là mốc so sánh.",
    "Khoảng cách tới màn hình" to
        "Ước lượng bằng camera đơn: lấy bề rộng thật của khoảng cách hai khóe mắt (8.9 cm) và đường " +
        "kính mống mắt (1.17 cm, rất ổn định giữa người với người) rồi suy ra khoảng cách từ tiêu cự " +
        "pixel của camera. Tiêu cự đọc từ Camera2 của đúng máy này, không phải hằng số chung. Sai số " +
        "tăng nhanh khi đầu quay ngang, nên chỉ tính khoảng cách khi góc yaw nhỏ.",
    "Nhịp chớp mắt (BPM)" to
        "Số lần chớp trong cửa sổ 60 giây, chỉ đáng tin sau 15 giây theo dõi liên tục. Chớp quá ít " +
        "(< 8/phút) hoặc quá nhiều (> 30/phút) đều là tín hiệu mệt hoặc căng mắt, nhưng nhịp chớp " +
        "rất cá nhân nên app so với thói quen của chính học sinh đó khi đã có đủ ba phiên.",
    "BFR / MAR" to
        "BFR là tỉ lệ nhíu mày so với đường cơ sở (giữ 2 giây thì tính là cau có). MAR là độ mở miệng " +
        "so với bề rộng môi, vượt 0.55 và giữ 1.2 giây thì tính là một cái ngáp. Cả hai đều là suy đoán " +
        "từ hình dạng, không phải đo cơ.",
    "Chất lượng tín hiệu" to
        "0-100 tổng hợp từ độ sáng trung bình của khung, kích thước khuôn mặt và độ ổn định của điểm " +
        "mốc. Dưới 50 thì các chỉ số khác bị coi là không đáng tin: app sẽ không báo buồn ngủ hay mất " +
        "tập trung khi không nhìn rõ mặt, vì đó là lỗi đo chứ không phải lỗi của người học.",
    "Điểm vi phạm tích lũy" to
        "Mỗi lần rời tầm nhìn, lệch hướng nhìn, ngáp, buồn ngủ hay ngồi quá gần cộng thêm một trọng số " +
        "trong cửa sổ 5 phút. Tổng vượt 3 là mức 'cần chú ý', vượt 6 là 'kéo dài'. Cách này tránh việc " +
        "một lần quay đi 3 giây đã bị kết luận là mất tập trung.",
    "Phát hiện ảnh đứng yên" to
        "Nếu 60 khung liên tiếp có góc đầu và EAR gần như không đổi (độ lệch chuẩn dưới ngưỡng) và " +
        "không có lần chớp nào, app coi đó nhiều khả năng là ảnh tĩnh hoặc camera bị đứng, rồi chặn " +
        "điểm SEI ở mức thấp để không khen nhầm một bức ảnh.",
    "Học thói quen theo hồ sơ" to
        "Một phiên chỉ được đưa vào hồ sơ khi đạt cả năm điều kiện: >= 2 phút hợp lệ, >= 70% thời gian " +
        "thấy khuôn mặt, >= 50% khung trong lúc thấy mặt là hợp lệ (không chớp lỗi, không đứng ảnh, " +
        "không buồn ngủ), chất lượng tín hiệu trung bình >= 60/100 và SEI trung bình >= 40. App chỉ lập " +
        "đường cơ sở riêng khi có >= 4 phiên đạt và tổng >= 10 phút hợp lệ; từ đó ngưỡng chung được trộn " +
        "với ngưỡng của cá nhân theo độ tin, độ tin tăng dần theo số phiên. HUD nói rõ đang có bao nhiêu " +
        "trên tổng 8 ngưỡng là của riêng bạn - nếu thấy 0/8 là vì hồ sơ chưa đủ điều kiện, không phải vì " +
        "app không học.",
    "Mất khuôn mặt thì sao" to
        "App giữ nguyên dấu '--' cho SEI và không tính khoảng trống đó là thời gian mở mắt hay mất " +
        "tập trung. Trước đây khoảng trống bị suy diễn thành 'bằng chứng không chú ý', gây báo đỏ " +
        "giả khi học sinh với sách hoặc quay sang bạn - nay đã sửa: mất tín hiệu là thiếu dữ liệu.",
    "Quyền riêng tư và dữ liệu" to
        "Toàn bộ suy luận chạy trên máy; ảnh không được lưu và không rời khỏi điện thoại. Khi một " +
        "phiên kết thúc, app gửi đúng MỘT bản tổng kết lên Cloud Firestore: tên học sinh, các giá " +
        "trị trung bình của phiên, số cảnh báo và số lần bấm 'cảnh báo sai'. Mất mạng thì lệnh ghi " +
        "nằm trong hàng đợi ngoài luồng của Firestore và tự gửi lại khi có mạng. Ngoài bản tổng kết " +
        "đó không có lời gọi mạng nào khác. Sao lưu dự phòng của hệ thống đã tắt (allowBackup=false), " +
        "dữ liệu nằm trong thư mục riêng của app, và văn bản chia sẻ chỉ gồm những dòng bạn tự bấm " +
        "nút tạo ra."
)

/** Dải màu cho dòng sự kiện, thứ tự khớp với TL_BAND_* ở trên. */
private val TimelineBands: List<Pair<String, Color>> = listOf(
    "Cảnh báo" to Color(0xFFE53935),
    "Kéo dài" to Color(0xFF8E24AA),
    "Mất mặt" to Color(0xFF757575),
    "Chớp mắt" to Color(0xFF00897B),
    "Quá gần" to Color(0xFFEF6C00)
)
