package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Test hai hàm dựng ra chữ cho người khác đọc: bảng phân tách điểm SEI và bộ đọc dòng cuối
 * file ghi. Lý do tồn tại: hai chỗ này in số, mà số in sai thì không ai phát hiện bằng mắt -
 * nó chỉ âm thầm làm bảng tự mâu thuẫn.
 */
class ReportFormatterTest {

    private val weights = floatArrayOf(0.20f, 0.15f, 0.20f, 0.20f, 0.10f, 0.15f)

    private fun numAfter(text: String, label: String): Float {
        val match = Regex("$label(-?\\d+\\.\\d+)").find(text)
        check(match != null) { "Không thấy '$label' trong bảng:\n$text" }
        return match.groupValues[1].toFloat()
    }

    private fun breakdown(
        close: Float,
        chronic: Float,
        frozen: Boolean,
        weightedSum: Float,
        finalRaw: Float
    ): String = buildSeiBreakdown(
        posture = 80f,
        dist = 90f,
        gaze = 70f,
        eyes = 60f,
        blinkRate = 100f,
        expression = 85f,
        weights = weights,
        closePenalty = close,
        chronicPenalty = chronic,
        frozen = frozen,
        anomalyCap = 30f,
        weightedSum = weightedSum,
        finalRaw = finalRaw,
        sei = finalRaw.toInt().coerceIn(0, 100)
    )

    /**
     * Tổng có trọng số, các mức trừ và điểm cuối phải khớp nhau. Bản cũ cộng ngược mức trừ
     * hai lần nên dòng "tổng" in ra cao hơn thực tế đúng bằng số bị trừ - bảng tự mâu thuẫn
     * mà mắt thường không thấy.
     */
    @Test
    fun totalsAgreeAfterPenalties() {
        val weighted = 78.25f
        val finalRaw = weighted - 15f - 5f
        val text = breakdown(close = 15f, chronic = 5f, frozen = false, weightedSum = weighted, finalRaw = finalRaw)

        assertEquals(weighted, numAfter(text, "Tổng có trọng số: "), 0.06f)
        assertEquals(finalRaw, numAfter(text, "Điểm đưa vào làm mượt: "), 0.06f)
        assertTrue(text.contains("ngồi quá gần"))
        assertTrue(text.contains("mất tập trung kéo dài"))
    }

    /** Không có mức trừ thì bảng không được nhắc tới mức trừ. */
    @Test
    fun noPenaltyRowsWhenNothingIsSubtracted() {
        val text = breakdown(close = 0f, chronic = 0f, frozen = false, weightedSum = 78.25f, finalRaw = 78.25f)
        assertFalse(text.contains("ngồi quá gần"))
        assertFalse(text.contains("mất tập trung kéo dài"))
        assertFalse(text.contains("chặn ở"))
    }

    /** Ảnh bị nghi là ảnh tĩnh phải hiện đúng mức chặn, không nói chung chung. */
    @Test
    fun frozenShowsTheCap() {
        val text = breakdown(close = 0f, chronic = 0f, frozen = true, weightedSum = 78.25f, finalRaw = 30f)
        assertTrue(text.contains("chặn ở 30"))
    }

    /** Sáu dòng thành phần phải cộng lại đúng bằng tổng in ra - đây là bảng giám khảo soi. */
    @Test
    fun componentRowsSumToTheTotal() {
        val text = breakdown(close = 0f, chronic = 0f, frozen = false, weightedSum = 78.25f, finalRaw = 78.25f)
        var sum = 0f
        var rows = 0
        for (line in text.lineSequence()) {
            val match = Regex("=\\s+(\\d+\\.\\d)$").find(line.trim())
            if (match != null) {
                sum += match.groupValues[1].toFloat()
                rows++
            }
        }
        assertEquals(6, rows)
        assertEquals(78.25f, sum, 0.06f)
    }

    private fun writeTemp(lines: List<String>): File {
        val f = File.createTempFile("frames", ".csv")
        f.deleteOnExit()
        f.writeText(lines.joinToString("\n") + "\n")
        return f
    }

    /**
     * Đọc phần cuối file và yêu cầu phải đọc được: bộ đọc trả về `null` khi không có gì để đọc,
     * mà test thì không được phép coi `null` là "rỗng" - như vậy là bỏ sót đúng cái lỗi cần bắt.
     */
    private fun tailOf(file: File, wanted: Int, windowBytes: Long = 96L * 1024L): List<String> =
        checkNotNull(readTailLines(file, wanted, windowBytes)) {
            "readTailLines trả về null cho ${file.name}"
        }

