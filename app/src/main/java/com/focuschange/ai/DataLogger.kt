package com.focuschange.ai

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bộ ghi dữ liệu nghiên cứu: mỗi khung hình một dòng CSV.
 *
 * Chỉ ghi chỉ số, không ghi ảnh hay video, nên file không chứa thông tin định danh
 * và vẫn nằm trong thư mục riêng của app (không app nào khác đọc được).
 *
 * Thread camera chỉ ghép chuỗi rồi bỏ vào hàng đợi; việc đụng flash do thread khác
 * lo. Hàng đợi có giới hạn: khi ghi không kịp thì mất mẫu (có đếm) chứ không làm
 * giật hình, vì mất vài dòng còn phân tích được, còn app đứng thì mất cả phiên.
 */
internal class DataLogger {

    private companion object {
        const val DIR_NAME = "research"
        const val FRAMES_NAME = "frames.csv"
        const val ALERTS_NAME = "false_alerts.csv"
        const val META_NAME = "meta.txt"
        const val FLUSH_EVERY = 30
        const val MAX_QUEUE = 900
        const val TAG = "DataLogger"
    }

    /**
     * 37 cột. Thứ tự phải trùng khớp với chỗ ghép chuỗi trong MainActivity.
     *
     * Để lộ danh sách này (thay vì chỉ chuỗi tiêu đề) để những chỗ phải ghi một dòng
     * "trống" ghép theo TÊN cột. Đếm vị trí bằng tay thì chỉ cần thêm một cột ở giữa là
     * toàn bộ dữ liệu phía sau bị dồn lệch, và file đối chiếu sẽ sai mà không ai hay biết.
     */
    val columnNames: List<String> = listOf(
        "t_ms", "face", "ear1", "ear2", "openness", "sigma", "eye_valid",
        "base_ear1", "base_ear2", "close_thr1", "close_thr2",
        "yaw_deg", "pitch_deg", "roll_deg", "gaze_h", "gaze_v", "mar", "bfr",
        "dist_raw_cm", "dist_cm", "dist_scale", "focal_px",
        "perclos_p60", "perclos_p70", "perclos_p80",
        "bpm", "avg_blink_ms", "inattention_pct",
        "sei", "fatigue", "quality", "alert_code", "chronic",
        "session_s", "fps", "proc_ms", "luma"
    )

    val header: String = columnNames.joinToString(",")

    val falseAlertHeader: String = listOf(
        "wall_ms", "alert_code", "alert_text", "sei",
        "perclos_p60", "perclos_p80", "bpm", "ear1", "ear2",
        "yaw_deg", "pitch_deg", "distance_cm", "quality", "session_s"
    ).joinToString(",")

    @Volatile var isActive = false
        private set

    @Volatile var rows = 0L
        private set

    @Volatile var droppedRows = 0L
        private set

    /**
     * Số dòng bị loại vì số cột không khớp tiêu đề.
     *
     * Thà mất một dòng còn hơn ghi một dòng lệch cột: dòng thiếu cột khiến mọi giá trị phía
     * sau bị dồn sang cột khác, và khi mở file trên máy tính thì không ai thấy chỗ đó bị
     * lệch - chỉ thấy số liệu kỳ lạ. Tính vào đây để biết chắc file của phiên này sạch.
     */
    @Volatile var malformedRows = 0L
        private set

    private val queue = ConcurrentLinkedQueue<String>()
    private val queued = AtomicInteger(0)
    private var thread: Thread? = null

