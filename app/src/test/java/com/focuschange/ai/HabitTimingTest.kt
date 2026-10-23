package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test các bài toán thời gian của máy chủ, chạy trên máy tính (không cần điện thoại).
 *
 * Lý do tồn tại: mọi con số trong báo cáo đều là tích phân theo thời gian giữa các khung
 * hình, mà nhịp khung hình thay đổi theo máy. Đây là chỗ dễ sai nhất và sai thì không thấy
 * bằng mắt.
 */
class HabitTimingTest {

    /** Máy chạy chậm (dưới 5 FPS) vẫn phải tính đủ thời gian học. */
    @Test
    fun frameGapBelowLimitIsCountedInFull() {
        val s = HabitSession(1L)
        s.tick(1_000L, true, true)   // khung đầu chỉ đặt mốc
        s.tick(1_400L, true, true)   // cách khung trước 400 ms
        s.tick(1_800L, true, true)   // thêm 400 ms nữa
        assertEquals(800L, s.activeMs)
        assertEquals(800L, s.faceMs)
        assertEquals(800L, s.validMs)
    }

    /**
     * Trước đây dt bị kẹp ở 200 ms: khung 400 ms chỉ được tính 200 ms, nên một máy chạy
     * 2,5 FPS không bao giờ tích đủ MIN_SESSION_MS và phiên bị loại oan.
     */
    @Test
    fun slowDeviceStillReachesMinimumSessionLength() {
        val s = HabitSession(1L)
        var t = 0L
        val frameMs = 400L
        // dt là độ lệch so với khung trước nên khung đầu không đóng góp: cần MIN/frameMs + 1 khung.
        while (t <= HabitConfig.MIN_SESSION_MS + frameMs) {
            s.tick(t, true, true)
            t += frameMs
        }
        assertTrue(
            "phút hợp lệ vẫn bị thiếu khi máy chạy ~2,5 FPS: ${s.validMs}",
            s.validMs >= HabitConfig.MIN_SESSION_MS
        )
    }

    /**
     * Khoảng dừng dài (ứng dụng đứng, camera khựng) chỉ chạy đồng hồ của phiên chứ không
     * được tính thành thời gian học; các hệ số tỉ lệ vẫn giữ mặt số đó ở mẫu số.
     */
    @Test
    fun longStallIsSkippedNotAccumulated() {
        val s = HabitSession(1L)
        s.tick(1_000L, true, true)
        s.tick(9_000L, true, true)
        assertEquals(8_000L, s.activeMs)
        assertEquals(0L, s.faceMs)
        assertEquals(0L, s.validMs)

        s.tick(9_300L, true, true)
        assertEquals(8_300L, s.activeMs)
        assertEquals(300L, s.validMs)
    }

    /** Khung đầu tiên chỉ đặt mốc thời gian, không tự sinh ra thời lượng. */
    @Test
    fun firstFrameEstablishesBaselineOnly() {
        val s = HabitSession(1L)
        s.tick(5_000L, true, true)
        assertEquals(0L, s.activeMs)
        assertEquals(0L, s.validMs)
    }

    /**
     * dt luôn là độ lệch SO VỚI KHUNG TRƯỚC, nên khung đầu tiên không đóng góp gì:
     * n khung cách nhau 100 ms chỉ tạo ra (n-1)*100 ms.
     */
    @Test
    fun stallKeepsFaceRatioHonest() {
        val s = HabitSession(1L)
        s.tick(0L, true, true)
        s.tick(100L, true, true)
        s.tick(200L, true, true)
        s.tick(300L, true, true)
        // Cú đứng máy 30 giây, rồi học tiếp bình thường.
        s.tick(30_300L, true, true)
        s.tick(30_400L, true, true)

        val summary = s.toSummary()
        // 300 ms học thật trong tổng cộng 30.400 ms đồng hồ trôi qua.
        assertEquals(300L, summary.validMs)
        assertTrue(
            "khoảng dừng bị tính thành thời gian học: faceRatio=${summary.faceRatio}",
            summary.faceRatio < 0.02f
        )
    }

