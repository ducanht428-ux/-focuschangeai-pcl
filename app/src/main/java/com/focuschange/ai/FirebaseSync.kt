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
    val ineligibleReason: String?,
    /**
     * Kết quả đối chiếu phiên này với dải hai phía của chính học sinh đó. Rỗng khi hồ sơ còn
     * quá ít phiên để dựng dải.
     *
     * Để riêng, không gộp vào các bộ đếm cảnh báo: một phiên có thể không vượt ngưỡng cảnh báo
     * nào mà vẫn nằm ngoài dải quen thuộc - đúng cái mà con số trung bình của phiên che mất.
     */
    val bandOutcomes: List<BandOutcome>
)

/**
 * Một nhịp hẹn: "em này đang được đo, tới phút thứ bao nhiêu, số liệu đến lúc này ra sao".
 *
 * Gửi riêng, gọn hơn nhiều so với bản tổng kết phiên: màn hình giáo viên cần biết AI đang chạy
 * chứ không cần mười hai giá trị trung bình mỗi năm phút. Muốn số đầy đủ thì đọc bản ghi
 * `sessions` được ghi lúc phiên kết thúc.
 *
 * Không có `ineligible_reason` ở đây: phiên đang chạy thì hiển nhiên chưa đủ độ dài, nên mọi
 * nhịp đều sẽ bị đánh dấu "không đủ điều kiện" và giáo viên đọc thành "em này đo hỏng". Phán
 * quyết đủ/không đủ dành cho bản tổng kết cuối phiên.
 *
 * [sessionId] và [startedAtWallMs] trùng nhau về cấu tạo (HabitSession lấy giờ wall lúc mở phiên
 * làm id) nhưng ghi thành hai cột để bản ghi tự giải thích được, không bắt người đọc suy ra từ
 * một con số 13 chữ số.
 */