    /** Trả về đường dẫn file nếu ghi được, null nếu không. */
    fun start(context: Context, meta: String): String? {
        if (isActive) return null
        val dir = dirFor(context) ?: return null

        // Dọn xác của lần ghi trước TRƯỚC khi mở file mới.
        // stop() chỉ chờ pump cũ tối đa 2 giây; nếu hàng đợi vẫn còn dòng (flash chậm, hoặc
        // pump chết vì IOException khi file đang ghi), thì những dòng của phiên cũ sẽ bị
        // viết tiếp vào file của phiên mới - t_ms nhảy ngược về quá khứ và không ai biết dòng
        // nào thuộc phiên nào.
        // Tăng số phiên TRƯỚC khi chờ: pump cũ thấy số của nó không còn là hiện hành sẽ tự
        // dừng ngay thay vì chờ hết 2 giây, và không ai còn ghi vào file của phiên cũ.
        val gen = generation.incrementAndGet()
        val old = thread
        thread = null
        if (old != null) {
            try {
                old.join(1_000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        isActive = false
        while (queue.poll() != null) {
            // bỏ dòng thừa của phiên cũ
        }
        queued.set(0)

        return try {
            val file = File(dir, FRAMES_NAME)
            val w = BufferedWriter(FileWriter(file, false))
            w.write(header)
            w.newLine()
            w.flush()
            File(dir, META_NAME).writeText(meta)

            // frames.csv bị ghi đè mỗi lần bật ghi, nên false_alerts.csv cũng phải làm lại
            // từ đầu; nếu không nhãn của phiên hôm trước sẽ dính vào khung hình của phiên này.
            try {
                BufferedWriter(FileWriter(File(dir, ALERTS_NAME), false)).use {
                    it.write(falseAlertHeader)
                    it.newLine()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Không tạo lại được file nhãn báo sai", e)
            }

            rows = 0L
            droppedRows = 0L
            malformedRows = 0L
            isActive = true
            thread = Thread { pump(w, gen) }.apply {
                isDaemon = true
                name = "DataLogger"
                start()
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Không mở được file ghi", e)
            isActive = false
            null
        }
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        val t = thread
        thread = null
        try {
            t?.join(2_000L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun submit(line: String) {
        if (!isActive) return
        if (!csvShapeOk(line, columnNames.size)) {
            malformedRows++
            return
        }
        if (queued.get() >= MAX_QUEUE) {
            droppedRows++
            return
        }
        queue.add(line)
        queued.incrementAndGet()
    }

    /** Ghi ngay khi bấm nút "báo sai" - thưa nên không cần hàng đợi. */
    fun appendFalseAlert(context: Context, line: String): Boolean {
        val dir = dirFor(context) ?: return false
        return try {
            val f = File(dir, ALERTS_NAME)
            val first = !f.exists() || f.length() == 0L
            val w = BufferedWriter(FileWriter(f, true))
            if (first) {
                w.write(falseAlertHeader)
                w.newLine()
            }
            w.write(line)
            w.newLine()
            w.close()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Không ghi được báo sai", e)
            false
        }
    }

    /**
     * Số phiên ghi, tăng mỗi lần start(). pump() dùng nó để biết mình còn là người ghi hiện
     * hành hay đã bị một lần start() mới vượt qua.
     *
     * Không có đánh số này thì một pump cũ (ví dụ bị kẹt trong flush() hơn 2 giây nên stop()
     * hết chờ) sẽ chạy tiếp, ghi dòng cũ vào file mới, rồi khi thoát lại set isActive = false
     * - tắt luôn việc ghi của phiên mới đang chạy song song.
     */
    private val generation = AtomicInteger(0)

    private fun pump(w: BufferedWriter, gen: Int) {
        var sinceFlush = 0
        try {
            while (generation.get() == gen && (isActive || queue.isNotEmpty())) {
                val line = queue.poll()
                if (line != null) {
                    queued.decrementAndGet()
                    w.write(line)
                    w.newLine()
                    rows++
                    if (++sinceFlush >= FLUSH_EVERY) {
                        w.flush()
                        sinceFlush = 0
                    }
                } else {
                    Thread.sleep(30L)
                    if (sinceFlush > 0) {
                        w.flush()
                        sinceFlush = 0
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dừng ghi giữa chừng", e)
        } finally {
            try {
                w.flush()
                w.close()
            } catch (e: Exception) {
                Log.w(TAG, "Không đóng được file", e)
            }
            // Chỉ được tắt cờ ghi nếu vẫn là phiên của mình. Nếu một lần start() mới đã xen vào
            // thì pump cũ thoát ra ở đây mà set isActive = false sẽ âm thầm tắt luôn việc ghi
            // của phiên mới - màn hình vẫn báo "đang ghi" còn file thì không thêm dòng nào.
            if (generation.get() == gen) {
                isActive = false
            }
        }
    }

    private fun dirFor(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }

    /** File ảnh đang ghi (hoặc đã ghi lần cuối) để màn hình xem dữ liệu thô đọc lại. */
    fun framesFile(context: Context): File? = dirFor(context)?.let { File(it, FRAMES_NAME) }

    fun alertsFile(context: Context): File? = dirFor(context)?.let { File(it, ALERTS_NAME) }

    /**
     * Số dòng DỮ LIỆU đã nằm trên đĩa (không tính dòng tiêu đề), khác với [rows] ở chỗ không
     * đếm phần còn trong hàng đợi.
     *
     * Tiêu đề bị trừ ra vì hai con số này được in cạnh nhau để đối chiếu "trong máy đếm bao
     * nhiêu - trên đĩa bao nhiêu". Nếu để dòng tiêu đề thì một phiên hoàn toàn bình thường
     * luôn luôn lệch đúng 1, và người xem sẽ tưởng file ghi bị thiếu.
     */
    fun linesOnDisk(context: Context): Long {
        val f = framesFile(context) ?: return -1L
        if (!f.exists()) return -1L
        return try {
            f.inputStream().use { input ->
                var count = 0L
                val buf = ByteArray(8192)
                var read = input.read(buf)
                while (read > 0) {
                    for (i in 0 until read) {
                        if (buf[i] == '\n'.code.toByte()) count++
                    }
                    read = input.read(buf)
                }
                if (count == 0L) 0L else count - 1L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không đếm được dòng trên đĩa", e)
            -1L
        }
    }
}