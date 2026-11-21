package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The situation layer exists to tell "bent over a notebook" apart from "dropping off", and it is
 * allowed to make an alert LATE but never to make one disappear. These tests hold it to both halves:
 * the geometry that separates the two postures, and the three bounds on what the gate may do.
 */
class SituationEngineTest {

    private fun frame(
        yaw: Float = 0f,
        pitch: Float = 0f,
        roll: Float = 0f,
        gazeV: Float = 0f,
        open: Float = 1.0f,
        closedMs: Long = 0L,
        poseStd: Float = 1.0f
    ) = SituationFrame(yaw, pitch, roll, gazeV, open, closedMs, poseStd)

    /** Head tipped 25 degrees, still moving, lids at `open`. Reads as writing, never as dozing. */
    private fun writingFrame(open: Float) = frame(pitch = 25f, open = open, poseStd = 1.0f)

    /**
     * Fills the DOWN bucket. The switch timer spends the first dozen frames on the previous label, so
     * 80 frames are fed to leave well over the 60 a situation needs, with openness wobbling between
     * `mean` and `mean + 0.02` so a spread - and therefore a floor - exists at all.
     */
    private fun learnWriting(e: SituationEngine, mean: Float, startMs: Long): Long {
        var t = startMs
        for (i in 0 until 80) {
            val wobble = if (i % 2 == 0) 0f else 0.02f
            e.update(writingFrame(mean + wobble), t)
            t += 33L
        }
        assertEquals(Situation.DOWN, e.situation)
        assertTrue(
            "expected the DOWN bucket to be full, got ${e.framesIn(Situation.DOWN)}",
            e.framesIn(Situation.DOWN) >= 60
        )
        return t
    }

    // ---------- geometry ----------

    @Test
    fun facingTheScreenIsForward() {
        val e = SituationEngine()
        assertEquals(Situation.FORWARD, e.classify(frame()))
    }

    @Test
    fun headTippedDownIsWriting() {
        val e = SituationEngine()
        assertEquals(Situation.DOWN, e.classify(frame(pitch = 20f)))
    }

    @Test
    fun eyesDroppedWithoutHeadMovementIsStillWriting() {
        val e = SituationEngine()
        assertEquals(Situation.DOWN, e.classify(frame(gazeV = 0.4f)))
    }

    @Test
    fun headOnOneShoulderIsTilt() {
        val e = SituationEngine()
        assertEquals(Situation.TILT, e.classify(frame(roll = -18f)))
    }

    @Test
    fun turnedHeadOutranksPitchBecauseEyelidsAreAlreadyRefusedThere() {
        val e = SituationEngine()
        assertEquals(Situation.TURNED, e.classify(frame(yaw = 30f, pitch = 25f)))
    }

    /**
     * The pair the whole feature rests on. Identical eyelids, identical pitch: the moving head is
     * writing and the motionless one is nodding off.
     */
    @Test
    fun movingHeadDownIsWritingAndStillHeadDownIsDozing() {
        val e = SituationEngine()
        assertEquals(Situation.DOWN, e.classify(frame(pitch = 30f, open = 0.35f, poseStd = 1.0f)))
        assertEquals(
            Situation.DOZING,
            e.classify(frame(pitch = 30f, open = 0.35f, closedMs = 500L, poseStd = 0.05f))
        )
    }

    /**
     * "Not measured yet" must not read as "motionless". Defaulting the window standard deviation to
     * zero would call every first frame of a session a doze.
     */
    @Test
    fun anUnmeasuredMotionWindowIsNotStillness() {
        val e = SituationEngine()
        assertEquals(
            Situation.DOWN,
            e.classify(frame(pitch = 30f, open = 0.35f, closedMs = 500L, poseStd = Float.NaN))
        )
    }

    @Test
    fun aSingleBlinkOnTheWayDownIsNotDozing() {
        val e = SituationEngine()
        assertEquals(
            Situation.DOWN,
            e.classify(frame(pitch = 30f, open = 0.35f, closedMs = 120L, poseStd = 0.05f))
        )
    }

    // ---------- switching ----------

    @Test
    fun oneOddFrameDoesNotMoveTheLabel() {
        val e = SituationEngine()
        e.update(frame(), 0L)
        // A lone turned-head frame: raw sees it, the published label waits for proof.
        e.update(frame(yaw = 40f), 33L)
        assertEquals(Situation.TURNED, e.rawSituation)
        assertEquals(Situation.FORWARD, e.situation)
        e.update(frame(yaw = 40f), 500L)
        assertEquals(Situation.TURNED, e.situation)
    }

    // ---------- bounds on the gate ----------

    /** Rule 1: before the student's own numbers exist, nothing may be held back. */
    @Test
    fun anUnlearnedSituationNeverDelaysAnAlert() {
        val e = SituationEngine()
        for (i in 0 until 20) e.update(writingFrame(0.75f), i * 33L)
        assertTrue(
            "DOWN must still be thin, got ${e.framesIn(Situation.DOWN)}",
            e.framesIn(Situation.DOWN) < 60
        )
        assertTrue(e.allow(true, writingFrame(0.74f), 10_000L))
    }

