package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline comparison called for in the research plan: one shared threshold versus a threshold that
 * knows what the student is doing.
 *
 * These tests run on recorded frames, not on live video, because the claim has to be checkable by
 * someone who does not own the phone. Two of them are honesty checks rather than feature checks:
 * [theReplayReproducesWhatThePhoneWrote] proves the file holds every input the layer used, and
 * [aFileFromBeforeTheSituationLayerIsRefused] proves an old file is rejected instead of silently
 * replayed from guesses.
 */
internal class SituationReplayTest {

    /** One frame as it would appear on disk, with the two columns the phone answers for. */
    private class F(
        val tMs: Long,
        val sessionS: Long,
        val openness: Float,
        val alertCode: Int = 0,
        val yaw: Float = 0f,
        val pitch: Float = 0f,
        val roll: Float = 0f,
        val gazeV: Float = 0f,
        val closedMs: Long = 0L,
        val poseStd: Float = 1.0f,
        val situation: Int = -1,
        val held: Int = -1,
    )

    /** Column names in a scrambled order: a reader that counts positions would fail here and pass on a tidy file. */
    private val headerCols = listOf(
        "openness", "t_ms", "pose_std_deg", "yaw_deg", "session_s", "alert_held", "roll_deg",
        "situation", "gaze_v", "alert_code", "pitch_deg", "closed_ms"
    )

    private fun csvOf(frames: List<F>): String {
        val sb = StringBuilder(headerCols.joinToString(",")).append('\n')
        for (f in frames) {
            val cells = mapOf(
                "openness" to f.openness.toString(),
                "t_ms" to f.tMs.toString(),
                "pose_std_deg" to f.poseStd.toString(),
                "yaw_deg" to f.yaw.toString(),
                "session_s" to f.sessionS.toString(),
                "alert_held" to (if (f.held < 0) "" else f.held.toString()),
                "roll_deg" to f.roll.toString(),
                // An empty situation cell is how the app marks a frame with no face.
                "situation" to (if (f.situation < 0) "" else f.situation.toString()),
                "gaze_v" to f.gazeV.toString(),
                "alert_code" to f.alertCode.toString(),
                "pitch_deg" to f.pitch.toString(),
                "closed_ms" to f.closedMs.toString(),
            )
            sb.append(headerCols.joinToString(",") { cells[it]!! }).append('\n')
        }
        return sb.toString()
    }

    /**
     * A student bent over a notebook for ten seconds, then six frames the bare threshold calls drowsy.
     *
     * The openness during those six frames sits just below the writing average but well inside that
     * student's own spread for that posture - the case the whole layer exists for, and the one a single
     * shared threshold cannot tell apart from a real doze.
     */
    private fun writingSession(bandK: Float): Pair<List<F>, String> {
        val engine = SituationEngine(bandK)
        val frame = SituationFrame()
        val out = ArrayList<F>()
        var t = 1_000_000L
        for (i in 0 until 300) {
            val open = if (i % 2 == 0) 0.69f else 0.71f
            out.add(record(engine, frame, t, t / 1000L, 0, 0f, 25f, 0f, 0f, open, 0L, 1.0f))
            t += 30L
        }
        for (i in 0 until 6) {
            out.add(record(engine, frame, t, t / 1000L, 1, 0f, 25f, 0f, 0f, 0.68f, 1_600L, 1.0f))
            t += 30L
        }
        return out to csvOf(out)
    }

    /** Feed one frame exactly the way the camera thread does: update, then ask the gate. */
    private fun record(
        engine: SituationEngine,
        frame: SituationFrame,
        tMs: Long,
        sessionS: Long,
        alertCode: Int,
        yaw: Float,
        pitch: Float,
        roll: Float,
        gazeV: Float,
        openness: Float,
        closedMs: Long,
        poseStd: Float,
    ): F {
        frame.yawDeg = yaw
        frame.pitchDeg = pitch
        frame.rollDeg = roll
        frame.gazeVDev = gazeV
        frame.openness = openness
        frame.closedMs = closedMs
        frame.poseStdDeg = poseStd
        engine.update(frame, tMs)
        val eyelid = alertCode == 1 || alertCode == 11
        val allow = engine.allow(eyelid, frame, tMs)
        return F(
            tMs = tMs, sessionS = sessionS, alertCode = alertCode, yaw = yaw, pitch = pitch, roll = roll,
            gazeV = gazeV, openness = openness, closedMs = closedMs, poseStd = poseStd,
            situation = engine.situation.csvCode, held = if (eyelid && !allow) 1 else 0,
        )
    }

