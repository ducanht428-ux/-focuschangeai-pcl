package com.focuschange.ai

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import java.util.HashMap
import kotlin.math.roundToInt

/**
 * Những gì một phiên đo để lại trên đám mây. Đây là SỐ TRUNG BÌNH của cả phiên, không phải
 * từng khung hình: một phiên ~2.000 khung chỉ tốn một lần ghi Firestore.
 */
internal class SessionReport(
    val studentId: String,
    val studentName: String,
    val summary: SessionSummary,
    val alertsTotal: Int,
    val falseAlerts: Int,
    val csvRows: Long,
    val avgFps: Float,
    val device: String,
    val appVersion: String,
    val endedAtWallMs: Long,
    val ineligibleReason: String?
)

/**
 * Đồng bộ một bản tổng kết / một phiên lên Cloud Firestore.
 *
 * Viết tách ra khỏi MainActivity để phần còn lại của app vẫn chạy y nguyên khi không có
 * Firebase: thiếu file cấu hình, mất mạng, bị từ chối quyền - tất cả chỉ làm một dòng chữ
 * trong bảng chuyển sang màu đỏ, không làm hỏng buổi đo đang chạy.
 */
internal object FirebaseSync {
    private const val COLLECTION = "sessions"

    /** Phiên bản cấu trúc bản ghi. Tăng lên khi đổi tên cột để dữ liệu cũ không bị đọc lệch. */
    private const val SCHEMA = 1

    /** Khóa của 12 đặc trưng trong HabitFeature, theo đúng thứ tự chỉ mục. */
    private val FEATURE_KEYS = arrayOf(
        "blink_bpm", "avg_blink_ms", "distance_cm", "yaw_deg", "pitch_deg", "gaze_h",
        "gaze_v", "mar", "bfr", "ear", "perclos_p60", "sei"
    )

    @Volatile
    var disabledReason = "Chưa bật"
        private set

    @Volatile
    private var db: FirebaseFirestore? = null

    @Volatile
    var sentCount = 0L
        private set

    @Volatile
    var failedCount = 0L
        private set

    @Volatile
    private var inFlight = 0

    @Volatile
    var lastError = ""
        private set

    val enabled: Boolean
        get() = db != null

    /**
     * Nối vào FirebaseApp mà ContentProvider của google-services.json đã khởi tạo sẵn.
     * getInstance() ném IllegalStateException khi không có file cấu hình: phải bắt lại ở đây,
     * vì đây là ranh giới với thư viện ngoài, không phải lỗi trong code của mình.
     */
    fun start(context: Context) {
        if (db != null) return
        try {
            com.google.firebase.FirebaseApp.initializeApp(context)
            db = FirebaseFirestore.getInstance()
            disabledReason = ""
        } catch (e: Exception) {
            db = null
            disabledReason = e.javaClass.simpleName
        }
    }

    /** Dựng bản ghi rồi gửi. [onResult] chạy trên luồng chính, đúng lúc cần đổi chữ trên màn hình. */
    fun upload(r: SessionReport, onResult: (Boolean) -> Unit) {
        val client = db
        if (client == null) {
            onResult(false)
            return
        }
        val payload = buildPayload(r)
        // Tên tài liệu tất định theo học sinh + phiên: gửi lại cùng một phiên (bấm hai lần,
        // hoặc Firestore gửi lại một lệnh chờ) là ghi đè, không phải tạo bản thứ hai.
        val docId = documentId(r.studentId, r.summary.id)
        inFlight++
        client.document("$COLLECTION/$docId").set(payload)
            .addOnSuccessListener {
                inFlight--
                sentCount++
                lastError = ""
                onResult(true)
            }
            .addOnFailureListener { e ->
                inFlight--
                failedCount++
                lastError = e.javaClass.simpleName
                onResult(false)
            }
    }

    private fun documentId(studentId: String, sessionId: Long): String {
        val safe = StringBuilder(studentId.length + 24)
        for (ch in studentId) {
            safe.append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-')
        }
        safe.append('-').append(sessionId)
        return safe.toString()
    }

    private fun buildPayload(r: SessionReport): HashMap<String, Any> {
        val s = r.summary
        val out = HashMap<String, Any>(48)
        out["schema"] = SCHEMA
        out["student_id"] = r.studentId
        out["student_name"] = r.studentName
        out["session_id"] = s.id
        out["ended_at_wall_ms"] = r.endedAtWallMs
        out["valid_ms"] = s.validMs
        out["face_ratio"] = round4(s.faceRatio)
        out["valid_ratio"] = round4(s.validRatio)
        out["quality_mean"] = round2(s.quality)
        out["alerts_total"] = r.alertsTotal
        out["false_alerts"] = r.falseAlerts
        out["csv_rows"] = r.csvRows
        out["avg_fps"] = round2(r.avgFps)
        out["device"] = r.device
        out["app_version"] = r.appVersion
        out["eligible"] = r.ineligibleReason == null
        if (r.ineligibleReason != null) {
            out["ineligible_reason"] = r.ineligibleReason
        }
        for (f in 0 until HabitFeature.COUNT) {
            val key = FEATURE_KEYS[f]
            // n = 0 nghĩa là đặc trưng đó không có mẫu nào trong phiên. Ghi 0 vào sẽ thành
            // "đo ra 0", nên chỉ ghi khi thực sự có mẫu.
            val n = s.count[f]
            if (n > 0) {
                out["mean_$key"] = round2(s.mean[f])
                out["std_$key"] = round2(s.std[f])
                out["n_$key"] = n
            }
        }
        return out
    }

    private fun round2(v: Float): Double = roundTo(v, 100f)

    private fun round4(v: Float): Double = roundTo(v, 10000f)

    private fun roundTo(v: Float, scale: Float): Double =
        if (v.isFinite()) (v * scale).roundToInt().toDouble() / scale.toDouble() else 0.0

    /** Một dòng ngắn cho bảng điều khiển: gửi được bao nhiêu phiên, còn gì đang chờ. */
    fun statusText(): String {
        if (db == null) {
            return "Firebase: TẮT ($disabledReason)"
        }
        val sb = StringBuilder("Firebase: đã gửi ").append(sentCount).append(" phiên")
        if (inFlight > 0) {
            sb.append(", đang gửi ").append(inFlight)
        }
        if (failedCount > 0L) {
            sb.append(", thất bại ").append(failedCount)
            if (lastError.isNotEmpty()) sb.append(" (").append(lastError).append(')')
        }
        return sb.toString()
    }
}