internal class HeartbeatReport(
    val studentId: String,
    val studentName: String,
    val sessionId: Long,
    val startedAtWallMs: Long,
    val beatAtWallMs: Long,
    /**
     * Thời gian phiên đếm được tới nhịp này, tính bằng cùng đồng hồ với các nhịp trước. Đây là
     * thứ tăng lên khi camera chạy và đứng yên khi màn hình tắt - cách ngắn nhất để phân biệt
     * "em này nghỉ giữa giờ" với "máy của em này đang đo".
     */
    val activeMs: Long,
    val summary: SessionSummary,
    val alertsTotal: Int,
    val falseAlerts: Int,
    val bandFrames: Int,
    val bandBelow: Int,
    val bandAbove: Int,
    val device: String,
    val appVersion: String
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

    /**
     * Một tài liệu duy nhất cho mỗi học sinh, được ghi đè bởi nhịp hẹn. Tách khỏi `sessions`
     * vì màn hình giáo viên cần câu "ai đang được đo ngay bây giờ" mà không phải quét và lọc
     * cả một bảng lịch sử.
     */
    private const val PRESENCE_COLLECTION = "presence"

    /**
     * Phiên bản cấu trúc bản ghi. Tăng lên khi đổi tên cột để dữ liệu cũ không bị đọc lệch.
     * Các cột dải mới chỉ THÊM vào, không đổi tên và không đổi nghĩa cột cũ, nên bản ghi đời
     * 1 vẫn đọc đúng - vì vậy ở đây vẫn là 1, không phải 2.
     */
    private const val SCHEMA = 1

    /**
     * Khóa của 12 đặc trưng. Lấy từ [HabitFeature.KEYS] chứ không liệt kê lại ở đây: tên khóa
     * trong Firebase và tên cột dải trong CSV phải là một, nếu tự viết hai bản thì sớm muộn
     * cũng lệch nhau một chữ và bản ghi trên mây không đọc được nữa.
     */
    private val FEATURE_KEYS = HabitFeature.KEYS

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

    /**
     * Nhịp hẹn đếm riêng, không gộp vào [sentCount]: hai loại bản ghi có nhịp gửi và hậu quả khác
     * nhau. Một nhịp thất bại chỉ nghĩa là "thầy chưa thấy em này lúc này", còn một phiên thất
     * bại nghĩa là mất số liệu. Gộp lại thì nhìn vào bảng không phân biệt được chuyện nào.
     */
    @Volatile
    var heartbeatSent = 0L
        private set

    @Volatile
    var heartbeatFailed = 0L
        private set

    @Volatile
    var lastHeartbeatError = ""
        private set

    /**
     * Dấu thời gian wall clock của nhịp gần nhất ĐÃ THỬ gửi (không phải nhịp đã được Firestore
     * chấp nhận). In ra tuyệt đối thay vì "cách đây n phút": một con số tương đối trên màn hình
     * chỉ được cập nhật khi có thứ khác vẽ lại, nên nó sẽ nói dối.
     */
    @Volatile
    var lastHeartbeatWallMs = 0L
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

    private fun documentId(studentId: String, sessionId: Long): String =
        sanitize(studentId) + '-' + sessionId

    /**
     * Đổi mọi ký tự không an toàn cho tên tài liệu Firestore thành dấu gạch ngang.
     * Viết một chỗ vì cả hai bảng đều cần: `sessions/<id>-<phiên>` và `presence/<id>`. Nếu mỗi
     * bảng tự lọc một kiểu thì tên học sinh có dấu cách sẽ tạo hai tài liệu khác nhau cho cùng
     * một em, và màn hình giáo viên chỉ thấy một.
     */
    private fun sanitize(studentId: String): String {
        val safe = StringBuilder(studentId.length + 8)
        for (ch in studentId) {
            safe.append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-')
        }
        return safe.toString()
    }

    /**
     * Ghi đè tài liệu `presence/<học sinh>` bằng một nhịp.
     *
     * Dùng `set` lên tên tài liệu tất định chứ không add(): mỗi em đúng một dòng "đang học",
     * nhịp sau thay nhịp trước, gửi trùng không tạo bản thừa. [onResult] chạy trên luồng chính.
     */
    fun uploadHeartbeat(r: HeartbeatReport, onResult: (Boolean) -> Unit) {
        val client = db
        if (client == null) {
            onResult(false)
            return
        }
        lastHeartbeatWallMs = r.beatAtWallMs
        client.document("$PRESENCE_COLLECTION/${sanitize(r.studentId)}").set(buildHeartbeatPayload(r))
            .addOnSuccessListener {
                heartbeatSent++
                lastHeartbeatError = ""
                onResult(true)
            }
            .addOnFailureListener { e ->
                heartbeatFailed++
                lastHeartbeatError = e.javaClass.simpleName
                onResult(false)
            }
    }

    private fun buildHeartbeatPayload(r: HeartbeatReport): HashMap<String, Any> {
        val s = r.summary
        val out = HashMap<String, Any>(24)
        out["schema_h"] = SCHEMA
        out["student_id"] = r.studentId
        out["student_name"] = r.studentName
        out["session_id"] = r.sessionId
        out["started_at_wall_ms"] = r.startedAtWallMs
        out["beat_at_wall_ms"] = r.beatAtWallMs
        out["active_ms"] = r.activeMs
        out["valid_ms"] = s.validMs
        out["face_ratio"] = round4(s.faceRatio)
        out["valid_ratio"] = round4(s.validRatio)
        // Hai cột này chỉ ghi khi thật sự có mẫu. round2(NaN) trả về 0, mà "SEI = 0" đọc thành
        // "em này ngủ gục", "chất lượng = 0" đọc thành "máy đo hỏng" - cả hai đều sai: sự thật là
        // chưa đo được gì. Thiếu cột là tín hiệu đúng, số 0 giả tạo là tín hiệu sai.
        // mean của một bộ tích lũy rỗng là NaN, nên isFinite chính là "có mẫu hay không".
        if (s.quality.isFinite()) {
            out["quality_mean"] = round2(s.quality)
        }
        val seiN = s.count[HabitFeature.SEI]
        if (seiN > 0) {
            // SEI là con số giáo viên nhìn đầu tiên, nên nó có cột riêng thay vì bắt người đọc
            // tra "mean_sei" như bảng sessions. Đây là trung bình của cả phiên tính tới nhịp này,
            // không phải của riêng 5 phút vừa rồi - muốn tốc độ ngắn hạn thì phải đọc CSV.
            out["sei_mean"] = round2(s.mean[HabitFeature.SEI])
            out["sei_n"] = seiN
        }
        out["alerts_total"] = r.alertsTotal
        out["false_alerts"] = r.falseAlerts
        out["device"] = r.device
        out["app_version"] = r.appVersion
        // Dải chỉ là ba số tổng hợp cho tới khi phiên kết thúc; chi tiết từng chỉ số nằm ở bản
        // ghi sessions, ghi lại mỗi năm phút là nhân ba lượng dữ liệu mà chẳng thêm thông tin.
        if (r.bandFrames > 0) {
            out["band_k"] = HabitConfig.BAND_K.toDouble()
            out["band_frames"] = r.bandFrames
            out["band_below"] = r.bandBelow
            out["band_above"] = r.bandAbove
        }
        return out
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
        if (r.bandOutcomes.isNotEmpty()) {
            // Hệ số nhân của dải đi kèm bản ghi: giáo viên đọc "dải ±2.5 lần dao động" thì mới
            // hiểu vì sao hai em có cùng trung bình lại có dải rộng khác nhau.
            out["band_k"] = HabitConfig.BAND_K.toDouble()
            for (b in r.bandOutcomes) {
                val key = FEATURE_KEYS[b.feature]
                // Ghi số khung, không ghi tỷ lệ: muốn tính trung bình cả tuần thì phải cộng các
                // số đếm, còn trung bình của mấy con số % đã làm tròn thì không có nghĩa gì.
                out["band_min_$key"] = round2(b.min)
                out["band_max_$key"] = round2(b.max)
                out["band_typical_$key"] = round2(b.mean)
                out["band_frames_$key"] = b.samples
                out["band_below_$key"] = b.below
                out["band_above_$key"] = b.above
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
        sb.append(" - nhịp hẹn: ").append(heartbeatSent).append(" đã gửi")
        if (heartbeatFailed > 0L) {
            sb.append(", ").append(heartbeatFailed).append(" thất bại")
            if (lastHeartbeatError.isNotEmpty()) sb.append(" (").append(lastHeartbeatError).append(')')
        }
        if (lastHeartbeatWallMs > 0L) {
            sb.append(", lần thử gần nhất ")
                // Dựng formatter tại chỗ: SimpleDateFormat không an toàn giữa các luồng, mà dòng
                // trạng thái này có thể được gọi từ cả luồng camera lẫn luồng chính.
                .append(
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(lastHeartbeatWallMs))
                )
        }
        return sb.toString()
    }
}