    @Test
    fun theReplayReproducesWhatThePhoneWrote() {
        val (frames, text) = writingSession(SituationEngine.BAND_K)
        val rows = SituationReplay.parse(text)!!
        assertEquals(frames.size, rows.size)
        val report = SituationReplay.replay(rows)

        // Any non-zero number here means frames.csv is missing an input the layer used, and every other
        // figure this file produces is then a guess rather than a replay.
        assertEquals(0, report.situationMismatch)
        assertEquals(0, report.heldFrameMismatch)
    }

    @Test
    fun aNoFaceGapDoesNotDisturbTheReplay() {
        val (frames, _) = writingSession(SituationEngine.BAND_K)
        val withGap = ArrayList<F>()
        withGap.addAll(frames.subList(0, 150))
        // Two frames the app wrote with no face: the situation cell is empty and nothing is measured.
        withGap.add(F(tMs = 1_004_500L, sessionS = 1_004L, openness = Float.NaN))
        withGap.add(F(tMs = 1_004_530L, sessionS = 1_004L, openness = Float.NaN))
        withGap.addAll(frames.subList(150, frames.size))
        val report = SituationReplay.replay(SituationReplay.parse(csvOf(withGap))!!)

        assertEquals(2, report.noFaceRows)
        assertEquals(0, report.situationMismatch)
    }

    @Test
    fun theContextLayerHoldsBackAnAlertThatAPlainPerSituationThresholdFires() {
        // Each replay uses the same k the session was generated with: replaying at another k would compare
        // two different rules against two different recordings, which is not the comparison anyone wants.
        val raw = SituationReplay.replay(SituationReplay.parse(writingSession(0f).second)!!, 0f)
        val gated = SituationReplay.replay(
            SituationReplay.parse(writingSession(SituationEngine.BAND_K).second)!!,
            SituationEngine.BAND_K
        )

        // The threshold itself is untouched: both replays see the same single episode on the same frames.
        assertEquals(1, raw.rawAlertCount)
        assertEquals(1, gated.rawAlertCount)

        // k = 0 puts the floor exactly on the student's writing average, so the lid dip fires an alert;
        // the shipped band reads the same dip as ordinary for a head bent over a page, and holds it.
        assertEquals(1, gated.suppressedCount)
        assertEquals(1, raw.gatedAlertCount)
        assertEquals(0, gated.gatedAlertCount)
        assertEquals(0, raw.situationMismatch)
        assertEquals(0, gated.situationMismatch)
    }

    @Test
    fun aDelayExpiresSoTheAlertStillGoesOut() {
        // A long eyelid alert: the hold must expire, so the alert goes out late rather than never.
        val engine = SituationEngine(SituationEngine.BAND_K)
        val frame = SituationFrame()
        val out = ArrayList<F>()
        var t = 2_000_000L
        for (i in 0 until 300) {
            out.add(record(engine, frame, t, t / 1000L, 0, 0f, 25f, 0f, 0f, if (i % 2 == 0) 0.69f else 0.71f, 0L, 1.0f))
            t += 30L
        }
        for (i in 0 until 60) {
            out.add(record(engine, frame, t, t / 1000L, 1, 0f, 25f, 0f, 0f, 0.68f, 1_600L, 1.0f))
            t += 30L
        }
        val report = SituationReplay.replay(SituationReplay.parse(csvOf(out))!!)

        assertEquals(1, report.rawAlertCount)
        assertEquals(1, report.gatedAlertCount)
        assertEquals(0, report.suppressedCount)
        // The ceiling is the hold plus one frame: the gate is only asked when a frame arrives, so the
        // alert goes out on the first frame after 700 ms, not at 700 ms exactly.
        assertEquals(1, report.episodes.size)
        assertTrue(
            report.episodes[0].addedLagMs in 1L..(SituationEngine.HOLD_MAX_MS + 30L)
        )
    }