    /** The intended win: a lid reading ordinary for THIS student's writing posture buys 700 ms. */
    @Test
    fun aReadingInsideTheOwnWritingBandDelaysButOnlyBriefly() {
        val e = SituationEngine()
        val t = learnWriting(e, mean = 0.75f, startMs = 0L)
        val floor = e.opennessFloor(Situation.DOWN)
        assertTrue(floor.isFinite())
        val probe = writingFrame((floor + 0.005f))
        assertFalse("just inside the learned band must delay", e.allow(true, probe, t))
        assertFalse("still delaying 699 ms later", e.allow(true, probe, t + 699L))
        assertTrue("and firing 700 ms later", e.allow(true, probe, t + 700L))
    }

    /**
     * The other side of the same band: a doze that drops below what this student does even while
     * writing must not be held back one frame.
     */
    @Test
    fun belowTheOwnWritingBandTheAlertIsNotHeld() {
        val e = SituationEngine()
        val t = learnWriting(e, mean = 0.75f, startMs = 0L)
        val floor = e.opennessFloor(Situation.DOWN)
        assertTrue(e.allow(true, writingFrame(floor - 0.05f), t))
    }

    /** Rule 2: 2.5 seconds of shut eyes outvotes every situation and every learned band. */
    @Test
    fun aLongClosureIgnoresTheSituationEntirely() {
        val e = SituationEngine()
        val t = learnWriting(e, mean = 0.75f, startMs = 0L)
        val inside = writingFrame(e.opennessFloor(Situation.DOWN) + 0.01f)
        assertFalse(e.allow(true, inside, t))
        val shut = SituationFrame(25f, 25f, 0f, 0f, 0.0f, 2_500L, 1.0f)
        assertTrue("safety floor did not override the delay", e.allow(true, shut, t + 33L))
    }

    @Test
    fun dozingIsCorroboratingEvidenceSoNothingIsHeld() {
        val e = SituationEngine()
        var t = 0L
        for (i in 0 until 72) {
            e.update(
                SituationFrame(0f, 30f, 0f, 0f, 0.35f, 600L, 0.05f),
                t
            )
            t += 33L
        }
        assertEquals(Situation.DOZING, e.situation)
        assertTrue(e.allow(true, SituationFrame(0f, 30f, 0f, 0f, 0.35f, 600L, 0.05f), t))
    }

    /**
     * Rule 3 boundary for everything the layer does NOT claim: PERCLOS is a one-minute ratio, so this
     * layer must not be able to bend it, and neither may any alert that is not about the lids.
     */
    @Test
    fun nonEyelidAlertsAreNotTheBusinessOfThisLayer() {
        val e = SituationEngine()
        val t = learnWriting(e, mean = 0.75f, startMs = 0L)
        val inside = writingFrame(e.opennessFloor(Situation.DOWN) + 0.01f)
        assertTrue(e.allow(false, inside, t))
        // Same frame again, now as an eyelid alert: proves the flag, not the timer, made the difference.
        assertFalse(e.allow(true, inside, t))
    }

    /**
     * The tally the HUD shows, and the reason it exists: "context makes the alerts better" has to be
     * pointable at on screen, not only recoverable from a CSV that somebody pulls afterwards.
     *
     * Per episode, never per frame - three held frames of one closure are ONE intervention, and a
     * count that grew with the frame rate would be reporting the camera, not the rule.
     */
    @Test
    fun aHeldEpisodeIsTalliedOnceHoweverLongTheHoldRuns() {
        val e = SituationEngine()
        val t = learnWriting(e, mean = 0.75f, startMs = 0L)
        val inside = writingFrame(e.opennessFloor(Situation.DOWN) + 0.005f)
        assertFalse(e.allow(true, inside, t))
        assertFalse(e.allow(true, inside, t + 200L))
        assertFalse(e.allow(true, inside, t + 400L))
        assertEquals(1, e.intervenedEpisodes)
        assertEquals("a hold that was still running must not read as suppressed", 0, e.suppressedEpisodes)
        assertTrue("and firing 700 ms later", e.allow(true, inside, t + 700L))
        assertEquals(1, e.intervenedEpisodes)
        assertEquals("delayed is not the same result as gone", 0, e.suppressedEpisodes)
    }

    /**
     * The other half of the distinction, and the one the precision claim rests on: an episode whose
     * evidence stops while it is being held is the only way this layer makes an alert disappear.
     *
     * The tally is wiped by [SituationEngine.reset] along with the learning it describes, so the number
     * on screen always means "this session's", never "since the app was installed".
     */
    @Test
    fun anEpisodeThatEndsWithoutAnAlertIsTalliedAsSuppressed() {
        val e = SituationEngine()
        val t = learnWriting(e, mean = 0.75f, startMs = 0L)
        val inside = writingFrame(e.opennessFloor(Situation.DOWN) + 0.005f)
        assertFalse(e.allow(true, inside, t))
        assertTrue("no evidence left to hold", e.allow(false, inside, t + 100L))
        assertEquals(1, e.intervenedEpisodes)
        assertEquals(1, e.suppressedEpisodes)
        e.reset()
        assertEquals(0, e.intervenedEpisodes)
        assertEquals(0, e.suppressedEpisodes)
    }

    @Test
    fun resetWipesTheLearningSoTheNextStudentStartsClean() {
        val e = SituationEngine()
        learnWriting(e, mean = 0.75f, startMs = 0L)
        e.reset()
        assertEquals(Situation.UNKNOWN, e.situation)
        assertEquals(0, e.framesIn(Situation.DOWN))
        assertTrue(e.opennessFloor(Situation.DOWN).isNaN())
    }

    /** Kotlin already has copy() only on data classes; this keeps the probe frames readable. */
    private fun SituationFrame.copySafe() = this
}