    /**
     * WindowRatio: khoảng dừng và nhịp khung chậm.
     *
     * Bốn điểm đã làm tôi đoán sai số hôm nay, ghi lại để không đoán lại:
     * - record() đầu tiên của một loạt có lastFrameMs = 0 nên dt = 0 và bị bỏ qua hoàn toàn;
     *   mọi khoảng thời gian đều do khung KẾ TIẾP tạo ra.
     * - percent() loại bucket theo TUỔI GIÂY (age < buckets), không phải theo mili. Một khoảng
     *   dừng 8 giây với buckets = 12 xoá sạch cụm cũ, nên percent() trả 0 vì mẫu số rỗng.
     * - khi refFrameMs >= capFrameMs thì mọi khoảng >= refFrameMs đều quy về refFrameMs:
     *   máy nhanh hay chậm vừa phải đều cho cùng một mẫu số, nên không thể suy ra hành vi
     *   cũ bằng cách lấy dt thật cộng dồn.
     * - khi mẫu số nhỏ hơn minSampleMs thì percent() trả 0 chứ không trả tỉ lệ.
     * - cờ của một khung gán cho khoảng VỪA KẾT THÚC tại khung đó, không phải khoảng tiếp
     * theo. Vì mỗi khung ở đây chỉ mang cờ một lần, tính nhầm chiều là sai số cả chuỗi.
     */
    @Test
    fun fastCadenceCountsBothFlaggedAndCleanIntervals() {
        val w = WindowRatio(12, 1L, capFrameMs = 200L, refFrameMs = 66L)
        w.record(1_000L, true)    // khung đầu: không đóng góp
        w.record(1_100L, true)    // 66 ms nhắm
        w.record(1_200L, false)   // 66 ms mở
        w.record(1_300L, false)   // 66 ms mở
        w.pause()
        w.record(5_000L, false)   // khung đầu cụm 2: không đóng góp
        w.record(5_100L, true)    // 66 ms nhắm
        w.record(5_200L, false)   // 66 ms mở
        // nhắm 132 ms / tổng 330 ms.
        assertEquals(132f / 330f * 100f, w.percent(5_200L), 0.01f)
    }

    /** pause(): khoảng trống giữa hai cụm không được lọt vào cửa sổ. */
    @Test
    fun pausedWindowDoesNotInheritTheGap() {
        val w = WindowRatio(12, 1L, capFrameMs = 200L, refFrameMs = 66L)
        w.record(1_000L, false)
        w.record(1_100L, true)     // [1000,1100]: nhắm, 66 ms
        w.pause()
        w.record(3_000L, true)     // khung đầu cụm 2: không đóng góp
        w.record(3_100L, false)    // [3000,3100]: mở, 66 ms
        // Nếu 1,9 giây trống bị tính thành 200 ms mở mắt thì tỉ lệ còn 66/332 = 19,9%.
        assertEquals(50f, w.percent(3_100L), 0.01f)
    }

    /**
     * Máy chậm không được làm PERCLOS TẸT đi: khoảng 400 ms mà chỉ được tính 200 ms (bản cũ)
     * thì đoạn máy nhanh chiếm ít mẫu số hơn đoạn máy chậm, nên một stretch chậm dài gấp 12
     * lần kéo mẫu số lên và chia đôi tỉ lệ. Quy mọi khoảng dài hơn refFrameMs về cùng một
     * trọng số giữ đúng tỉ lệ số khung nhắm trên số khung.
     */
    @Test
    fun slowCadenceIsNotDeflated() {
        val w = WindowRatio(12, 1L, capFrameMs = 200L, refFrameMs = 66L)
        w.record(1_000L, false)
        w.record(1_033L, true)     // [1000,1033]: nhắm, 33 ms
        w.record(1_066L, true)     // [1033,1066]: nhắm, 33 ms
        w.record(1_466L, false)    // [1066,1466]: mở, 400 ms -> quy về 66 ms
        // nhắm 66 / tổng 132 = 50%. Bản cũ (chỉ cap 200 ms) cho 66/266 = 24,8%.
        assertEquals(50f, w.percent(1_466L), 0.01f)
    }

    /** Cú đứng máy dài hơn WINDOW_STALL_MS chỉ là khoảng trống, không phải bằng chứng mở mắt. */
    @Test
    fun stallIsSkippedEntirely() {
        val w = WindowRatio(12, 1L, capFrameMs = 200L, refFrameMs = 66L)
        w.record(1_000L, true)
        w.record(1_066L, true)     // [1000,1066]: nhắm, 66 ms
        w.record(1_132L, true)     // [1066,1132]: nhắm, 66 ms
        w.record(3_132L, false)    // 2000 ms (> WINDOW_STALL_MS): bỏ hẳn
        w.record(3_198L, false)    // [3132,3198]: mở, 66 ms
        // 132/198 = 66,7%. Nếu khoảng dừng bị tính thành 200 ms mở mắt thì còn 32,5%.
        assertEquals(132f / 198f * 100f, w.percent(3_198L), 0.01f)
    }