    @Test
    fun aClosureLongerThanTheCapIsNeverCountedAsHeldBack() {
        val engine = SituationEngine(SituationEngine.BAND_K)
        val frame = SituationFrame()
        val out = ArrayList<F>()
        var t = 3_000_000L
        for (i in 0 until 300) {
            out.add(record(engine, frame, t, t / 1000L, 0, 0f, 25f, 0f, 0f, if (i % 2 == 0) 0.69f else 0.71f, 0L, 1.0f))
            t += 30L
        }
        // Past the 2.5 s cap the absolute rule takes over, so the alert fires even inside the band.
        for (i in 0 until 12) {
            out.add(record(engine, frame, t, t / 1000L, 1, 0f, 25f, 0f, 0f, 0.68f, 2_600L, 1.0f))
            t += 30L
        }
        val report = SituationReplay.replay(SituationReplay.parse(csvOf(out))!!)

        assertTrue(report.overrideFrames > 0)
        assertEquals(1, report.gatedAlertCount)
        assertEquals(0, report.suppressedCount)
    }

    @Test
    fun aFileFromBeforeTheSituationLayerIsRefused() {
        val text = "t_ms,openness,yaw_deg\n1000,0.7,0.0\n"
        // No situation columns at all: replaying it would mean inventing the inputs, so the answer is null.
        assertNull(SituationReplay.parse(text))
    }

    /**
     * The header the phone actually writes must be readable by the replay, by name.
     *
     * This is the check that catches a rename: the two halves live in different files and a column
     * renamed on one side would make every real session unparsable while all the other tests here kept
     * passing, because they build their own header.
     */
    @Test
    fun theRealHeaderIsReplayable() {
        val cols = DataLogger().columnNames
        val one = buildString {
            append(cols.joinToString(",")).append('\n')
            for (c in cols) {
                append(
                    when (c) {
                        "t_ms" -> "1000"
                        "session_s" -> "1"
                        "situation" -> "1"
                        "alert_held" -> "0"
                        "alert_code" -> "0"
                        "closed_ms" -> "0"
                        "pose_std_deg" -> "1.0"
                        "openness" -> "0.7"
                        "yaw_deg", "pitch_deg", "roll_deg", "gaze_v" -> "0.0"
                        else -> ""
                    }
                )
                if (c != cols.last()) append(',')
            }
        }
        val rows = SituationReplay.parse(one)
        assertTrue("the shipped header must hold every column the replay reads", rows != null)
        assertEquals(1, rows!!.size)
        assertEquals(1, rows[0].loggedSituation)
        assertEquals(1L, rows[0].sessionS)
        assertEquals(0L, rows[0].closedMs)
    }

    @Test
    fun falseAlertLabelsAttachToTheEpisodeTheyWerePressedFor() {
        val text = writingSession(SituationEngine.BAND_K).second
        val report = SituationReplay.replay(SituationReplay.parse(text)!!)
        val pressedAt = report.episodes[0].startSec + 3   // a hand moving after the alert appeared

        val labels = "wall_ms,alert_code,alert_text,sei,session_s\n" +
            "1700000000000,1,\"Mắt nhắm\",40,$pressedAt\n"
        val (matched, unmatched) = SituationReplay.labelledEpisodes(report.episodes, labels)
        assertEquals(1, matched.size)
        assertEquals(0, unmatched)

        // A label hours away from any episode must come back unmatched, not be pinned to the nearest one.
        val far = "wall_ms,alert_code,alert_text,sei,session_s\n1700000000000,1,\"Mắt nhắm\",40,99999\n"
        val (none, two) = SituationReplay.labelledEpisodes(report.episodes, far + far.substring(far.indexOf('\n')))
        assertEquals(0, none.size)
        assertEquals(2, two)
    }

    @Test
    fun theWritingPostureCarriesTheFramesOfThisSession() {
        val frames = writingSession(SituationEngine.BAND_K).first
        // The posture in this session is a notebook-writing head, so DOWN carries the frames and nothing
        // claims a situation the student never held.
        val report = SituationReplay.replay(SituationReplay.parse(csvOf(frames))!!)
        assertTrue(report.framesIn(Situation.DOWN) > 60)
        assertEquals(0, report.framesIn(Situation.DOZING))
        assertEquals(0, report.framesIn(Situation.TURNED))
    }
}