    /** Đọc đủ số dòng cuối, theo đúng thứ tự chúng xuất hiện trong file. */
    @Test
    fun tailReturnsLastLinesInOrder() {
        val lines = (1..25).map { "row_%02d,1,2,3".format(it) }
        val tail = tailOf(writeTemp(lines), 5)
        assertEquals(listOf("row_21,1,2,3", "row_22,1,2,3", "row_23,1,2,3", "row_24,1,2,3", "row_25,1,2,3"), tail)
    }

    /**
     * Khi cửa sổ đọc bắt đầu giữa một dòng thì dòng bị cắt phải bị loại, không được hiển thị
     * như một dòng dữ liệu thật - một giá trị cụt trong bảng là đủ để hiểu sai cả phiên.
     */
    @Test
    fun partialFirstLineOfTheWindowIsDropped() {
        val lines = (1..200).map { "row_%03d,1,2,3,4,5,6,7,8,9,10".format(it) }
        val tail = tailOf(writeTemp(lines), 6, windowBytes = 200L)
        val known = lines.toSet()
        assertTrue("cửa sổ quá nhỏ để còn dòng nào", tail.isNotEmpty())
        assertTrue("có dòng bị cắt: $tail", tail.all { it in known })
        assertTrue(tail.size <= 6)
    }

    /** File rỗng, file một dòng và yêu cầu đọc không dòng nào đều trả về kết quả an toàn. */
    @Test
    fun degenerateInputsReturnEmpty() {
        val empty = File.createTempFile("empty", ".csv")
        empty.deleteOnExit()
        assertEquals(emptyList<String>(), tailOf(empty, 10))
        assertEquals(emptyList<String>(), tailOf(writeTemp(listOf("chỉ một dòng")), 0))
        assertEquals(listOf("chỉ một dòng"), tailOf(writeTemp(listOf("chỉ một dòng")), 10))
    }

    /** Dòng trống cuối file (do ký tự xuống dòng thừa) không được xuất hiện thành dòng rỗng. */
    @Test
    fun trailingNewlineDoesNotYieldBlankLine() {
        assertEquals(listOf("a,1", "b,2"), tailOf(writeTemp(listOf("a,1", "b,2")), 10))
    }

    /**
     * Khi cửa sổ đọc bắt đầu đúng vào đầu một dòng thì dòng đó nguyên vẹn và phải được giữ.
     * Quy tắc "cứ bỏ dòng đầu" trước đây làm mất một dòng thật mỗi khi điểm bắt đầu rơi trúng
     * ranh giới - với dòng CSV ngắn thì chuyện này xảy ra rất thường.
     */
    @Test
    fun windowStartingOnALineBoundaryKeepsThatLine() {
        // Mỗi dòng đúng 6 byte: "ROW" + 2 số + xuống dòng. 20 dòng = 120 byte.
        val lines = (1..20).map { "ROW%02d".format(it) }
        val f = File.createTempFile("boundary", ".csv")
        f.deleteOnExit()
        f.writeText(lines.joinToString("") { it + "\n" })
        assertEquals(120L, f.length())
        // Cửa sổ 24 byte -> start = 96 = 16 * 6, ngay đầu dòng ROW17: bốn dòng phải còn đủ.
        assertEquals(listOf("ROW17", "ROW18", "ROW19", "ROW20"), tailOf(f, 10, windowBytes = 24L))
        // Cửa sổ 23 byte -> start = 97, cắt giữa dòng ROW17: dòng cụt phải bị bỏ.
        assertEquals(listOf("ROW18", "ROW19", "ROW20"), tailOf(f, 10, windowBytes = 23L))
    }

    /**
     * "Không có file" và "file chưa có dòng nào" là hai kết luận khác nhau khi đối chiếu số,
     * nên hàm đọc phải trả về hai giá trị khác nhau. UI dựa vào `null` để in câu "không đọc
     * được" thay vì hiện bảng tiêu đề và làm người bấm tưởng app chưa ghi gì.
     */
    @Test
    fun missingFileIsReportedSeparatelyFromAnEmptyOne() {
        val f = File(System.getProperty("java.io.tmpdir"), "khong_ton_tai_${System.nanoTime()}.csv")
        assertNull(readTailLines(f, 10))
        val empty = File.createTempFile("empty", ".csv")
        empty.deleteOnExit()
        assertEquals(emptyList<String>(), readTailLines(empty, 10))
    }

    /**
     * Dòng "mất mặt" phải có đúng số cột của tiêu đề, nếu không thì mở file lên Excel sẽ
     * dồn lệch cả những khung hình tốt đứng phía sau.
     */
    @Test
    fun noFaceRowHasExactlyOneFieldPerHeaderColumn() {
        val cols = DataLogger().columnNames
        val row = buildNoFaceCsvRow(
            columns = cols,
            out = StringBuilder(),
            nowMs = 12_345L,
            sei = null,
            chronic = 0,
            sessionSec = 41L,
            fps = 24.5f,
            procMs = 33.25f,
            luma = 120f
        ).toString()
        assertEquals(cols.size, row.split(',').size)
    }

