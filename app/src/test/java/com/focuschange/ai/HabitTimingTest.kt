package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the timing math of the measuring device, run on a computer (no phone needed).
 *
 * Why this exists: every number in the report is a time integral between camera
 * frames, while the frame cadence depends on the device. This is the easiest place to
 * be wrong, and a wrong result is invisible to the eye.
 */
class HabitTimingTest {

    /** A slow device (below 5 FPS) must still count enough study time. */
    @Test
    fun frameGapBelowLimitIsCountedInFull() {
        val s = HabitSession(1L)
        s.tick(1_000L, true, true)   // first frame only sets the timestamp
        s.tick(1_400L, true, true)   // 400 ms after the previous frame
        s.tick(1_800L, true, true)   // another 400 ms on top
        assertEquals(800L, s.activeMs)
        assertEquals(800L, s.faceMs)
        assertEquals(800L, s.validMs)
    }

    /**
     * dt used to be clamped at 200 ms: a 400 ms frame only counted as 200 ms, so a device
     * running at 2.5 FPS never built up MIN_SESSION_MS and the session was rejected for no
     * reason.
     */
    @Test
    fun slowDeviceStillReachesMinimumSessionLength() {
        val s = HabitSession(1L)
        var t = 0L
        val frameMs = 400L
        // dt is the offset from the previous frame, so the first frame contributes nothing: needs MIN/frameMs + 1 frames.
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
     * A long stall (the app stopped, the camera froze) only advances the session clock and
     * must not be counted as study time; the ratio terms still keep that clock in the
     * denominator.
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

    /** The first frame only establishes the timestamp, it never produces duration itself. */
    @Test
    fun firstFrameEstablishesBaselineOnly() {
        val s = HabitSession(1L)
        s.tick(5_000L, true, true)
        assertEquals(0L, s.activeMs)
        assertEquals(0L, s.validMs)
    }

    /**
     * dt is always the offset FROM THE PREVIOUS FRAME, so the first frame contributes nothing:
     * n frames spaced 100 ms apart only produce (n-1)*100 ms.
     */
    @Test
    fun stallKeepsFaceRatioHonest() {
        val s = HabitSession(1L)
        s.tick(0L, true, true)
        s.tick(100L, true, true)
        s.tick(200L, true, true)
        s.tick(300L, true, true)
        // A 30 second device freeze, then studying continues normally.
        s.tick(30_300L, true, true)
        s.tick(30_400L, true, true)

        val summary = s.toSummary()
        // 300 ms of real study inside 30,400 ms of wall clock elapsed.
        assertEquals(300L, summary.validMs)
        assertTrue(
            "khoảng dừng bị tính thành thời gian học: faceRatio=${summary.faceRatio}",
            summary.faceRatio < 0.02f
        )
    }

    /**
     * WindowRatio: stalls and slow frame cadence.
     *
     * Four points that made me misjudge the numbers today, written down so I do not re-guess:
     * - the first record() of a run has lastFrameMs = 0, so dt = 0 and it is skipped entirely;
     *   every interval is produced by the NEXT frame.
     * - percent() drops buckets by AGE IN SECONDS (age < buckets), not by millis. An 8 second
     *   stall with buckets = 12 wipes the old cluster, so percent() returns 0 with an empty denominator.
     * - when refFrameMs >= capFrameMs, every interval >= refFrameMs maps back to refFrameMs:
     *   a fast or moderately slow device gives the same denominator, so the old behaviour
     *   cannot be inferred by summing the real dt values.
     * - when the denominator is below minSampleMs, percent() returns 0 instead of a ratio.
     * - a frame's flag belongs to the interval JUST ENDED at that frame, not the next
     * one. Each frame here carries its flag only once, so a wrong direction skews the whole run.
     */
    @Test
    fun fastCadenceCountsBothFlaggedAndCleanIntervals() {
        val w = WindowRatio(12, 1L, capFrameMs = 200L, refFrameMs = 66L)
        w.record(1_000L, true)    // first frame: no contribution
        w.record(1_100L, true)    // 66 ms eyes closed
        w.record(1_200L, false)   // 66 ms eyes open
        w.record(1_300L, false)   // 66 ms eyes open
        w.pause()
        w.record(5_000L, false)   // first frame of cluster 2: no contribution
        w.record(5_100L, true)    // 66 ms eyes closed
        w.record(5_200L, false)   // 66 ms eyes open
        // 132 ms eyes closed out of 330 ms total.
        assertEquals(132f / 330f * 100f, w.percent(5_200L), 0.01f)
    }

    /** pause(): the gap between two clusters must never leak into the window. */
    @Test
    fun pausedWindowDoesNotInheritTheGap() {
        val w = WindowRatio(12, 1L, capFrameMs = 200L, refFrameMs = 66L)
        w.record(1_000L, false)
        w.record(1_100L, true)     // [1000,1100]: eyes closed, 66 ms
        w.pause()
        w.record(3_000L, true)     // first frame of cluster 2: no contribution
        w.record(3_100L, false)    // [3000,3100]: eyes open, 66 ms
        // If the 1.9 s of dead air were counted as 200 ms of open eyes, the ratio would fall to 66/332 = 19.9%.
        assertEquals(50f, w.percent(3_100L), 0.01f)
    }

    /**
     * A slow device must not DEFLATE PERCLOS: if a 400 ms interval only counted as 200 ms (the
     * old version), the fast-device stretch took up less of the denominator than the slow one,
     * so one slow stretch 12 times longer pulled the denominator up and halved the ratio.
     * Mapping every interval longer than refFrameMs to one weight keeps the share of
     * eyes-closed frames over frames correct.
     */
    @Test
    fun slowCadenceIsNotDeflated() {
        val w = WindowRatio(12, 1L, capFrameMs = 200L, refFrameMs = 66L)
        w.record(1_000L, false)
        w.record(1_033L, true)     // [1000,1033]: eyes closed, 33 ms
        w.record(1_066L, true)     // [1033,1066]: eyes closed, 33 ms
        w.record(1_466L, false)    // [1066,1466]: eyes open, 400 ms -> reduced to 66 ms
        // 66 closed / 132 total = 50%. The old version (capped at 200 ms only) gave 66/266 = 24.8%.
        assertEquals(50f, w.percent(1_466L), 0.01f)
    }

    /** A freeze longer than WINDOW_STALL_MS is dead air, not evidence of open eyes. */
    @Test
    fun stallIsSkippedEntirely() {
        val w = WindowRatio(12, 1L, capFrameMs = 200L, refFrameMs = 66L)
        w.record(1_000L, true)
        w.record(1_066L, true)     // [1000,1066]: eyes closed, 66 ms
        w.record(1_132L, true)     // [1066,1132]: eyes closed, 66 ms
        w.record(3_132L, false)    // 2000 ms (> WINDOW_STALL_MS): skipped entirely
        w.record(3_198L, false)    // [3132,3198]: eyes open, 66 ms
        // 132/198 = 66.7%. If the stall were counted as 200 ms of open eyes it would drop to 32.5%.
        assertEquals(132f / 198f * 100f, w.percent(3_198L), 0.01f)
    }

    /**
     * "Not measured enough" must stay different from "measured 0%". If the HUD showed 0.0% during
     * the first three seconds, a viewer would read it as an alert student, and the profile would
     * learn a 0 that never really happened.
     */
    @Test
    fun insufficientSampleIsNotReportedAsZeroPercent() {
        val w = WindowRatio(60, 3_000L, capFrameMs = 200L, refFrameMs = 33L)
        w.record(1_000L, false)
        w.record(1_033L, false)    // only 33 ms of sample so far
        assertNull(w.percentOrNull(1_033L))
        assertEquals(0f, w.percent(1_033L), 0f)   // the scoring path still treats it as 0%

        var t = 1_033L
        while (t <= 6_000L) {
            w.record(t, false)
            t += 33L
        }
        assertEquals(0f, w.percentOrNull(6_000L)!!, 0.01f)   // enough samples: a REAL 0%
    }

    /** One session in front is not enough to act as "the previous sessions" for a trend comparison. */
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
        val both = p.avgSei(-5, 99)   // indices past either end must be clamped, not throw an exception
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
     * The two new thresholds come as a PAIR: MIN_SESSIONS x MIN_SESSION_MS (4 x 2 minutes = 8
     * minutes) is still BELOW MIN_TOTAL_MS (10 minutes), so four borderline sessions are not
     * enough to open a profile. This test exists so that editing a constant somewhere else
     * cannot silently keep the profile from ever becoming "ready" during the fair.
     */
    @Test
    fun fourSessionsAtThePerSessionFloorStillMissTheTotalBar() {
        val p = HabitProfile("hs1", "Học sinh 1")
        repeat(HabitConfig.MIN_SESSIONS) { p.sessions.add(studySession(HabitConfig.MIN_SESSION_MS)) }
        assertEquals(8L * 60L * 1000L, p.totalValidMs())
        assertFalse("4 phiên sát mép mà đã ready thì MIN_TOTAL_MS không còn tác dụng", p.isReady())
    }

    /**
     * Four sessions of 2.5 minutes = exactly 10 minutes = just enough for ONE demo run. And
     * confidence must start at 0.2 on the first qualifying session of a profile that has just
     * become ready - the original design number, not 0.4 shifted by the old "- 2" milestone
     * once MIN_SESSIONS went up from 3 to 4.
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
