package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests the two functions that build text other people read: the SEI score breakdown table and the tail
 * reader of the log file. Why they are tested: both print numbers, and a wrongly printed number is not
 * something anyone catches by eye - it only quietly makes the table contradict itself.
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
     * The weighted total, the deductions and the final score must agree with one another. The old version
     * added a deduction back twice, so the "total" line printed exactly the deducted amount too high - a
     * table contradicting itself with nothing visible to catch it.
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

    /** With no deduction in play the table must not mention deductions at all. */
    @Test
    fun noPenaltyRowsWhenNothingIsSubtracted() {
        val text = breakdown(close = 0f, chronic = 0f, frozen = false, weightedSum = 78.25f, finalRaw = 78.25f)
        assertFalse(text.contains("ngồi quá gần"))
        assertFalse(text.contains("mất tập trung kéo dài"))
        assertFalse(text.contains("chặn ở"))
    }

    /** An image suspected of being static must show the exact clamp level, not a vague phrase. */
    @Test
    fun frozenShowsTheCap() {
        val text = breakdown(close = 0f, chronic = 0f, frozen = true, weightedSum = 78.25f, finalRaw = 30f)
        assertTrue(text.contains("chặn ở 30"))
    }

    /** The six component rows must add up to the printed total - this is the table the judges scrutinise. */
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
     * Reads the tail of a file and requires that the read actually worked: the reader returns `null` when
     * there is nothing to read, and a test may not treat `null` as "empty" - that would miss the real bug.
     */
    private fun tailOf(file: File, wanted: Int, windowBytes: Long = 96L * 1024L): List<String> =
        checkNotNull(readTailLines(file, wanted, windowBytes)) {
            "readTailLines trả về null cho ${file.name}"
        }

    /** Reads the requested number of last lines, in the order they appear inside the file. */
    @Test
    fun tailReturnsLastLinesInOrder() {
        val lines = (1..25).map { "row_%02d,1,2,3".format(it) }
        val tail = tailOf(writeTemp(lines), 5)
        assertEquals(listOf("row_21,1,2,3", "row_22,1,2,3", "row_23,1,2,3", "row_24,1,2,3", "row_25,1,2,3"), tail)
    }

    /**
     * When the read window starts in the middle of a line, the truncated line has to be dropped rather than
     * shown as a real data row - one cut-off value in the table is enough to misread the whole session.
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

    /** An empty file, a one-line file and a request for zero lines all return a safe result. */
    @Test
    fun degenerateInputsReturnEmpty() {
        val empty = File.createTempFile("empty", ".csv")
        empty.deleteOnExit()
        assertEquals(emptyList<String>(), tailOf(empty, 10))
        assertEquals(emptyList<String>(), tailOf(writeTemp(listOf("chỉ một dòng")), 0))
        assertEquals(listOf("chỉ một dòng"), tailOf(writeTemp(listOf("chỉ một dòng")), 10))
    }

    /** A trailing blank line (from an extra line break) must not show up as an empty row. */
    @Test
    fun trailingNewlineDoesNotYieldBlankLine() {
        assertEquals(listOf("a,1", "b,2"), tailOf(writeTemp(listOf("a,1", "b,2")), 10))
    }

    /**
     * When the read window starts exactly at the beginning of a line, that line is intact and must be kept.
     * The old "always drop the first line" rule lost one real row whenever the start landed on a boundary -
     * with short CSV rows that happens constantly.
     */
    @Test
    fun windowStartingOnALineBoundaryKeepsThatLine() {
        // Every row is exactly 6 bytes: "ROW" + 2 digits + line break. 20 rows = 120 bytes.
        val lines = (1..20).map { "ROW%02d".format(it) }
        val f = File.createTempFile("boundary", ".csv")
        f.deleteOnExit()
        f.writeText(lines.joinToString("") { it + "\n" })
        assertEquals(120L, f.length())
        // A 24 byte window -> start = 96 = 16 * 6, exactly at the head of ROW17: four rows must still be there.
        assertEquals(listOf("ROW17", "ROW18", "ROW19", "ROW20"), tailOf(f, 10, windowBytes = 24L))
        // A 23 byte window -> start = 97, cutting into ROW17: the truncated row must be dropped.
        assertEquals(listOf("ROW18", "ROW19", "ROW20"), tailOf(f, 10, windowBytes = 23L))
    }

    /**
     * "There is no file" and "the file has no rows yet" are two different conclusions when reconciling the
     * numbers, so the reader must return two different values. The UI uses `null` to print "cannot be read"
     * instead of showing a header-only table that makes whoever tapped think nothing was written.
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
     * A "no face" row must carry exactly the header's column count, otherwise opening the file in Excel
     * shifts every good frame standing behind it into the wrong column.
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
     * Values must land in the RIGHT column, not merely the right number of them. This is precisely where
     * the old position-counting loop went wrong: one column added in the middle pushed `chronic` into `fatigue`.
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
        // Column names must not repeat, otherwise the name lookup above becomes meaningless.
        assertEquals(cols.size, cols.toSet().size)
    }

    /** A column that cannot be measured stays empty, never 0 - 0 would read as "eyes closed". */
    @Test
    fun noFaceRowLeavesUnmeasuredColumnsBlank() {
        assertEquals("", noFaceCsvValue("ear1", 1L, null, 0, 0L, 0f, 0f, 0f))
        assertEquals("", noFaceCsvValue("perclos_p80", 1L, null, 0, 0L, 0f, 0f, 0f))
        assertEquals("", noFaceCsvValue("bpm", 1L, null, 0, 0L, 0f, 0f, 0f))
        assertEquals("0", noFaceCsvValue("face", 1L, null, 0, 0L, 0f, 0f, 0f))
        assertEquals("1", noFaceCsvValue("t_ms", 1L, null, 0, 0L, 0f, 0f, 0f))
    }

    /** The column count must be checked before writing: one row with shifted columns spoils the audit file. */
    @Test
    fun shapeCheckAcceptsRealRowsAndRejectsShiftedOnes() {
        val cols = DataLogger().columnNames
        assertTrue(csvShapeOk("1,2,3", 3))
        assertFalse(csvShapeOk("1,2,3", 4))
        assertFalse(csvShapeOk("1,2,3,", 3))
        // One empty cell is still a valid row of a single-column file; what matters is that a row of the header
        // must carry exactly as many commas as there are columns - missing one and the whole row is rejected.
        assertTrue(csvShapeOk("", 1))
        assertFalse(csvShapeOk("", cols.size))
        assertTrue(csvShapeOk("5", 1))
        val row = buildNoFaceCsvRow(
            columns = cols, out = StringBuilder(), nowMs = 1L, sei = null,
            chronic = 0, sessionSec = 0L, fps = 0f, procMs = 0f, luma = 0f
        ).toString()
        assertTrue(csvShapeOk(row, cols.size))
    }

    /** An Sei with no score yet (too few frames) must give an empty cell, not "0" and not "null". */
    @Test
    fun noFaceRowDistinguishesNoSeiFromZeroSei() {
        assertEquals("", noFaceCsvValue("sei", 1L, null, 0, 0L, 0f, 0f, 0f))
        assertEquals("0", noFaceCsvValue("sei", 1L, 0, 0, 0L, 0f, 0f, 0f))
        assertEquals("73", noFaceCsvValue("sei", 1L, 73, 0, 0L, 0f, 0f, 0f))
    }

    /** Environment measures use the same format as a face-present row so one column never has two styles. */
    @Test
    fun noFaceRowFormatsEnvironmentColumnsLikeTheFullRow() {
        assertEquals("24.5000", noFaceCsvValue("fps", 1L, null, 0, 0L, 24.5f, 0f, 0f))
        assertEquals("33.2500", noFaceCsvValue("proc_ms", 1L, null, 0, 0L, 0f, 33.25f, 0f))
        assertEquals("120.00", noFaceCsvValue("luma", 1L, null, 0, 0L, 0f, 0f, 120f))
        // Printing NaN as "NaN" makes Excel read it as text, so the whole column cannot be averaged.
        assertEquals("", noFaceCsvValue("fps", 1L, null, 0, 0L, Float.NaN, Float.NaN, Float.NaN))
    }
}