    /**
     * "Chưa đo đủ" phải khác "đo ra 0%". HUD mà hiện 0.0% trong ba giây đầu thì người xem
     * hiểu lầm là học sinh đang tỉnh, còn hồ sơ thì học một con số 0 không có thật.
     */
    @Test
    fun insufficientSampleIsNotReportedAsZeroPercent() {
        val w = WindowRatio(60, 3_000L, capFrameMs = 200L, refFrameMs = 33L)
        w.record(1_000L, false)
        w.record(1_033L, false)    // mới 33 ms mẫu
        assertNull(w.percentOrNull(1_033L))
        assertEquals(0f, w.percent(1_033L), 0f)   // đường tính điểm vẫn coi là 0%

        var t = 1_033L
        while (t <= 6_000L) {
            w.record(t, false)
            t += 33L
        }
        assertEquals(0f, w.percentOrNull(6_000L)!!, 0.01f)   // đủ mẫu: 0% THẬT
    }

    /** Một phiên phía trước không đủ làm "các phiên trước" để so xu hướng. */
    @Test
    fun averageSeiReportsHowManySessionsBehindIt() {
        fun session(idx: Int, sei: Float) = SessionSummary(
            id = idx.toLong(),
            validMs = 60_000L,
            faceRatio = 1f,
            validRatio = 1f,
            quality = 80f,
            mean = FloatArray(HabitFeature.COUNT).also { it[HabitFeature.SEI] = sei },
            std = FloatArray(HabitFeature.COUNT),
            count = IntArray(HabitFeature.COUNT).also { it[HabitFeature.SEI] = 100 }
        )
        val p = HabitProfile("hs1", "Học sinh 1")
        assertNull(p.avgSei(0, 0))
        p.sessions.add(session(0, 60f))
        p.sessions.add(session(1, 70f))
        val one = p.avgSei(0, 1)
        assertEquals(60f, one!!.first, 0.01f)
        assertEquals(1, one.second)
        val both = p.avgSei(-5, 99)   // chỉ số vượt hai đầu phải được kẹp lại, không ném ngoại lệ
        assertEquals(65f, both!!.first, 0.01f)
        assertEquals(2, both.second)
    }

    private fun studySession(validMs: Long, sei: Float = 70f) = SessionSummary(
        id = 1L,
        validMs = validMs,
        faceRatio = 0.95f,
        validRatio = 0.90f,
        quality = 80f,
        mean = FloatArray(HabitFeature.COUNT).also { it[HabitFeature.SEI] = sei },
        std = FloatArray(HabitFeature.COUNT),
        count = IntArray(HabitFeature.COUNT).also { it[HabitFeature.SEI] = 100 }
    )

    /**
     * Hai ngưỡng mới đi CẶP: MIN_SESSIONS x MIN_SESSION_MS (4 x 2 phút = 8 phút) vẫn DƯỚI
     * MIN_TOTAL_MS (10 phút), nên bốn phiên sát mép không đủ lập hồ sơ. Test này tồn tại để
     * một lần sửa hằng số ở chỗ khác không âm thầm làm hồ sơ không bao giờ "ready" trong lúc thi.
     */
    @Test
    fun fourSessionsAtThePerSessionFloorStillMissTheTotalBar() {
        val p = HabitProfile("hs1", "Học sinh 1")
        repeat(HabitConfig.MIN_SESSIONS) { p.sessions.add(studySession(HabitConfig.MIN_SESSION_MS)) }
        assertEquals(8L * 60L * 1000L, p.totalValidMs())
        assertFalse("4 phiên sát mép mà đã ready thì MIN_TOTAL_MS không còn tác dụng", p.isReady())
    }

    /**
     * Bốn phiên 2,5 phút = đúng 10 phút = vừa đủ cho MỘT lượt demo. Và độ tin cậy phải bắt đầu
     * ở 0.2 tại phiên đầu tiên đạt chuẩn của một hồ sơ vừa ready - con số thiết kế ban đầu, không
     * phải 0.4 do mốc "- 2" cũ lệch theo khi MIN_SESSIONS tăng từ 3 lên 4.
     */
    @Test
    fun demoLengthSessionsReachReadinessWithConfidenceAtItsFloor() {
        val p = HabitProfile("hs1", "Học sinh 1")
        repeat(HabitConfig.MIN_SESSIONS) { p.sessions.add(studySession(150_000L)) }
        assertTrue(p.isReady())
        assertEquals(0.2f, p.confidence(), 0.001f)
        repeat(4) { p.sessions.add(studySession(150_000L)) }
        assertEquals(1f, p.confidence(), 0.001f)
    }
}