    /**
     * Giá trị phải nằm ĐÚNG cột, không chỉ đúng số lượng. Đây chính là chỗ vòng lặp đếm
     * vị trí cũ dễ sai: một cột thêm vào giữa làm `chronic` rơi sang cột `fatigue`.
     */
    @Test
    fun noFaceRowPutsEachValueUnderItsOwnColumnName() {
        val cols = DataLogger().columnNames
        val row = buildNoFaceCsvRow(
            columns = cols, out = StringBuilder(), nowMs = 9_999L, sei = 77,
            chronic = 2, sessionSec = 101L, fps = 20f, procMs = 50f, luma = 90f
        ).toString()
        val cells = row.split(',')
        val byName = cols.zip(cells).toMap()
        assertEquals("9999", byName["t_ms"])
        assertEquals("77", byName["sei"])
        assertEquals("2", byName["chronic"])
        assertEquals("101", byName["session_s"])
        assertEquals("20.0000", byName["fps"])
        assertEquals("50.0000", byName["proc_ms"])
        assertEquals("90.00", byName["luma"])
        assertEquals("", byName["fatigue"])
        assertEquals("", byName["quality"])
        // Tên cột không được trùng nhau, nếu không thì tra tên ở trên vô nghĩa.
        assertEquals(cols.size, cols.toSet().size)
    }

    /** Cột không đo được thì để trống, không được viết 0 - 0 nghĩa là "mắt nhắm". */
    @Test
    fun noFaceRowLeavesUnmeasuredColumnsBlank() {
        assertEquals("", noFaceCsvValue("ear1", 1L, null, 0, 0L, 0f, 0f, 0f))
        assertEquals("", noFaceCsvValue("perclos_p80", 1L, null, 0, 0L, 0f, 0f, 0f))
        assertEquals("", noFaceCsvValue("bpm", 1L, null, 0, 0L, 0f, 0f, 0f))
        assertEquals("0", noFaceCsvValue("face", 1L, null, 0, 0L, 0f, 0f, 0f))
        assertEquals("1", noFaceCsvValue("t_ms", 1L, null, 0, 0L, 0f, 0f, 0f))
    }

    /** Số cột phải được kiểm trước khi ghi: một dòng lệch cột làm hỏng cả file đối chiếu. */
    @Test
    fun shapeCheckAcceptsRealRowsAndRejectsShiftedOnes() {
        val cols = DataLogger().columnNames
        assertTrue(csvShapeOk("1,2,3", 3))
        assertFalse(csvShapeOk("1,2,3", 4))
        assertFalse(csvShapeOk("1,2,3,", 3))
        // Một ô trống vẫn là một dòng hợp lệ của file một cột; điều quan trọng là dòng của cả
        // tiêu đề thì phải có đúng số dấu phẩy theo cột, thiếu một cái là cả dòng bị chặn.
        assertTrue(csvShapeOk("", 1))
        assertFalse(csvShapeOk("", cols.size))
        assertTrue(csvShapeOk("5", 1))
        val row = buildNoFaceCsvRow(
            columns = cols, out = StringBuilder(), nowMs = 1L, sei = null,
            chronic = 0, sessionSec = 0L, fps = 0f, procMs = 0f, luma = 0f
        ).toString()
        assertTrue(csvShapeOk(row, cols.size))
    }

    /** Sei chưa có điểm (chưa đủ khung hình) phải ra ô trống, không phải "0" hay "null". */
    @Test
    fun noFaceRowDistinguishesNoSeiFromZeroSei() {
        assertEquals("", noFaceCsvValue("sei", 1L, null, 0, 0L, 0f, 0f, 0f))
        assertEquals("0", noFaceCsvValue("sei", 1L, 0, 0, 0L, 0f, 0f, 0f))
        assertEquals("73", noFaceCsvValue("sei", 1L, 73, 0, 0L, 0f, 0f, 0f))
    }

    /** Số đo môi trường dùng cùng định dạng với dòng có mặt người, để một cột không hai kiểu. */
    @Test
    fun noFaceRowFormatsEnvironmentColumnsLikeTheFullRow() {
        assertEquals("24.5000", noFaceCsvValue("fps", 1L, null, 0, 0L, 24.5f, 0f, 0f))
        assertEquals("33.2500", noFaceCsvValue("proc_ms", 1L, null, 0, 0L, 0f, 33.25f, 0f))
        assertEquals("120.00", noFaceCsvValue("luma", 1L, null, 0, 0L, 0f, 0f, 120f))
        // NaN mà in ra "NaN" thì Excel hiểu là chữ, cả cột không tính trung bình được.
        assertEquals("", noFaceCsvValue("fps", 1L, null, 0, 0L, Float.NaN, Float.NaN, Float.NaN))
    }
}
