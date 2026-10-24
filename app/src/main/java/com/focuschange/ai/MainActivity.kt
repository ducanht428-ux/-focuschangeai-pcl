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
        // Changed the key: drop the old scale factor that was calibrated with the wrong formula.
        const val KEY_DISTANCE_SCALE = "distance_scale_v2"

        /**
         * The profile that this phone is being used for ONE student. Before this lock existed,
         * anyone holding the phone could switch the profile, so a measurement session could quietly
         * carry another person's label - and the whole "this student's own threshold" idea is lost.
         */
        const val KEY_BOUND_STUDENT = "bound_student_id"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_SOUND_MODE = "sound_mode"
        const val KEY_SENSITIVITY = "sensitivity_level"
        const val KEY_ENHANCE = "enhance_mode"
        const val KEY_HABIT_LEARN = "habit_learn_enabled"
        const val HABIT_AUTOSAVE_MS = 30_000L
        const val HABIT_STATUS_REFRESH_MS = 1_000L

        /**
         * Heartbeat cadence for the teacher screen: the FIRST beat after 1 minute (soon enough to
         * see the phone has entered a measurement shift), then one beat every 5 minutes.
         *
         * 5 minutes is to fit Firestore's write budget, not to fit the human eye: a 40-minute
         * session only adds 8 more writes. The beat is timed off elapsedRealtime because it must
         * keep counting even while the phone is snoozing, while the timestamp inside the record is
         * a wall-clock reading so that two machines read it the same way.
         */
        const val HEARTBEAT_FIRST_MS = 60_000L
        const val HEARTBEAT_MS = 5 * 60_000L

        // SEI below the student's own everyday level (deviation of 1.5x their own spread) for 45 seconds.
        const val LOW_VS_PERSONAL_MS = 45_000L
        const val LOW_VS_PERSONAL_K = 1.5f
        const val PERSONAL_SEI_MIN_SPREAD = 8f

        /** Total number of thresholds that can be replaced by a personal one (so the HUD can say
         *  how many are still defaults). Exactly equal to the number of mix() calls in
         *  applyPersonalParams: 4 posture/gaze + 2 blink cadence + PERCLOS + MAR. If a new personal
         *  threshold is added, this number must be updated to match. */
        const val TOTAL_PERSONAL_THRESHOLDS = 8

        /**
         * The CSV band-column code for a frame where that feature was NOT scored (face lost, poor
         * signal, or habit learning is off). Different from BandPosition.toCsvCode()'s -1: -1 means
         * "scored but the band has not been learned yet". Merging the two codes would make it
         * impossible to tell apart "the device lacks enough data" from "that student was absent today".
         */
        const val CODE_BAND_NOT_SCORED = -2

        /** Percentage of frames falling outside the band before the metrics panel flags a row with an arrow. */
        const val BAND_MARK_OUT_PCT = 5f

        // Alert kind (used to count how many times it appears before a sound fires).
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

        // Only analyse the eyes when the head angle is small enough (beyond it eye landmarks are unreliable).
        const val EYE_MAX_YAW_DEG = 45f
        const val EYE_MAX_PITCH_DEG = 35f

        // Only sound an alert once it has appeared enough times within the counting window.
        const val ALERT_SOUND_AFTER_NORMAL = 2          // normal alert: sounds from the 2nd occurrence
        const val ALERT_SOUND_AFTER_HABIT = 4           // vs-habit alert: sounds from the 4th occurrence
        const val ALERT_COUNT_WINDOW_MS = 10L * 60L * 1000L
        const val ALERT_COUNT_WINDOW_HABIT_MS = 30L * 60L * 1000L
        const val ALERT_REARM_MS = 5_000L               // must stay off for over 5 seconds and reappear to count as a new occurrence

        // Eye landmark indices (kept unchanged from the original code).
        val RIGHT_EYE = intArrayOf(33, 160, 158, 133, 153, 144)
        val LEFT_EYE = intArrayOf(362, 385, 387, 263, 373, 380)

        // Landmarks used for distance and head pose.
        const val LM_OUTER_RIGHT_EYE = 33
        const val LM_OUTER_LEFT_EYE = 263
        const val LM_NOSE_TIP = 1
        const val LM_FOREHEAD = 10
        const val LM_CHIN = 152
        const val LM_FACE_RIGHT = 234
        const val LM_FACE_LEFT = 454
        const val MIN_LANDMARKS = 468
        const val IRIS_LANDMARKS = 478

        // Eye drawing mask (O(1) lookup, allocates nothing while drawing).
        val EYE_MASK = BooleanArray(IRIS_LANDMARKS).also { mask ->
            for (i in RIGHT_EYE) mask[i] = true
            for (i in LEFT_EYE) mask[i] = true
        }

        // Iris: 468-472 right eye, 473-477 left eye.
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

        // The two horizontal iris points (measures the iris diameter).
        const val LM_R_IRIS_H1 = 469
        const val LM_R_IRIS_H2 = 471
        const val LM_L_IRIS_H1 = 474
        const val LM_L_IRIS_H2 = 476

        // Eyebrows and mouth.
        const val LM_BROW_INNER_R = 55
        const val LM_BROW_INNER_L = 285
        const val LM_MOUTH_UP = 13
        const val LM_MOUTH_LOW = 14
        const val LM_MOUTH_R = 78
        const val LM_MOUTH_L = 308

        const val RAD_TO_DEG = 57.29578f

        // Pinhole model: D = (W_real * f_px) / W_pixel
        const val EYE_OUTER_WIDTH_CM = 8.9f        // outer-cornus to outer-cornus (not the 6.3 IPD)
        const val IRIS_DIAMETER_CM = 1.17f         // human iris diameter, very stable
        const val FALLBACK_FOCAL_PX_AT_640 = 500f  // only used if Camera2 cannot be read
        const val MIN_SAFE_DISTANCE_CM = 30f
        const val DISTANCE_RECOVER_CM = 32f
        const val REFERENCE_DISTANCE_CM = 50f
        const val DIST_CAL_SAMPLES = 20

        // Distance calibration window: 8 seconds sitting still is enough for 20 frames at 3 FPS or more.
        const val DIST_CAL_WINDOW_MS = 8_000L
        const val DIST_MEDIAN_SIZE = 9

        // Distance bookkeeper. The eye corners (8.9 cm ~ 89 px @50 cm) are ~1.6% noisy;
        // the iris (1.17 cm ~ 12 px @50 cm) is ~12% noisy, so it is only used to catch outliers.
        const val DIST_MAX_YAW_DEG = 40f
        const val IRIS_MIN_PIXELS = 6f
        const val IRIS_MAX_DISAGREE = 0.15f
        const val IRIS_AGREE_LO = 0.75f
        const val IRIS_AGREE_HI = 1.35f

        // Adaptive EAR calibration.
        const val CALIBRATION_FRAMES = 60
        const val CALIBRATION_MIN_EAR = 0.20f
        // Small eyes or glasses: if after 6 seconds fewer than 10 valid frames were collected, lower the threshold.
        const val CAL_RELAX_AFTER_MS = 6_000L
        const val CAL_RELAX_MIN_FRAMES = 10
        const val CAL_RELAXED_MIN_EAR = 0.12f
        const val CLOSE_RATIO = 0.68f

        // Blinking / BPM.
        const val BPM_WINDOW_MS = 60_000L
        const val BPM_MIN_WINDOW_MS = 10_000L
        const val BPM_RELIABLE_AFTER_MS = 15_000L
        const val DROWSY_CLOSURE_MS = 1_500L
        const val LOW_BLINK_BPM = 8f
        const val HIGH_BLINK_BPM = 30f

        // PERCLOS and inattention ratio (60 seconds).
        const val RATIO_BUCKETS = 60
        const val RATIO_MIN_SAMPLE_MS = 3_000L

        /** Expected frame cadence of the camera stream (~30 FPS), used as the reference for WindowRatio. */
        const val RATIO_REF_FRAME_MS = 33L
        const val PERCLOS_ALERT = 15f
        // The PERCLOS standard in sleepiness literature is P80: lids cover >= 80% of the pupil.
        // openness = how open the eye is versus this person's own baseline,
        // so P80 corresponds to openness <= 0.20 and P70 corresponds to <= 0.30.
        const val PERCLOS_P70_OPEN = 0.30f
        const val PERCLOS_P80_OPEN = 0.20f

        // 3D head pose.
        const val LOOK_AWAY_YAW_DEG = 18f
        const val LOOK_AWAY_PITCH_DEG = 18f
        const val LOOK_AWAY_MS = 2_000L
        const val HEAD_FRONTAL_YAW_DEG = 20f

        // Freeze / abnormal-jerk detection (Anti-Spoofing).
        const val POSE_WINDOW_MS = 10_000L
        const val FREEZE_MIN_SAMPLES = 60
        const val FREEZE_POSE_STD_DEG = 0.15f
        const val FREEZE_EAR_STD = 0.006f
        const val FREEZE_NO_BLINK_MS = 10_000L
        const val JERK_SPEED_DEG_PER_S = 200f
        const val JERK_MIN_GAP_MS = 300L
        const val JERK_WINDOW_MS = 10_000L
        const val JERK_COUNT_ALERT = 3

        // Gaze direction and Visual Drift.
        const val GAZE_H_DRIFT = 0.16f
        const val GAZE_V_DRIFT = 0.22f
        const val VISUAL_DRIFT_MS = 3_000L

        // Micro-expressions.
        const val BFR_FURROW_RATIO = 0.88f
        const val BFR_SUSTAIN_MS = 2_000L
        const val MAR_YAWN = 0.55f
        const val YAWN_MIN_MS = 1_200L
        const val YAWN_WINDOW_MS = 5L * 60L * 1000L

        // Signal quality.
        const val LUMA_VERY_LOW = 35f
        const val LUMA_LOW = 60f
        const val LUMA_HIGH = 220f
        const val MIN_FACE_PX = 40f
        const val VERY_SMALL_FACE_PX = 25f
        const val QUALITY_OK = 50
        const val LUMA_SAMPLE_STEP = 8

        // Cumulative inattention engine.
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

        // 20-20-20 breaks.
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

        // Fatigue index.
        const val FATIGUE_SMOOTHING = 0.10f

        // UI.
        const val HISTORY_SIZE = 120
        const val UI_TEXT_REFRESH_MS = 120L

        /**
         * How long a short message is held on the status line. The periodic "present / not present"
         * line is rewritten every 120 ms, so if it overwrites immediately then the CSV-write summary
         * ("STOP USING THIS FILE: ..."), a report-share error, or a camera-permission reminder all
         * vanish before someone standing next to the judge can finish reading.
         */
        const val STATUS_HOLD_MS = 20_000L

        // ---------------- Demo-prep panel ----------------
        // Before going on stage: press one button, sit still for 20 seconds, the app measures whether
        // the device keeps up and points out exactly what to fix. No number in this panel is a guess.
        const val PREFLIGHT_MS = 20_000L
        const val PREFLIGHT_FPS_FAIL = 12f
        const val PREFLIGHT_FPS_WARN = 20f
        const val PREFLIGHT_QUALITY_WARN = 60
        const val PREFLIGHT_LATENCY_WARN_MS = 80f
        const val PREFLIGHT_FACE_RATIO_MIN = 0.85f
        const val PREFLIGHT_BATTERY_MIN = 25

        // Per-frame processing-latency histogram (drawn with Canvas, no library needed).
        const val LATENCY_BINS = 12
        const val LATENCY_BIN_MS = 15f
        const val LATENCY_SAMPLES = 240

        const val BREAKDOWN_REFRESH_MS = 400L
        const val THRESHOLD_REFRESH_MS = 1_000L

        // Event line of the last 60 seconds: one horizontal strip per type, newest at the right edge.
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

    // @Volatile: created on the UI thread, read on cameraExecutor, and now also set back to null by
    // cameraExecutor itself (see onDestroy) - three threads touching one native pointer.
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

    // ---------------- Interface information (written on the UI thread only) ----------------
    // `status` goes through a property so each change keeps its own hold timestamp: the cycle
    // status line (face present / face absent) rewrites every 120 ms, and if it overwrote it
    // directly the CSV summary or the camera permission hint would vanish before anyone could read it.
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
    private var soundMode by mutableStateOf(2) // 0 off, 1 important alerts only, 2 full

    private var studentName by mutableStateOf("")
    private var habitText by mutableStateOf("--")
    private var habitDetailText by mutableStateOf("")
    private var habitHistoryText by mutableStateOf("")
    private var bandText by mutableStateOf("")
    private var eyeDetailText by mutableStateOf("--")
    private var sensitivityUi by mutableStateOf(1)
    private var enhanceUi by mutableStateOf(0)
    private var cameraMirrored by mutableStateOf(true)
    private var imageDetailText by mutableStateOf("--")
    private var habitLearnUi by mutableStateOf(true)
    private var showAddStudent by mutableStateOf(false)

    /** Profile this phone is bound to (KEY_BOUND_STUDENT), changeable only through the confirm dialog. */
    private var boundStudentId by mutableStateOf("")
    private var showRebind by mutableStateOf(false)
    private var rebindTargetId: String? = null
    private var showResetHabit by mutableStateOf(false)
    private var newStudentName by mutableStateOf("")

    // ---------------- Extended control panel ----------------
    // 0 = monitoring parameters, 1 = demo preparation, 2 = indices and thresholds,
    // 3 = explanation for the judges, 4 = demo script.
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

    // Latest 60-second event line: one strip per type, newest point on the right.
    private class TimedEvent(val second: Long, val band: Int)

    private val timelineEvents = ArrayDeque<TimedEvent>()
    private val timelineLastSec = LongArray(TL_BAND_COUNT) { -1L }

    // Counters for the 20-second test. Touched by the UI thread only (called from applySnapshot),
    // so no @Volatile is needed.
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

    // Latency histogram: plain backing array, only an immutable copy is handed to state.
    private val latencyCounts = IntArray(LATENCY_BINS)

    // Slots of each sample in the LATENCY_SAMPLES ring. Having the ring is what lets the oldest
    // sample be deleted correctly; otherwise the histogram only grows and "average X ms" is the
    // average of the whole session, not of the last 240 frames as the HUD text promises.
    private val latencyBins = IntArray(LATENCY_SAMPLES)
    private var latencyWriteIdx = 0
    private var latencyFilled = 0

    // The SEI breakdown table built by cameraExecutor; the UI thread only reads the string reference.
    @Volatile
    private var seiBreakdown = "--"
    private var lastBreakdownMs = 0L
    private var lastThresholdMs = 0L
    private var activeStudentId = ""

    // State used on the UI thread only
    private var lastSnapshot: HudSnapshot? = null
    private var lastHistorySecond = 0L
    private var lastUiTextMs = 0L

    // Sensitivity: 0 low, 1 medium, 2 high. Written by the UI thread, read by cameraExecutor.
    @Volatile
    private var sensLevel = 1
    private var lastChronicLevelUi = 0
    private var lastTooCloseUi = false
    private var lastAnyToneMs = -1_000_000L
    private val alertOccurrences = Array(15) { ArrayList<Long>() }
    private val lastKindActiveMs = LongArray(15) { -1_000_000L }
    private val lastToneMs = LongArray(5) { -1_000_000L }
    private var toneGenerator: ToneGenerator? = null

    // ---------------- Processing state (cameraExecutor only) ----------------
    @Volatile
    private var recalibrationRequested = false

    @Volatile
    private var distanceCalibrationRequested = false

    // When the 50 cm calibration button was pressed, on elapsedRealtime. Must be @Volatile because the
    // UI thread writes it while cameraExecutor reads it to judge whether a sample is still usable.
    @Volatile
    private var distanceCalRequestedMs = 0L

    @Volatile
    private var distanceScale = 1f

    /**
     * Factor read from the old `distance_scale_v2` pref. From this version the real factor lives inside
     * each student's profile (HabitProfile.distanceScale); the old pref only rescues profiles that were
     * never calibrated on the new build, and it is overwritten at the very next calibration.
     */
    @Volatile
    private var legacyDistanceScale = 1f

    /** Switching profile switches the factor and the face-width signature - never carry numbers across. */
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
     * The "the person seems to have changed" reminder line, written by cameraExecutor and copied by
     * HudSnapshot. It never enters the alert chain: choosing the wrong profile is an operational
     * mistake, not a sign of distraction, and counting it would pollute the research numbers.
     */
    @Volatile
    private var personHintPlain: String? = null

    /** 0 = no reminder, 1 = rolling test (cleared when clean), 2 = calibration just deviated (kept). */
    @Volatile
    private var personHintKind = 0

    /**
     * Band position codes of the 12 features for the frame being processed. Only cameraExecutor touches
     * it, and it reuses one array like csvBuf so that no 8 objects are allocated per frame.
     */
    private val bandCodes = IntArray(HabitFeature.COUNT) { CODE_BAND_NOT_SCORED }

    /** One summary line plus one long table, rebuilt by cameraExecutor once per second. */
    @Volatile
    private var bandSummaryPlain = ""

    @Volatile
    private var bandTablePlain = ""

    // EAR calibration + personal baseline
    // These three are normally cameraExecutor-only, BUT loggerMeta() reads them on the UI
    // thread to write meta.txt. Without @Volatile the environment file of a CSV session could
    // copy values from the previous session - and meta.txt is what the numbers are rebuilt from.
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

    // Absolute pose angles (degrees) computed from the 3D landmarks
    private var poseYawAbs = 0f
    private var posePitchAbs = 0f
    private var poseRollAbs = 0f

    // One Euro filters
    private val yawFilter = OneEuroFilter(1.5f, 0.30f)
    private val pitchFilter = OneEuroFilter(1.5f, 0.30f)
    private val rollFilter = OneEuroFilter(1.5f, 0.30f)
    private val gazeHFilter = OneEuroFilter(1.5f, 5.0f)
    private val gazeVFilter = OneEuroFilter(1.5f, 5.0f)
    // Distance changes far slower than head angle, so its cutoff is much lower.
    private val distanceFilter = OneEuroFilter(0.25f, 0.02f)

    // Real camera parameters (to compute the pixel focal length accurately)
    private var sensorFocalMm = 0f
    private var sensorPhysWmm = 0f
    private var sensorPhysHmm = 0f

    @Volatile
    private var streamW = 640

    @Volatile
    private var streamH = 480

    // Distance median window + distance calibration samples
    private val distWindow = ArrayDeque<Float>()
    private val distCalSamples = ArrayList<Float>()

    // Upgraded eye analysis engine (glasses, tilt, squint, eye asymmetry, incomplete blinks).
    private val eyeEngine = EyeEngine()

    // Low-level image handling: adaptive CLAHE and light denoising on the luminance channel, before RGB.
    private val enhancer = ImageEnhancer()
    private val faceBox = FloatArray(4)
    private var faceBoxMs = 0L
    private var imageDetail = "--"
    private var lastImageDetailMs = 0L

    // 0 = automatic by light level, 1 = off (for an A/B comparison of detection quality)
    @Volatile
    private var enhanceMode = 0

    // ---------------- Per-student habit learning (cameraExecutor only) ----------------
    private lateinit var habitStore: HabitStore
    private var habitProfile: HabitProfile? = null
    private var habitSession: HabitSession? = null
    private var lastHabitSaveMs = 0L
    private var lastHabitStatusMs = 0L

    /**
     * When (elapsedRealtime) the next presence beat may be sent. Reset when a session opens so the
     * first beat arrives after 1 minute, not on the very first frame.
     */
    private var nextHeartbeatMs = 0L
    private var habitStatus = "Đang nạp hồ sơ học sinh..."
    private var habitDetail = ""
    private var habitHistory = ""
    private var personalSeiMean = Float.NaN
    private var personalSeiSpread = 0f

    @Volatile
    private var personalThresholdCount = 0

    /**
     * Percentage weight of the personal profile in the current threshold (this is c of
     * HabitProfile.confidence, 0 while there are not enough sessions). The "Chi so" table prints it
     * so nobody reads "ho so rieng" as "100% of that student's numbers": at 5 sessions the personal
     * profile is still only 20%.
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

    // Current thresholds: defaults, replaced by personal ones once the profile has enough data.
    // cameraExecutor writes, the UI thread reads (the index table), so each one must be @Volatile.
    @Volatile private var thrLookYaw = LOOK_AWAY_YAW_DEG
    @Volatile private var thrLookPitch = LOOK_AWAY_PITCH_DEG
    @Volatile private var thrGazeH = GAZE_H_DRIFT
    @Volatile private var thrGazeV = GAZE_V_DRIFT
    @Volatile private var thrBlinkLow = LOW_BLINK_BPM
    @Volatile private var thrBlinkHigh = HIGH_BLINK_BPM
    @Volatile private var thrPerclos = PERCLOS_ALERT
    @Volatile private var thrMar = MAR_YAWN

    // Exactly the order of the six components in buildSeiBreakdown.
    private val seiWeights = floatArrayOf(
        W_POSTURE, W_DISTANCE, W_GAZE, W_EYES, W_BPM, W_EXPRESSION
    )

    // Blink state machine
    private var eyesClosed = false
    private var closedStartMs = 0L
    private var lastBlinkMs = 0L
    private val blinkTimestamps = mutableListOf<Long>()
    private val blinkDurations = mutableListOf<Long>()
    private var monitoringStartMs = 0L

    // PERCLOS and the distraction ratio
    private val perclos =
        WindowRatio(RATIO_BUCKETS, RATIO_MIN_SAMPLE_MS, refFrameMs = RATIO_REF_FRAME_MS)
    private val inattention =
        WindowRatio(RATIO_BUCKETS, RATIO_MIN_SAMPLE_MS, refFrameMs = RATIO_REF_FRAME_MS)
    private val perclosP70 =
        WindowRatio(RATIO_BUCKETS, RATIO_MIN_SAMPLE_MS, refFrameMs = RATIO_REF_FRAME_MS)
    private val perclosP80 =
        WindowRatio(RATIO_BUCKETS, RATIO_MIN_SAMPLE_MS, refFrameMs = RATIO_REF_FRAME_MS)

    // Raw eye opening of the most recent frame, kept for the CSV row. The eye value only
    // lives inside one if-branch, so it has to be parked here.
    private var lastOpenness = Float.NaN
    private var lastSigma = Float.NaN
    private var lastEyeValid = false

    private val dataLogger = DataLogger()

    // The button toggles on the UI thread, the frame loop reads on cameraExecutor. Without @Volatile the
    // machine could run a whole session without seeing the record command: empty CSV, screen says writing.
    @Volatile
    private var loggingEnabled = false
    private val csvBuf = StringBuilder(256)

    // Distance
    private var smoothedDistanceCm: Float? = null
    private var distanceAlertActive = false

    // Head pose
    private var prevNoseX = Float.NaN
    private var prevNoseY = Float.NaN
    private var jitterEma = 0f
    private var lookAwayStartMs = 0L

    // Head pose sliding window (anti-spoofing)
    private val poseWindow = ArrayDeque<PoseSample>()
    private val jerkTimestamps = mutableListOf<Long>()
    private var lastJerkMs = 0L

    // Visual Drift
    private var driftStartMs = 0L

    // Micro-expressions
    private var furrowStartMs = 0L
    private var yawnStartMs = 0L
    private var yawnCounted = false
    private val yawnTimestamps = mutableListOf<Long>()

    // Accumulating distraction engine
    private val violationEvents = ArrayDeque<ViolationEvent>()
    private var prevHeadAway = false
    private var prevDrift = false
    private var prevDrowsy = false
    private var prevTooClose = false
    private var prevConfused = false
    private var chronicActive = false
    private var chronicLevelValue = 0

    // Session statistics
    private var violationsTotal = 0
    private var chronicEpisodes = 0
    private var yawnTotal = 0
    private var minSeiValue = Int.MAX_VALUE

    // Signal quality
    private var lumaMean = 128f
    private var signalNote = "Tốt"
    private var signalReliable = true

    // Fatigue index
    private var fatigueEma = 0f

    // 20-20-20 break
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

    // Reusable buffers to cut GC (cameraExecutor only)
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

        // Keep the screen awake while monitoring.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Firebase only syncs the summary when a session ends. If the config file is missing, start()
        // marks itself as disabled and the app keeps measuring normally.
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
        val bound = prefs.getString(KEY_BOUND_STUDENT, null)
        val wanted = bound?.takeIf { b -> habitStore.students().any { it.id == b } }
        if (wanted != null) {
            // This phone is bound to one student: ignore HabitStore's "current" pointer, because that
            // pointer changes every time someone taps in the list. Without this, a single tap would
            // make student A's phone start writing data under B's name.
            activeStudentId = wanted
            habitStore.setCurrent(wanted)
        } else {
            activeStudentId = habitStore.currentId()
            if (bound == null) {
                // First run on this phone: the currently selected profile becomes the owner.
                prefs.edit().putString(KEY_BOUND_STUDENT, activeStudentId).apply()
            }
        }
        boundStudentId = activeStudentId
        studentName = habitStore.nameOf(activeStudentId)
        studentSwitchRequest = activeStudentId

        // BootReceiver just reopened the app after the phone booted: note it in the status line so today's
        // session leaves a clear trace of whether it started automatically or by hand.
        if (intent?.getBooleanExtra(BootReceiver.EXTRA_AUTO_START, false) == true) {
            status = "Tự mở sau khi bật điện thoại " +
                "(${intent?.getStringExtra(BootReceiver.EXTRA_AUTO_START_SOURCE) ?: "boot"}). " +
                "Nếu máy này có công tắc Autostart riêng thì vẫn nên kiểm tra nó."
        }

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

                        // SEI chart of the last 2 minutes.
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

                        // Scrollable parameter area so the camera frame stays large enough.
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
                            if (bandText.isNotEmpty()) {
                                Text(
                                    text = bandText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (habitHistoryText.isNotEmpty()) {
                                Text(
                                    text = "Các phiên gần đây:\n$habitHistoryText",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Text(
                                text = "Máy này đang gắn với: $studentName",
                                style = MaterialTheme.typography.bodySmall
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { requestSwitchStudent() }) {
                                    Text("Đổi người dùng trên máy này")
                                }
                                Button(onClick = { requestAddStudent() }) {
                                    Text("Thêm HS")
                                }
                            }

                            if (showRebind) {
                                val target = rebindTargetId?.let { id -> habitStore.nameOf(id) } ?: ""
                                AlertDialog(
                                    onDismissRequest = {
                                        showRebind = false
                                        rebindTargetId = null
                                    },
                                    title = { Text("Đổi người dùng?") },
                                    text = {
                                        Text(
                                            "Máy này đang theo dõi $studentName. Đổi sang $target sẽ " +
                                                "kết thúc phiên của $studentName và gửi bản tổng kết " +
                                                "của em đó, đồng thời mọi ngưỡng riêng và hệ số hiệu " +
                                                "chuẩn khoảng cách sẽ chuyển sang của $target. " +
                                                "Nếu hai em chỉ ngồi cạnh nhau thì đừng đổi."
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = { applyRebind() }) {
                                            Text("Đổi sang $target")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = {
                                            showRebind = false
                                            rebindTargetId = null
                                        }) {
                                            Text("Giữ nguyên")
                                        }
                                    }
                                )
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

                            // ---- Control panel: pick one of the five frames ----
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
                                    // Write the timestamp FIRST and only then raise the flag: these two variables are
                                    // read on cameraExecutor, and if a frame lands between the two
                                    // writes it sees "already requested" stamped with the earlier request (or 0),
                                    // concludes the request is too old, clears the flag - the button vanishes
                                    // silently and no calibration happens.
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
                                    // Full -> Important only -> Off -> Full
                                    soundMode = if (soundMode == 0) 2 else soundMode - 1
                                    prefs.edit().putInt(KEY_SOUND_MODE, soundMode).apply()
                                    if (soundMode > 0) {
                                        // Plays a sample so the student can hear the volume level.
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

                                // Mapping matches FILL_CENTER (zoom in + centre crop).
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
                                            // Front camera: mirror along the X axis.
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

                // Lower the analysis resolution to cut processing time.
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                analysis.setAnalyzer(cameraExecutor) { image ->
                    analyzeFrame(image)
                }

                // Prefer the front camera (phone). A device with no front camera (tablet, Chromebook, desktop
                // emulator, external webcam) falls back to the rear camera or whichever camera is available.
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

    // ======================= REAL CAMERA PARAMETERS =======================

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

            // Pass 1: front camera (phone). Pass 2: any camera (tablet, emulator, external webcam).
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

    /** Pixel focal length of the frame being analysed (the cropped part of the sensor is included). */
    private fun focalPx(): Float {
        val longPx = max(streamW, streamH).toFloat()
        val shortPx = min(streamW, streamH).toFloat()

        if (sensorFocalMm <= 0f || sensorPhysWmm <= 0f || sensorPhysHmm <= 0f) {
            return FALLBACK_FOCAL_PX_AT_640 * (longPx / 640f)
        }

        val sensorAspect = sensorPhysWmm / sensorPhysHmm
        val streamAspect = longPx / shortPx
        val pxPerMm = if (sensorAspect >= streamAspect) {
            shortPx / sensorPhysHmm   // wider sensor: cropped on the sides
        } else {
            longPx / sensorPhysWmm    // narrower sensor: cropped top and bottom
        }
        return sensorFocalMm * pxPerMm
    }

    // ======================= HABIT LEARNING PER STUDENT =======================

    // ---- Runs on the UI thread ----

    private fun selectStudent(st: HabitStudent) {
        activeStudentId = st.id
        habitStore.setCurrent(st.id)
        studentName = st.name
        // Choosing a profile on this phone = binding the phone to that profile. Every path that can change
        // the person goes through this function, including the confirm dialog and the add-student button,
        // so no path can change the ID without being recorded here.
        prefs.edit().putString(KEY_BOUND_STUDENT, st.id).apply()
        boundStudentId = st.id
        studentSwitchRequest = st.id
        resetAlertCounters()
        blinkCount = 0
        seiValue = -1
        seiHistory = emptyList()
        calibrationText = "Đang hiệu chuẩn lại..."
    }

    /**
     * The student-switch button does NOT switch immediately like it used to. Changing the user on a
     * phone already bound to an ID ends the measurement of the student who is sitting there, so it has
     * to be a deliberate teacher action with an explanation, not a stray swipe of a finger.
     */
    private fun requestSwitchStudent() {
        val list = habitStore.students()
        if (list.size < 2) {
            status = "Mới có 1 hồ sơ học sinh, hãy bấm Thêm HS."
            return
        }
        val idx = list.indexOfFirst { it.id == boundStudentId }
        rebindTargetId = list[(idx + 1).mod(list.size)].id
        showRebind = true
    }

    private fun applyRebind() {
        val target = rebindTargetId
        showRebind = false
        rebindTargetId = null
        if (target == null) return
        val st = habitStore.students().firstOrNull { it.id == target } ?: return
        status = "Máy này giờ theo dõi ${st.name}. Phiên của $studentName đã được kết thúc và gửi đi."
        selectStudent(st)
    }

    private fun addStudent(name: String) {
        selectStudent(habitStore.addStudent(name))
    }

    /** Adding a profile is always a person change, so it goes through selectStudent and rebinds the phone. */
    private fun requestAddStudent() {
        newStudentName = ""
        showAddStudent = true
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

    // ---- Runs on cameraExecutor ----

    /** Handles a student switch or profile delete request sent over by the UI thread. */
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
                // "Delete profile" means forgetting the sessions that were learned, not forgetting the distance
                // calibration as well: that one is a physical measurement, and erasing it would make the user
                // calibrate again by hand without anyone being told why.
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
        bandSummaryPlain = ""
        bandTablePlain = ""
        personalSeiMean = Float.NaN
        personalSeiSpread = 0f
        lowVsPersonalStartMs = 0L
        lastSeiValue = -1
    }

    /** Replaces the default thresholds with personal ones, blended by confidence c (0..1). */
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

    /** Called when calibration finishes: opens a new session, applies personal thresholds, blends base EAR. */
    private fun beginHabitSession(nowMs: Long) {
        val prof = habitProfile
        resetPersonalParams()
        lastHabitSaveMs = nowMs
        lastHabitStatusMs = 0L
        nextHeartbeatMs = nowMs + HEARTBEAT_FIRST_MS

        if (prof == null) {
            habitSession = null
            return
        }

        val session = HabitSession(System.currentTimeMillis())
        habitSession = session

        // Records the RAW base EAR of this session (before blending) so it cannot amplify itself.
        if (habitLearnEnabled) {
            session.observe(HabitFeature.EAR, (baselineEar1 + baselineEar2) / 2f)
        }

        if (!prof.isReady()) return

        val base = prof.baseline()
        val c = prof.confidence()
        applyPersonalParams(base, c)
        blendEarWithHabit(base, c)

        // The two-sided band is built ONCE for the whole session, from the sessions that came before it.
        // Rebuilding it midway would let one unusual study session widen its own band.
        session.setBands(base, HabitFeature.BAND_FEATURES)
        sendBandSnapshot(prof, base)
    }

    /**
     * Publishes the range this student is currently scored against, once per session right after
     * [HabitSession.setBands]. The cloud copy is therefore exactly the range this session is being
     * judged by, not a wider one built later.
     *
     * Purely descriptive, like the on-screen band: it says what this student looks like when they
     * are being themselves. It does not mark anyone as good or bad, and no alert depends on it.
     */
    private fun sendBandSnapshot(prof: HabitProfile, base: HabitBaseline) {
        if (!FirebaseSync.enabled) return
        val list = ArrayList<FeatureBand>(HabitFeature.BAND_FEATURES.size)
        for (f in HabitFeature.BAND_FEATURES) {
            val b = base.band(f) ?: continue
            val sp = base.spread(f) ?: continue
            list.add(FeatureBand(f, b.mean, b.min, b.max, sp))
        }
        // Nothing learned yet means nothing to publish. An empty document would read as "this
        // student has no range", while the truth is "this student has not been measured enough".
        if (list.isEmpty()) return
        FirebaseSync.uploadBand(
            BandReport(
                studentId = prof.id,
                studentName = studentName,
                sessionsUsed = prof.sessionCount(),
                updatedAtWallMs = System.currentTimeMillis(),
                bands = list,
                device = android.os.Build.MODEL,
                appVersion = appVersionName()
            )
        ) {
            // Firestore reports the result on the main thread, so the panel text can be changed right away.
            firebaseText = FirebaseSync.statusText()
        }
    }

    /**
     * Blends the session base EAR with the learned typical EAR (up to 35% at full confidence).
     * If they differ by more than 30% the new value is kept as is (new glasses or new lighting).
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
        val session = habitSession
        val summary = session?.toSummary()
        // The band must be read BEFORE the session is discarded. It is not stored in the profile (today's
        // band loaded into tomorrow's profile would let one unusual session widen its own band), so this
        // point is the only chance to keep it for the CSV and the cloud record.
        val bands = session?.bandOutcomes() ?: emptyList()
        commitHabitSession()
        habitSession = null
        if (summary != null) uploadSessionToCloud(summary, bands)
    }

    /**
     * Ending a session sends EXACTLY ONE record to Firestore, carrying the averages of the whole
     * session rather than individual frames. A session that fails the profile bar is still sent and
     * marked ineligible with its reason: excluded data is still data, and hiding it would leave only
     * the beautiful sessions in the summary table on the cloud.
     */
    private fun uploadSessionToCloud(s: SessionSummary, bands: List<BandOutcome>) {
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
            appVersion = appVersionName(),
            endedAtWallMs = System.currentTimeMillis(),
            ineligibleReason = prof.ineligibleReason(s),
            bandOutcomes = bands
        )
        FirebaseSync.upload(report) { ok ->
            // Firestore reports the result on the main thread, so it may touch the HUD state directly.
            firebaseText = FirebaseSync.statusText()
            if (!ok) {
                status = "Không gửi được lên Firebase (${FirebaseSync.lastError}) - " +
                    "số liệu vẫn còn nguyên trong máy."
            }
        }
    }

    /** Saves periodically and refreshes the status line (once per second). */
    private fun maintainHabit(nowMs: Long) {
        if (nowMs - lastHabitSaveMs >= HABIT_AUTOSAVE_MS) {
            lastHabitSaveMs = nowMs
            commitHabitSession()
        }
        if (nowMs - lastHabitStatusMs >= HABIT_STATUS_REFRESH_MS) {
            lastHabitStatusMs = nowMs
            habitStatus = buildHabitStatus()
            habitHistory = habitProfile?.historyText(5) ?: ""
            refreshBandText()
            refreshHeartbeat(nowMs)
        }
    }

    /**
     * Presence beat: tells the teacher screen "this student is being measured, up to minute N".
     *
     * Only runs inside the camera thread, so it can only be sent while the camera really runs: screen on
     * and the app in the foreground. A frozen `beat_at_wall_ms` therefore means "NOT MEASURED", never
     * "the student is doing something else" - Android 9+ forbids a background app from using the camera,
     * so switching the screen off ends the data. This is spelled out here because it is the cell most
     * easily misread on the monitoring board.
     *
     * First beat after HEARTBEAT_FIRST_MS, then every HEARTBEAT_MS - not every frame: a 40 minute
     * session adds 8 writes, while sending per frame would hit the Firestore write quota during
     * the very first measurement session.
     */
    private fun refreshHeartbeat(nowMs: Long) {
        val session = habitSession ?: return
        if (!FirebaseSync.enabled) return
        // With the habit-learning button off, tick()/observe() do not run, mid-session numbers are all zeros
        // and the teacher reads "this student measured 0". A frozen presence table is the honest option.
        if (!habitLearnEnabled) return
        if (nowMs < nextHeartbeatMs) return
        nextHeartbeatMs = nowMs + HEARTBEAT_MS
        sendHeartbeat(session)
    }

    private fun sendHeartbeat(session: HabitSession) {
        val prof = habitProfile ?: return
        var alerts = 0
        for (list in alertOccurrences) alerts += list.size
        var bandFrames = 0
        var bandBelow = 0
        var bandAbove = 0
        for (o in session.bandOutcomes()) {
            bandFrames += o.samples
            bandBelow += o.below
            bandAbove += o.above
        }
        FirebaseSync.uploadHeartbeat(
            HeartbeatReport(
                studentId = prof.id,
                studentName = studentName,
                sessionId = session.id,
                // HabitSession is built with System.currentTimeMillis(), so the id and the start moment are the
                // same number. Both are passed as separate columns so the record explains itself without guessing.
                startedAtWallMs = session.id,
                beatAtWallMs = System.currentTimeMillis(),
                activeMs = session.activeMs,
                summary = session.toSummary(),
                alertsTotal = alerts,
                falseAlerts = falseAlertCount,
                bandFrames = bandFrames,
                bandBelow = bandBelow,
                bandAbove = bandAbove,
                device = android.os.Build.MODEL,
                appVersion = appVersionName()
            )
        ) {
            // Firestore reports the result on the main thread, so the panel text can be changed right away.
            firebaseText = FirebaseSync.statusText()
        }
    }

    /** Build label, so the cloud table can tell which phone is still running an old build. */
    private fun appVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    /**
     * Builds the two-sided band text: one line for the main screen, one table for the index tab.
     * Runs on cameraExecutor on the same 1-second tick as [habitStatus]; the long table is only read
     * when the viewer opens that tab, so no effort is wasted on it.
     *
     * Two facts are printed straight onto the screen instead of being left for the reader to infer:
     * (1) the band only DESCRIBES, it changes not one alert threshold, and
     * (2) "no band yet" differs from "always inside the band": while the profile has too few sessions
     * the table must say there is nothing, not report a 0% out-of-band rate.
     */
    private fun refreshBandText() {
        val totalFeatures = HabitFeature.BAND_FEATURES.size
        val session = habitSession
        if (session == null || !habitLearnEnabled) {
            bandSummaryPlain = if (habitLearnEnabled) {
                "Dải hai phía: chưa có phiên nào đang đo (chỉ có sau khi hiệu chuẩn xong)."
            } else {
                "Dải hai phía: không chấm vì nút 'Học thói quen' đang tắt."
            }
            bandTablePlain = ""
            return
        }

        val outcomes = session.bandOutcomes()
        val covered = session.bandCoverage(HabitFeature.BAND_FEATURES)
        if (outcomes.isEmpty()) {
            bandSummaryPlain = String.format(
                Locale.US,
                "Dải hai phía: chưa dựng được chỉ số nào (cần %d phiên đạt, hồ sơ có %d).",
                HabitConfig.MIN_SESSIONS, habitProfile?.sessionCount() ?: 0
            )
            bandTablePlain = "Chưa có dải nên mọi cột band_* trong CSV đang mang -1 (chưa học được " +
                "dải) hoặc -2 (khung này không chấm) - không phải 'tất cả đều bình thường'."
            return
        }

        var samples = 0
        var below = 0
        var above = 0
        for (o in outcomes) {
            samples += o.samples
            below += o.below
            above += o.above
        }
        bandSummaryPlain = String.format(
            Locale.US,
            "Dải hai phía (%d/%d chỉ số): %.1f%% lượt đo nằm trong dải, %.1f%% dưới, %.1f%% trên - " +
                "tính trên %d lượt đo, mỗi khung chấm tối đa %d chỉ số. Mô tả, không phải cảnh báo.",
            covered, totalFeatures,
            (samples - below - above) * 100f / samples,
            below * 100f / samples, above * 100f / samples, samples, totalFeatures
        )

        bandTablePlain = buildString {
            appendLine(
                String.format(
                    Locale.US,
                    "Dải cho phép học từ thói quen (mô tả - không phải ngưỡng cảnh báo; " +
                        "dải = mức điển hình ±%.1f lần dao động):",
                    HabitConfig.BAND_K
                )
            )
            for (o in outcomes) {
                val dp = HabitFeature.DECIMALS[o.feature]
                val range = String.format(
                    Locale.US, "[%." + dp + "f – %." + dp + "f]%s",
                    o.min, o.max, HabitFeature.UNITS[o.feature]
                )
                val mark = if (o.outsidePct >= BAND_MARK_OUT_PCT) "  ← lệch thường xuyên" else ""
                appendLine(
                    String.format(
                        Locale.US, "%-24s %-18s dưới %5.1f%% | trong %5.1f%% | trên %5.1f%%  (%d khung)%s",
                        HabitFeature.NAMES[o.feature], range,
                        o.belowPct, o.inPct, o.abovePct, o.samples, mark
                    )
                )
            }
            append(
                String.format(
                    Locale.US,
                    "SEI nằm trong bảng này với tư cách số so sánh, không phải bằng chứng. " +
                        "%d/%d đặc trưng có dải; 4 đặc trưng cố ý không dựng dải (khoảng cách, " +
                        "độ dài một cái chớp, BFR, EAR) vì lý do ghi trong HabitModel.kt.",
                    covered, totalFeatures
                )
            )
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

    // ======================= REPORTS & SOUNDS (UI thread) =======================

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

    /** Volume level per alert type: 1 gentle reminder, 2 warning, 3 urgent, 4 break time. */
    private fun alertLevelOf(kind: Int): Int = when (kind) {
        KIND_DROWSY, KIND_CHRONIC -> 3
        KIND_PERCLOS, KIND_YAWN, KIND_TOO_CLOSE -> 2
        KIND_BREAK -> 4
        else -> 1
    }

    /**
     * How many times an alert must appear before it makes a sound.
     * Sleepiness, prolonged distraction and the break reminder sound from the first occurrence.
     */
    private fun alertSoundAfter(kind: Int): Int = when (kind) {
        KIND_DROWSY, KIND_CHRONIC, KIND_BREAK -> 1
        KIND_LOW_PERSONAL -> ALERT_SOUND_AFTER_HABIT
        else -> ALERT_SOUND_AFTER_NORMAL
    }

    /** Records one occurrence; returns true once the number of occurrences is enough to sound. */
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
     * Sound by severity, one distinct tone per level so the alert type is recognisable by ear:
     * 1 gentle reminder, 2 warning (double beep), 3 urgent (long tone), 4 break (soft tone).
     * Each level has its own quiet period so it cannot nag the whole class continuously.
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
            // Avoids two tones overlapping; the urgent level is always allowed to cut in.
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

    // ======================= SENSITIVITY =======================

    /** Confirmation time factor: the higher the sensitivity the earlier it alerts (0.6 = 60% of the time). */
    private fun sensTime(): Float = when (sensLevel) {
        0 -> 1.5f
        2 -> 0.6f
        else -> 1.0f
    }

    /** Head angle / gaze threshold factor: the higher the sensitivity the smaller the deviation it alerts on. */
    private fun sensThr(): Float = when (sensLevel) {
        0 -> 1.15f
        2 -> 0.85f
        else -> 1.0f
    }

    /** SEI smoothing factor: the higher the sensitivity the closer the index tracks the instant value. */
    private fun sensSmooth(): Float = when (sensLevel) {
        0 -> 0.7f
        2 -> 1.6f
        else -> 1.0f
    }

    // ======================= GEOMETRY MATH =======================

    /** Euclidean distance in pixel space (avoids distortion from non-square images). */
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
     * Distance in cm, NOT yet multiplied by the user factor.
     *
     * Uses ONE ruler only, the span between the two eye corners, cancelling perspective with 1/cos(yaw).
     * The iris no longer takes part in the average - it only serves as a gate that drops noisy frames.
     * Reason: at 640x480 and 50 cm the eye corners span about 89 px (landmark noise ~1.6%) while the
     * iris is only about 12 px (~12%). Both sources used to be blended with equal weight, so the
     * noisier ruler dominated; and when the eyes close the function returns the iris alone, making
     * the median window jump between two estimates with different systematic bias.
     *
     * `yawAbsDeg` must be the ABSOLUTE head rotation relative to the lens axis, not the deviation from
     * that student's resting pose. The cosine term below compensates exactly the shortening caused by
     * perspective, and that shortening only exists when the face really turns relative to the camera.
     * It used to receive `yawRawDev`, so a student sitting permanently turned 20 degrees got nothing:
     * the estimated distance is 6.4% TOO HIGH (1.9 cm at the 30 cm mark), larger than the whole 30->32
     * cm hysteresis band, so the "too close" alert could never fire even while sitting very close.
     * Absolute angle fixed one more bug: a 30 degree turn used to move the number 15% in place.
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

        // The frame rejection gate has to consider the absolute angle too: this very 1/cos term magnifies
        // landmark error during a deep turn, so the angle that is NOT compensated is the one to block.
        val yaw = abs(yawAbsDeg)
        if (yaw > DIST_MAX_YAW_DEG) return null

        // Clamped at 0.3f: cos(72 deg) ~ 0.31, blocking a division by zero if anyone calls this above the gate.
        val dCorner = EYE_OUTER_WIDTH_CM * f / (cornerPx / cos(yaw / RAD_TO_DEG).coerceAtLeast(0.3f))

        // Cross-check with the iris: the two eyes must report similar sizes and must agree
        // with the eye corners within ~35%. A mismatch means the landmarks are noisy -> drop this frame.
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
     * Projects point p onto the a->b axis in pixel space.
     * Returns 0 at a and 1 at b (used for the iris position inside the eye socket).
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
     * Euler angles from the face-plane normal (uses the z coordinates of MediaPipe as well).
     * R = horizontal vector (left-right cheek), U = vertical vector (chin-forehead), N = R x U pointing
     * at the camera. Convention: yaw positive = turned toward the user's left, pitch positive = bending
     * down. The values are used mostly as deviations from the calibration baseline.
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

    // ======================= SIGNAL QUALITY =======================

    /**
     * Signal quality 0-100. Updates signalReliable (light + face size) used to block the
     * less trustworthy alerts, and signalNote (the main reason).
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

        // Backlight (face much darker than the background) or a blown-out face: eye landmarks are unreliable.
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

    // ======================= HEAD POSE: SLIDING WINDOW & ANOMALY =======================

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
     * Standard deviation of head pose (degrees) and of EAR over the sliding window.
     * Returns null when there is not enough data yet.
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

    // ======================= ACCUMULATING DISTRACTION ENGINE =======================

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

    // ======================= CALIBRATION & SEI 2.0 =======================

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

        // Opens the habit session and, when a profile exists, blends the base EAR with the learned value.
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
        // Ideal zone follows the current thresholds (default 12..25, personalised once the profile has learned).
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

    /** Composite fatigue index 0-100 (PERCLOS, blink duration, yawn, BPM, long closures). */
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
     * Time-based smoothing coefficient. perFrameAt30fps is the familiar coefficient when the machine
     * runs at 30 FPS; this returns the equivalent coefficient for the real interval between two frames,
     * so SEI and the fatigue index react at the same speed whether the phone does 12 or 30 frames/s.
     */
    private fun frameAlpha(perFrameAt30fps: Float): Float {
        val a = perFrameAt30fps.coerceIn(0.001f, 0.99f)
        val tauMs = -33.3f / ln(1f - a)
        return (1f - exp(-frameDtMs / tauMs)).coerceIn(0f, 1f)
    }

    private fun updateFps(nowMs: Long) {
        if (prevFrameTimeMs != 0L) {
            val dt = nowMs - prevFrameTimeMs
            // updateFps runs for EVERY frame delivered, including frames with no face, so a gap larger than
            // WINDOW_STALL_MS means the machine received no frame at all: the app was pushed to the
            // background, CameraX stopped, possibly for tens of minutes. The session clock (HUD, the
            // session_s column, the 20 minute break reminder) must jump over that dead span, otherwise
            // one screen-off in the middle of a lesson reports "monitored for 40 minutes" when only a few
            // minutes had real images.
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

    // ======================= FRAME PROCESSING =======================

    /** Clears the instant counters when the face is lost (avoids a fake event when it returns). */
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
        // The "too close" flag is a CONCLUSION drawn from a distance measurement. Once the face is gone
        // there is no measurement left, so it has to switch off too: if it stayed, the first frames after
        // the face returns would still lose 15 SEI points for "standing too close" while the app has no
        // idea how far the student actually sits from the screen.
        distanceAlertActive = false
        // Distance calibration samples are only comparable with each other while the face stays
        // continuously in one pose. Losing the face midway throws them away: keeping them would blend
        // two different sitting distances into a median that describes neither of them.
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

    /** Updates the face bounding box (normalized coordinates of the rotated image), smoothed so the ROI holds. */
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

        // Widened by 35% on each side so the ROI includes hair, chin, glasses and part of the neck.
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

        // Leaving the screen for long enough counts as having taken a break.
        if (faceLostSinceMs == 0L) {
            faceLostSinceMs = nowMs
        }
        if (calibrated && nowMs - faceLostSinceMs >= BREAK_DETECT_MS) {
            workStartMs = 0L
        }

        // Losing the face is not "focus equals zero": the chart keeps "--" instead of falling to 0,
        // because this fake low SEI would enter the average, the minimum and the habit profile.
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
            // The "calibration just deviated by 20%" reminder is an operator matter, so it must still be on the
            // HUD after that student stands up; only the rolling test loses its value when the face is lost.
            personHint = if (personHintKind == 2) personHintPlain else null,
            // The band line stays as is when the face is lost: it describes the session, not this frame.
            // Letting it vanish on every turn of the head would only make the HUD flicker.
            bandSummary = bandSummaryPlain
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

        // The face came back.
        faceLostSinceMs = 0L
        updateFaceBox(lm, nowMs)
        if (calibrated && workStartMs == 0L) {
            workStartMs = nowMs
        }

        // ---- EAR ----
        val ear1 = calculateEAR(lm, RIGHT_EYE, imgW, imgH)
        val ear2 = calculateEAR(lm, LEFT_EYE, imgW, imgH)

        // ---- 3D head pose (moved earlier so distance can be corrected for the turn angle) ----
        computeHeadPose(lm, imgW, imgH)
        val yawRawDev = poseYawAbs - baselineYawDeg
        val pitchRawDev = posePitchAbs - baselinePitchDeg

        // ---- Distance (real focal length + iris + eye corners, median then One Euro) ----
        val faceWidthPx = pixelDistance(lm, LM_OUTER_RIGHT_EYE, LM_OUTER_LEFT_EYE, imgW, imgH)
        // The eye-closed threshold must come from this person's own calibration (baselineEar x 0.68,
        // usually 0.17-0.22). The old 0.16 constant sat below the real threshold, so it still let
        // through frames with nearly closed eyes - exactly when the iris landmarks collapse.
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

        // Calibration: takes the median of many frames instead of trusting one noisy frame.
        if (rawUnscaled != null && distanceCalibrationRequested) {
            if (nowMs - distanceCalRequestedMs > DIST_CAL_WINDOW_MS) {
                // A calibration request that is too old: its DIST_CAL_SAMPLES were spread over
                // DIST_CAL_WINDOW_MS (8 seconds), in different poses, no longer describes "sitting
                // 50 cm from the screen". Using them would change the distance factor silently and every
                // later number in the session would be wrong along with it.
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
                    // Changing the factor changes the very scale that raises the "too close" flag, so the flag must
                    // go off: if it stayed, a student sitting correctly at 50 cm would keep hearing the siren.
                    distanceAlertActive = false
                    distanceFilter.reset()
                    personGuard.reset()
                    personHintPlain = null
                    personHintKind = 0

                    // The factor now belongs to the profile, not to the phone. It is written into
                    // HabitProfile and saved to file right away: kept only in memory, the next time the app
                    // opens it would still use another student's number.
                    val prof = habitProfile
                    val storedScale = prof?.distanceScale
                    // When storedScale is valid, prof is valid too (it was just read from prof), so prof is not
                    // checked a second time: an always-true condition hides which test actually decides, and the
                    // compiler was already warning about this exact spot.
                    val calDev = if (storedScale != null && prof.hasCalibration()) {
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
                        // This is the most trustworthy trigger: calibration takes place at a distance that is ALREADY
                        // KNOWN, so a factor jumping 20% can only mean a different face, or this student sitting in
                        // the wrong place relative to the instructions.
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

        // ---- Suspected person change (operator reminder, not a distraction alert) ----
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

        // ---- Filtered pose angles used for scoring ----
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

        // ---- Raw gaze from the iris (468-477), One Euro filtered ----
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

        // ---- Raw micro-expressions: BFR and MAR ----
        val bfr = pixelDistance(lm, LM_BROW_INNER_R, LM_BROW_INNER_L, imgW, imgH) /
                faceWidthPx.coerceAtLeast(1f)

        val mouthWidthPx = pixelDistance(lm, LM_MOUTH_R, LM_MOUTH_L, imgW, imgH)
        val mar = if (mouthWidthPx < 1f) {
            0f
        } else {
            pixelDistance(lm, LM_MOUTH_UP, LM_MOUTH_LOW, imgW, imgH) / mouthWidthPx
        }

        // ---- Signal quality ----
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
            // ---- Calibration phase: only valid frames and a signal good enough ----
            if (calWaitStartMs == 0L) {
                calWaitStartMs = nowMs
            }
            val relaxed = calFrames < CAL_RELAX_MIN_FRAMES &&
                    nowMs - calWaitStartMs >= CAL_RELAX_AFTER_MS
            val minEar = if (relaxed) CAL_RELAXED_MIN_EAR else CALIBRATION_MIN_EAR

            // The head must stay reasonably still relative to the frames already accepted, so the baseline (pose,
            // gaze) is not dragged off when the student is turning away or bowing during calibration.
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
                // Rejected for about 3 straight seconds (a seating change): drop the old data and calibrate anew.
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

            // ---- Upgraded eye analysis: merges the 5 eyelid point pairs + blendshape, compensates tilt and bow ----
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

            // ---- PERCLOS: three thresholds in parallel ----
            if (eye.valid) {
                perclos.record(nowMs, eyesClosed)
                perclosP70.record(nowMs, eye.openness <= PERCLOS_P70_OPEN)
                perclosP80.record(nowMs, eye.openness <= PERCLOS_P80_OPEN)
            } else {
                perclos.pause()
                perclosP70.pause()
                perclosP80.pause()
            }
            // PERCLOS stays null while there are too few samples: the HUD must show "--", not "0.0 %",
            // because 0% sounds like "wide awake" when in reality nothing has been measured yet.
            val perclosSample = perclos.percentOrNull(nowMs)
            val perclosValue = perclosSample ?: 0f
            perclosOut = perclosSample
            perclosP70Out = perclosP70.percentOrNull(nowMs)
            perclosP80Out = perclosP80.percentOrNull(nowMs)

            // ---- 60 second sliding window (BPM) ----
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

            // ---- Head turned away from the screen ----
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

            // ---- Pose sliding window (raw angles): frozen or sudden jerk ----
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

            // ---- Gaze and Visual Drift ----
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

            // ---- Micro-expressions: frown (BFR) and yawn (MAR) ----
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

            // A wide open mouth (blendshape jawOpen) counts as a yawn too: faster and firmer than geometry.
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

            // ---- Violations recorded on the rising edge (each burst counted once) ----
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

            // ---- Accumulating distraction engine ----
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

            // ---- Composite fatigue index ----
            fatigueOut = updateFatigue(
                perclosValue,
                averageBlinkMs(),
                yawnsRecent,
                bpmValue,
                bpmReliable,
                drowsyNow
            )

            // ---- 20-20-20 break reminder ----
            val workMs = if (workStartMs == 0L) 0L else nowMs - workStartMs
            var breakReminder = false
            if (workMs >= BREAK_REMINDER_MS) {
                if (workMs < BREAK_REMINDER_MS + BREAK_REMINDER_SHOW_MS) {
                    breakReminder = true
                } else {
                    workStartMs = nowMs
                }
            }

            // ---- SEI 2.0 index ----
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

            // Keeping the total from before the subtraction: otherwise the breakdown table prints numbers that
            // were subtracted twice whenever a penalty applies, and were blocked by the still-image check.
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

            // The way the score is computed is made public: the breakdown table is rebuilt only 2.5 times per
            // second so it does not allocate a new string every frame.
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

            // ---- Compared with habit: SEI falling below this student's own usual level ----
            val belowPersonal = reliable && !personalSeiMean.isNaN() &&
                    seiNow < personalSeiMean - LOW_VS_PERSONAL_K * personalSeiSpread
            if (belowPersonal) {
                if (lowVsPersonalStartMs == 0L) lowVsPersonalStartMs = nowMs
            } else {
                lowVsPersonalStartMs = 0L
            }
            val lowVsPersonal =
                lowVsPersonalStartMs != 0L && nowMs - lowVsPersonalStartMs >= LOW_VS_PERSONAL_MS

            // ---- Habit learning: only valid frames, no anomaly, no ongoing long distraction burst ----
            val hs = habitSession
            // The band codes must be reset EVERY FRAME. Otherwise a frame that is not scored carries the code of
            // the frame before, and whoever opens the CSV cannot tell "this frame was not measured" from
            // "same as the frame before" - two completely different conclusions when reconciling the numbers.
            for (f in HabitFeature.BAND_FEATURES) bandCodes[f] = CODE_BAND_NOT_SCORED
            if (hs != null && habitLearnEnabled) {
                // `eye.valid` here is the "no bad blink" wording that the explanation table promises. Without it,
                // a frame where EyeEngine itself says "I cannot conclude about these eyes" (jumping landmarks, too
                // steep an angle) still adds to validMs, so the valid-frame ratio inflates exactly when the signal
                // is poor - and PERCLOS had already been gated by eye.valid; only the time accounting was leaking.
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
                    // PERCLOS is only learned once the window holds enough samples: writing 0% during the first
                    // empty seconds teaches the profile that this student is "always awake", pulling alerts down.
                    perclosSample?.let { hs.observe(HabitFeature.PERCLOS, it) }
                    hs.observe(HabitFeature.SEI, seiNow.toFloat())
                    hs.addQuality(signalQuality.toFloat())

                    // Two-sided comparison against this student's own familiar band. Reuses exactly the sampling
                    // condition above: when BPM is not reliable the band must not be scored either - otherwise the
                    // band columns fill with codes on the very frames where the source index was skipped.
                    fun scoreBand(f: Int, sampled: Boolean, value: Float) {
                        bandCodes[f] = if (sampled) hs.observeBand(f, value).toCsvCode()
                        else CODE_BAND_NOT_SCORED
                    }
                    scoreBand(HabitFeature.BPM, bpmReliable, bpmValue)
                    scoreBand(HabitFeature.YAW, true, yawDeg)
                    scoreBand(HabitFeature.PITCH, true, pitchDeg)
                    scoreBand(HabitFeature.GAZE_H, irisAvailable && eyesOpenForGaze, offH)
                    scoreBand(HabitFeature.GAZE_V, irisAvailable && eyesOpenForGaze, offV)
                    scoreBand(HabitFeature.MAR, true, mar)
                    perclosSample?.let { scoreBand(HabitFeature.PERCLOS, true, it) }
                    scoreBand(HabitFeature.SEI, true, seiNow.toFloat())
                }
            }

            // ---- Priority alerts (distance / chronic / anomaly, each with its own line) ----
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

            // Alert type currently showing (0 = none / silent), with the same priority order as the alert chain.
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
            // luma is the last measured column: no trailing comma, and left empty when brightness is not finite.
            if (lumaMean.isFinite()) {
                b.append(String.format(Locale.US, "%.2f", lumaMean))
            }
            // The eight band columns come AFTER luma, never inserted in the middle, so the 37 measured columns
            // keep their order and files from earlier sessions still open with the same analysis script.
            for (f in HabitFeature.BAND_FEATURES) {
                b.append(',').append(bandCodes[f])
            }
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
            personHint = personHintPlain,
            bandSummary = bandSummaryPlain
        )
    }

    /** Runs on the UI thread: refreshes the text lines of the parameter panel (already rate limited). */
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
        bandText = s.bandSummary
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

        // This line runs on the 120 ms tick: it may only replace the text when no message is being held,
        // otherwise it erases every short alert line that was just printed.
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

    /** Runs on the UI thread: applies the state snapshot onto the interface. */
    private fun applySnapshot(s: HudSnapshot) {
        lastSnapshot = s

        faceCount = if (s.faceDetected) 1 else 0
        landmarkPoints = s.points
        frameWidth = s.frameWidth
        frameHeight = s.frameHeight

        if (s.blinkDetected) {
            blinkCount++
        }

        // Text only needs refreshing about 8 times per second; redrawing the whole panel 30 times per second wastes battery.
        val nowText = SystemClock.elapsedRealtime()
        if (nowText - lastUiTextMs >= UI_TEXT_REFRESH_MS) {
            lastUiTextMs = nowText
            applyHudText(s)
        }

        // SEI history: one sample per second, keeping the most recent HISTORY_SIZE samples.
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

        // Sound played when a severe state is entered.
        val chronicRising = s.chronicLevel == 2 && lastChronicLevelUi != 2
        val tooCloseRising = s.tooClose && !lastTooCloseUi
        // Counts occurrences per alert type; only enough occurrences make a sound.
        val code = s.alertCode
        if (code > 0) {
            val isNew = nowUi - lastKindActiveMs[code] > ALERT_REARM_MS
            lastKindActiveMs[code] = nowUi
            if (isNew) {
                if (registerAlertOccurrence(code, nowUi)) {
                    playAlertSound(alertLevelOf(code), nowUi)
                }
            } else if (code == KIND_DROWSY) {
                // Still closing the eyes for a long time: repeat the reminder on a cycle.
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

    /** Builds the calibration string from the snapshot only (safe to run on the UI thread). */
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

    /** Converts the MediaPipe landmark list into a flat array that reuses the buffer. */
    private fun toLandmarks(
        list: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): Landmarks {
        val needed = list.size * 3
        // A fresh array every frame (the UI thread reads it in parallel); one flat allocation instead of ~1,400 objects.
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
     * Takes 5 blendshape coefficients: [left blink, right blink, left squint, right squint, jaw open].
     * Returns null when FaceLandmarker has not been given setOutputFaceBlendshapes(true) - then the eye
     * analysis falls back to the geometry-only mode by itself.
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

            // Converts YUV directly into an RGB Bitmap, without JPEG compression.
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

            // All the distance / EAR / pose / gaze / SEI math runs here (cameraExecutor).
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

    // Reads YUV_420_888 taking rowStride and pixelStride into account.
    // No intermediate JPEG is created.
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height

        // Records the raw frame size to compute the pixel focal length.
        streamW = width
        streamH = height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Copies each plane once into a reused array (faster than calling get() byte by byte).
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

        // Low-level image work on the luminance channel (light measured over the face, adaptive CLAHE when dark).
        // lumaMean is the ORIGINAL brightness (before enhancement) so it reflects the real lighting condition.
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
        // Saves the session being learned when leaving the screen (runs on cameraExecutor so it does not race
        // the data), and sends the summary right away: for the user, switching the screen off is when a
        // measurement ends - endHabitSession only runs on a student switch or a recalibration. Two sends
        // of the same session still collapse into ONE Firestore record because the document name is deterministic.
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            try {
                cameraExecutor.execute {
                    val session = habitSession
                    val bands = session?.bandOutcomes() ?: emptyList()
                    commitHabitSession()
                    if (session != null) uploadSessionToCloud(session.toSummary(), bands)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không lưu được phiên khi dừng", e)
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        previewView = null

        // The last few dozen rows are still inside the BufferedWriter buffer. If the app is destroyed without
        // stop(), that part of the session just measured is lost - and it is the most important part,
        // because it is the final minutes of the session.
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
                // Closes the model with a task queued LAST, instead of closing it right on the UI thread.
                // awaitTermination only waits a bounded time while one heavy frame can take longer; running
                // close() while native is still detecting gives the process a SIGSEGV - that is a crash exactly
                // when switching to the other screen on stage. A single-thread executor means this task
                // is certain to run after the very last frame.
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
                    // Frames still running after 1.2 seconds: skip closing the model and let the process free it
                    // rather than close while native is using it - a leaked model only costs RAM, while a
                    // SIGSEGV loses the whole session that is being written to CSV.
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

    // ==================== RESEARCH DATA LOGGING ====================

    /** One numeric column in the CSV. An empty cell means "not measurable", which is not the same as 0. */
    private fun StringBuilder.num(v: Float?) {
        // isFinite rather than !isNaN: "Infinity" in a CSV cell holds no comma so it slips through the
        // column count check, and Excel reads it as text - the whole column average then fails silently.
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
     * Strips commas, double quotes and line breaks out of any text so it cannot break the CSV shape.
     * A line break matters as much as a comma: one message row containing `\n` splits the file in two, and
     * Excel reads the second half as a ragged record with the wrong column count - nothing signals the error.
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
     * Environment information of the recording session, written separately into meta.txt because the CSV
     * must stay clean for a machine. Without these numbers the data cannot be reproduced.
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
     * Records an alert that the person being monitored believes was wrong, together with every index of
     * that frame. This is the cheapest real data source there is for tuning the thresholds.
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

    /** CSV row for a frame without a face: only elapsed time, sei and the environment still mean anything. */
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

    // The SEI breakdown table and the tail-of-file reader live in FocusCore.kt (pure code, testable
    // on a computer through HabitTimingTest).

    // ==================== PRE-DEMO CHECK ====================

    /**
     * Press once: set the phone where it will sit on the table, then stay still for 20 seconds.
     * The app measures the things that actually break on stage (slow device, low light, no calibration,
     * flat battery, file not writable) instead of letting anyone guess.
     */
    private fun startPreflight() {
        // Pressing repeatedly must not restart the measurement: every press would wipe the 20 seconds already
        // accumulated, and on stage the person pressing would only ever see a progress line that never ends.
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

        // The counters only advance when a new frame arrives. If the camera dies midway this referee
        // finalises the result, so the table cannot hang on "measuring..." right on stage.
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
        // Signal quality only means something while a face is visible; frames without a face are stamped 0,
        // and averaging them in means a few seconds turned away makes the check falsely report low light.
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
        // null means the window has too few samples; a real 0% (awake, eyes wide) still counts as measured.
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

    /** One result row: kind = 0 fine, 1 worth noting, 2 failing. */
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

        // The part that really got measured during those 20 seconds.
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

        // The environment part: re-checked at the moment the run ends. These items must also feed the
        // conclusion - printing them only would allow a red "camera permission not granted" directly
        // under the "READY FOR THE DEMO" row.
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

    /** Items independent of the frame cadence: permissions, model, free space, battery, alert sounds. */
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

    // ==================== LATENCY HISTOGRAM ====================

    /** Counts samples into 12 buckets of 15 ms, holding exactly the last 240 frames. */
    private fun addLatencySample(ms: Float) {
        if (!ms.isFinite() || ms < 0f) {
            return
        }
        val bin = (ms / LATENCY_BIN_MS).toInt().coerceIn(0, LATENCY_BINS - 1)
        if (latencyFilled == LATENCY_SAMPLES) {
            // Ring already full: the sample sitting at latencyWriteIdx is precisely the oldest one and must be
            // the one subtracted. Subtracting from any bucket looks fine, but the lowest bucket fills first and
            // is always cleared first, so the histogram drifts steadily toward the slow side.
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

    // ==================== CURRENT THRESHOLDS ====================

    /**
     * Lists every number currently driving the result, with its source (default or personal profile) and
     * its verification status. Written down so that nobody - including me - can overstate how trustworthy it is.
     */
    private fun buildThresholdRows(): String = buildString {
        fun line(name: String, current: Float, default: Float, note: String, unit: String = "") {
            // "personal profile" on its own is the most misleading phrase in this whole table: the blended
            // threshold is not 100% that student's numbers, so the exact blend percentage must be printed too.
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
        // Converting frame counts into seconds must use the real cadence of the machine. Assuming 30 FPS, a
        // phone running at 18 FPS would print "2 s" while the student actually sat there for 3.3 s.
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
        appendLine()
        appendLine()
        // The band table is built by cameraExecutor every second and read across like any other HUD text line.
        // It stands AFTER the thresholds because it only describes: no threshold in the app changes because of it.
        val bandTable = bandTablePlain
        if (bandTable.isNotEmpty()) {
            append(bandTable)
        }
    }

    /**
     * Refreshes the numbers that only live in the side panel. Called from applyHudText so it is already
     * clamped to about 8 times per second; the threshold table and the histogram are only built while the
     * index tab is open, so no effort goes to a screen that is currently covered.
     */
    private fun refreshPanelExtras() {
        if (panelTab != 2) {
            return
        }
        // applyHudText runs about 8 times per second; the long text table only needs one pass per second.
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
     * Records events by second. Each type gets only one point within a second: otherwise one long
     * sleepiness spell draws 1800 overlapping bars and the chart becomes a solid block of colour.
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

    /** Turns an event into a 0..1 position on the time axis, dropping anything outside the window. */
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

    // ==================== RAW DATA VIEWER ====================

    /**
     * Reads the last few rows of the log file. This touches flash, so it runs on a separate thread and hands
     * the result back to the UI thread; reading inside the draw frame would freeze the phone on every tap.
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
                            // null = the file exists but cannot be touched. Saying plainly that it cannot be read is better than
                            // leaving the table empty - an empty table is easily read as "nothing has been written yet".
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

    /** Explanation for every column, written so nobody has to guess when opening the file on a computer. */
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
        appendLine("  band_<tên>    8 cột cuối: giá trị khung đó nằm ở đâu SO VỚI DẢI RIÊNG của em này -")
        appendLine("                  0 = dưới dải, 1 = trong dải, 2 = trên dải,")
        appendLine("                  -1 = chưa học được dải (hồ sơ còn thiếu phiên), -2 = khung này không chấm")
        appendLine("                  Dải = mức điển hình ±2.5 lần dao động của chính em đó. Đây là mô tả,")
        appendLine("                  không phải ngưỡng cắt cảnh báo: cột band_* và cột alert_code độc lập nhau.")
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

    // ==================== DEMO SCRIPT ====================

    private fun toggleDemoStep(index: Int) {
        demoDone = demoDone xor (1 shl index)
    }

    // ==================== INDEX EXPLANATION ====================

    /** Snapshot of every number currently on screen, for pasting into the written report. */
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

/** The five frames of the control panel, in the same order as when (panelTab). */
private val PanelTabs = listOf("Theo dõi", "Chuẩn bị", "Chỉ số", "Diễn giải", "Kịch bản")

/**
 * Demo script: each row is one action and the exact reaction the HUD must show.
 * Used for rehearsing beforehand and for comparing notes when something on stage differs.
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
 * Explains every index for the judges. Each entry states what is actually measured and what is only an
 * assumption, because overstating one sentence here is easier than fixing an algorithm later.
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
        "đường cơ sở riêng khi có >= 5 phiên đạt và tổng >= 10 phút hợp lệ; từ đó ngưỡng chung được trộn " +
        "với ngưỡng của cá nhân theo độ tin, độ tin tăng dần theo số phiên. HUD nói rõ đang có bao nhiêu " +
        "trên tổng 8 ngưỡng là của riêng bạn - nếu thấy 0/8 là vì hồ sơ chưa đủ điều kiện, không phải vì " +
        "app không học.",
    "Dải cho phép của từng em (hai phía)" to
        "Với mỗi đặc trưng, app dựng một dải [thấp nhất – cao nhất] từ thói quen của đúng em đó: " +
        "mức điển hình ±2.5 lần dao động tự nhiên, kẹp trong khoảng vật lý của chỉ số. Mỗi khung hình " +
        "được gắn mã dưới/trên/trong dải, ghi vào CSV và gửi kèm bản tổng kết. Tab 'Chỉ số' hiển thị " +
        "tỉ lệ thời gian của ba loại đó, nên một hôm khác thường hiện ra rõ ràng thay vì bị đường " +
        "trung bình của cả phiên che mất. Bảng này MÔ TẢ, không cắt cảnh báo: alert_code không đổi " +
        "vì nó. Khoảng cách tới màn hình, độ dài một cái chớp, BFR và EAR cố ý không có dải riêng - " +
        "xem mục 'Học thói quen theo hồ sơ'.",
    "Giáo viên thấy gì lúc đang học" to
        "60 giây sau khi một phiên bắt đầu và mỗi 5 phút sau đó, app ghi đè lên một bản ghi nhỏ " +
        "tên là 'presence' của riêng em đó: tên, nhịp lúc nào, phiên dài tới đâu, SEI trung bình " +
        "tới thời điểm đó và vài con số khác. Màn hình giáo viên vì thế thấy 'em này đang được đo' " +
        "mà không phải chờ tới lúc phiên kết thúc. Một phiên 40 phút thêm khoảng 8 lần ghi. " +
        "Điều quan trọng nhất để đọc đúng bảng này: nhịp hẹn CHỈ gửi được khi camera đang chạy, " +
        "tức là màn hình bật và app ở tiền cảnh. Vì vậy một nhịp đứng yên nghĩa là 'không đo được' " +
        "(tắt màn hình, app bị Android cấm chạy nền, hết pin), KHÔNG phải 'em đó đang làm việc khác'.",
    "Mất khuôn mặt thì sao" to
        "App giữ nguyên dấu '--' cho SEI và không tính khoảng trống đó là thời gian mở mắt hay mất " +
        "tập trung. Trước đây khoảng trống bị suy diễn thành 'bằng chứng không chú ý', gây báo đỏ " +
        "giả khi học sinh với sách hoặc quay sang bạn - nay đã sửa: mất tín hiệu là thiếu dữ liệu.",
    "Quyền riêng tư và dữ liệu" to
        "Toàn bộ suy luận chạy trên máy; ảnh không được lưu và không rời khỏi điện thoại. Có đúng " +
        "hai loại dữ liệu được gửi đi, cả hai đều là bản tổng kết chứ không phải khung hình: " +
        "(1) khi một phiên kết thúc, app ghi MỘT bản tổng kết lên Cloud Firestore - tên học sinh, " +
        "các giá trị trung bình của phiên, số cảnh báo và số lần bấm 'cảnh báo sai'; (2) giữa " +
        "chừng phiên, mỗi 5 phút app ghi đè một bản 'presence' nhỏ để giáo viên biết em đang được " +
        "đo. Ngoài hai loại bản ghi đó, app không tự gửi thêm dữ liệu nào khác. Mất mạng thì lệnh " +
        "ghi nằm trong hàng đợi ngoài luồng của Firestore và tự gửi lại khi có mạng. Sao lưu dự " +
        "phòng của hệ thống đã tắt (allowBackup=false), dữ liệu nằm trong thư mục riêng của app, " +
        "và văn bản chia sẻ chỉ gồm những dòng bạn tự bấm nút tạo ra."
)

/** Colour bands for the event rows, in the same order as the TL_BAND_* constants above. */
private val TimelineBands: List<Pair<String, Color>> = listOf(
    "Cảnh báo" to Color(0xFFE53935),
    "Kéo dài" to Color(0xFF8E24AA),
    "Mất mặt" to Color(0xFF757575),
    "Chớp mắt" to Color(0xFF00897B),
    "Quá gần" to Color(0xFFEF6C00)
)
