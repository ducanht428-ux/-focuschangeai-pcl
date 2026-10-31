package com.focuschange.ai

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

import kotlin.math.sqrt
// =====================================================================
// ADVANCED EYE ANALYSIS TOOLING (glasses, oblique viewing angle, squinting, two-eye asymmetry)
// =====================================================================

/** Eye analysis result for one frame (reused object, read only on cameraExecutor). */
internal class EyeFrame {
    var valid = false
    var openness = 1f
    var closed = false
    var closedStartMs = 0L
    var blinkDurationMs = 0L
    var blinkIncomplete = false
    var noisy = false
    var sigma = 0f
    var squintPct = 0f
    var asymPct = 0f
    var incompleteRatio = Float.NaN
    var squintAlert = false
    var asymAlert = false
    var incompleteAlert = false
    var usingBlendshapes = false
}

/**
 * Fused eye openness, robust to glasses and to oblique viewing angles.
 *
 * 1. Geometry: EAR from 5 eyelid point pairs (trimmed mean, dropping the highest/lowest value to reject glare-spiked points),
 *    normalized by each eye's own baseline, with foreshortening compensation for head rotation (cos yaw) and lid drop when looking down.
 * 2. Blendshapes (when available): MediaPipe's deep-learned model, far less affected by glasses and head tilt.
 * 3. Fusion with a disagreement check, One Euro filtering, close/open thresholds that adapt themselves to the noise level.
 * 4. Real-time blink state machine (independent of FPS), with two-threshold hysteresis.
 * 5. Discomfort indicators: prolonged squinting, uneven opening between the two eyes, incomplete blinks.
 */
internal class EyeEngine {

    // internal (not private) so the threshold table on the HUD prints the squint / two-eye
    // asymmetry values with two decimals instead of copying them by hand and drifting apart.
    internal companion object {
        val R_UP = intArrayOf(161, 160, 159, 158, 157)
        val R_LOW = intArrayOf(163, 144, 145, 153, 154)
        val L_UP = intArrayOf(388, 387, 386, 385, 384)
        val L_LOW = intArrayOf(390, 373, 374, 380, 381)
        const val R_OUT = 33
        const val R_IN = 133
        const val L_IN = 362
        const val L_OUT = 263

        const val CLOSE_O = 0.60f
        const val OPEN_O = 0.78f
        const val MIN_CLOSE_DWELL_MS = 40L
        const val MIN_BLINK_MS = 25L
        const val MAX_BLINK_MS = 1_000L
        const val INCOMPLETE_MIN_O = 0.45f
        const val INCOMPLETE_MAX_DT_MS = 55f
        const val ASYM_EXTRA_THRESHOLD = 0.25f
        const val PARTIAL_LO = 0.45f
        const val PARTIAL_HI = 0.80f
        const val DEG2RAD = 0.017453292f
        const val NOISY_ON = 0.12f
        const val NOISY_OFF = 0.09f
        const val NOISY_HOLD_MS = 10_000L
        const val SQUINT_ALERT_PCT = 40f
        const val ASYM_ALERT_PCT = 50f
        const val INCOMPLETE_ALERT_RATIO = 0.6f
        const val RING = 20
    }

    val out = EyeFrame()

    /**
     * Sensitivity: 0 low (only deep blinks registered), 1 medium, 2 high (also catches shallow
     * blinks), 3 very high. A higher level raises [closeO], so a less-shut eye already counts as
     * closed - which shortens the time an alert needs to confirm but also admits more noise.
     *
     * Only the CLOSE line moves. The reopen line stays capped at [OPEN_O]: if it rose with
     * [closeO], a more sensitive setting would demand a wider eye opening before a blink is
     * allowed to finish, so shallow or slowly-reopening blinks would start and never end -
     * under-counting blinks and inflating [closedDuration] into a false drowsy alert. The
     * min-gap floor in the state machine still guarantees hysteresis above the higher close line.
     */
    fun setSensitivity(level: Int) {
        closeO = when (level) {
            0 -> 0.54f
            2 -> 0.66f
            3 -> 0.70f
            else -> CLOSE_O
        }
        openO = minOf(OPEN_O, closeO + 0.18f)
    }

    private val tmp = FloatArray(5)
    private val openFilter = OneEuroFilter(4.0f, 1.0f)
    private val squintRatio = WindowRatio(60, 20_000L)
    private val asymRatio = WindowRatio(60, 30_000L)

    // Calibration
    private val calE1 = ArrayList<Float>()
    private val calE2 = ArrayList<Float>()
    private val calBs = ArrayList<Float>()
    private val calAsym = ArrayList<Float>()
    private var ready = false
    private var calBase1 = 0.3f
    private var calBase2 = 0.3f
    private var base1 = 0.3f
    private var base2 = 0.3f
    private var bsBase = 0.08f
    private var bsAsymBase = 0f
    // internal so a host test can pin the sensitivity trap: the reopen line must not climb with
    // the close line, or a more sensitive level demands a wider reopening to end a blink.
    internal var closeO = CLOSE_O
    internal var openO = OPEN_O

    // Blink state
    private var closed = false
    private var candStart = 0L
    private var candMin = 1f
    private var closedStart = 0L
    private var minDuring = 1f

    // Noise and rejection of broken frames
    private var sigma = 0.03f
    private var noisyStart = 0L
    private var prevWr = 0f
    private var prevWl = 0f
    private var glitchStreak = 0
    private var lastUpdateMs = 0L
    private var dtEma = 33f

    // Incomplete blinks
    private val ring = BooleanArray(RING)
    private var ringIdx = 0
    private var ringCount = 0

    private fun d(lm: Landmarks, a: Int, b: Int, w: Int, h: Int): Float {
        val dx = (lm.x(a) - lm.x(b)) * w
        val dy = (lm.y(a) - lm.y(b)) * h
        return sqrt(dx * dx + dy * dy)
    }

    /** EAR from 5 point pairs, trimmed mean. Returns NaN when the geometry is not valid. */
    private fun ear5(
        lm: Landmarks,
        up: IntArray,
        low: IntArray,
        a: Int,
        b: Int,
        w: Int,
        h: Int
    ): Float {
        var sum = 0f
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (i in 0 until 5) {
            val v = d(lm, up[i], low[i], w, h)
            tmp[i] = v
            sum += v
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val hor = d(lm, a, b, w, h)
        if (hor < 1f) return Float.NaN
        return ((sum - mn - mx) / 3f) / hor
    }

    private fun percentile(list: List<Float>, p: Float): Float {
        val s = list.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    fun reset() {
        calE1.clear()
        calE2.clear()
        calBs.clear()
        calAsym.clear()
        ready = false
        sigma = 0.03f
        noisyStart = 0L
        ringIdx = 0
        ringCount = 0
        squintRatio.reset()
        asymRatio.reset()
        pause()
    }

    /** Face lost or stream restarted: wipes the instant state, keeps the calibration. */
    fun pause() {
        closed = false
        candStart = 0L
        openFilter.reset()
        squintRatio.pause()
        asymRatio.pause()
        prevWr = 0f
        prevWl = 0f
        glitchStreak = 0
        lastUpdateMs = 0L
        out.valid = false
        out.closed = false
    }

    fun addCalibrationSample(lm: Landmarks, w: Int, h: Int, bs: FloatArray?) {
        val e1 = ear5(lm, R_UP, R_LOW, R_OUT, R_IN, w, h)
        val e2 = ear5(lm, L_UP, L_LOW, L_IN, L_OUT, w, h)
        if (e1.isNaN() || e2.isNaN()) return
        calE1.add(e1)
        calE2.add(e2)
        if (bs != null) {
            calBs.add((bs[0] + bs[1]) / 2f)
            calAsym.add(abs(bs[0] - bs[1]))
        }
    }

    /**
     * Locks in the baseline: takes the 70th percentile (dropping the blink/squint frames that slipped into the calibration set).
     * scale is the mixing factor with the value already learned in the habit profile.
     */
    fun finishCalibration(scale: Float) {
        if (calE1.size < 5) {
            ready = false
            return
        }
        calBase1 = percentile(calE1, 0.70f) * scale
        calBase2 = percentile(calE2, 0.70f) * scale
        base1 = calBase1
        base2 = calBase2
        bsBase = if (calBs.size >= 5) percentile(calBs, 0.5f).coerceIn(0f, 0.5f) else 0.08f
        // The two eyes are never perfectly symmetric: only call it asymmetric when it exceeds the natural asymmetry measured during calibration.
        bsAsymBase = if (calAsym.size >= 5) percentile(calAsym, 0.5f).coerceIn(0f, 0.3f) else 0f
        ready = true
        sigma = 0.03f
        noisyStart = 0L
        ringIdx = 0
        ringCount = 0
        squintRatio.reset()
        asymRatio.reset()
        pause()
    }

    private fun recordBlink(incomplete: Boolean) {
        ring[ringIdx] = incomplete
        ringIdx = (ringIdx + 1) % RING
        if (ringCount < RING) ringCount++
    }

    private fun incompleteRatio(): Float {
        if (ringCount < 8) return Float.NaN
        var n = 0
        for (i in 0 until ringCount) if (ring[i]) n++
        return n.toFloat() / ringCount.toFloat()
    }

    /**
     * This frame supports NO conclusion about the eyes. The indicators derived from earlier frames
     * must be wiped from the release: `out` is a reused object, so just lowering the valid flag to
     * false would leave MainActivity still reading a squint percentage from three seconds ago and
     * deducting SEI, even re-firing the squint alert, while the engine is saying "I don't know".
     *
     * sigma is set to NaN instead of 0: an empty CSV cell means "not measurable", while 0 means
     * "eye fully open, no noise" - two opposite conclusions on the very same number.
     */
    private fun clearConclusions(o: EyeFrame) {
        o.squintPct = 0f
        o.asymPct = 0f
        o.incompleteRatio = Float.NaN
        o.squintAlert = false
        o.asymAlert = false
        o.incompleteAlert = false
        o.noisy = false
        o.sigma = Float.NaN
    }

    private fun markInvalid(o: EyeFrame, closedState: Boolean) {
        o.valid = false
        o.closed = closedState
        clearConclusions(o)
    }

    fun update(
        lm: Landmarks,
        w: Int,
        h: Int,
        nowMs: Long,
        yawDev: Float,
        pitchDev: Float,
        gazeVDev: Float,
        bs: FloatArray?,
        poseOk: Boolean
    ): EyeFrame {
        val o = out
        o.blinkDurationMs = 0L
        o.blinkIncomplete = false
        o.usingBlendshapes = bs != null

        if (!ready) {
            markInvalid(o, closedState = false)
            return o
        }

        val e1 = ear5(lm, R_UP, R_LOW, R_OUT, R_IN, w, h)
        val e2 = ear5(lm, L_UP, L_LOW, L_IN, L_OUT, w, h)
        val wr = d(lm, R_OUT, R_IN, w, h)
        val wl = d(lm, L_IN, L_OUT, w, h)

        if (lastUpdateMs != 0L) {
            val dtFrame = (nowMs - lastUpdateMs).toFloat()
            if (dtFrame in 1f..300f) dtEma = dtEma * 0.95f + dtFrame * 0.05f
        }
        lastUpdateMs = nowMs

        // 1) Reject frames where landmarks "jump" abnormally (usually glasses glare or a one-frame tracking loss).
        var glitch = e1.isNaN() || e2.isNaN()
        if (!glitch && prevWr > 0f && prevWl > 0f) {
            val rr = wr / prevWr
            val rl = wl / prevWl
            if (rr > 1.3f || rr < 0.77f || rl > 1.3f || rl < 0.77f) glitch = true
        }
        if (glitch) {
            glitchStreak++
            if (glitchStreak > 8) {
                // New geometry stable for more than 8 frames: accept it as the new reference point.
                prevWr = 0f
                prevWl = 0f
                glitchStreak = 0
            }
            markInvalid(o, closedState = closed)
            return o
        }
        glitchStreak = 0
        prevWr = wr
        prevWl = wl

        // 2) Angle too oblique or weak signal: no conclusion about the eye state.
        if (!poseOk) {
            closed = false
            candStart = 0L
            openFilter.reset()
            markInvalid(o, closedState = false)
            return o
        }

        // 3) Compensated geometric openness: foreshortening on head turn and lid drop when looking down.
        val cosY = cos(min(abs(yawDev), 50f) * 0.9f * DEG2RAD)
        val down = max(pitchDev, 0f) * 0.006f + max(gazeVDev, 0f) * 0.5f
        val expected = (1f - down).coerceIn(0.7f, 1f)
        val c1 = e1 * cosY / expected
        val c2 = e2 * cosY / expected
        val og1 = (c1 / base1).coerceIn(0f, 1.5f)
        val og2 = (c2 / base2).coerceIn(0f, 1.5f)
        // Both lids of one face fall together, so the wider-open eye is the honest reading. Averaging let
        // the far eye collapse from yaw foreshortening and drag the whole face under the close line.
        val og = max(og1, og2)

        // 4) Blendshapes (when present) and fusion with a disagreement check between the two sources.
        var fused = og
        var squintBs = 0f
        var asymBs = 0f
        if (bs != null) {
            val blink = (bs[0] + bs[1]) / 2f
            val ob = (1f - (blink - bsBase) / (1f - bsBase).coerceAtLeast(0.5f)).coerceIn(0f, 1.3f)
            fused = if (abs(og - ob) > 0.45f) {
                // The two sources disagree strongly: geometry is usually the one skewed by glasses glare, so trust blendshapes more.
                0.8f * ob + 0.2f * og
            } else {
                0.65f * ob + 0.35f * og
            }
            squintBs = (bs[2] + bs[3]) / 2f
            asymBs = abs(bs[0] - bs[1]) - bsAsymBase
        }

        // 5) Time filtering and noise estimate while the eye is steadily open.
        val f = openFilter.filter(fused, nowMs)
        if (!closed && f > 0.85f) {
            sigma = sigma * 0.98f + abs(fused - f) * 1.25f * 0.02f
        }
        sigma = sigma.coerceIn(0f, 0.3f)

        if (sigma > NOISY_ON) {
            if (noisyStart == 0L) noisyStart = nowMs
        } else if (sigma < NOISY_OFF) {
            noisyStart = 0L
        }
        val noisy = noisyStart != 0L && nowMs - noisyStart >= NOISY_HOLD_MS

        // 6) Self-adapting close/open thresholds: the noisier (glasses, glare), the stricter, to avoid false blinks.
        val closeThr = (closeO - 1.5f * sigma).coerceIn(0.40f, closeO)
        val openThr = max(closeThr + 0.12f, openO - sigma).coerceAtMost(0.88f)

        // 7) Real-time blink state machine.
        if (!closed) {
            if (f < closeThr) {
                if (candStart == 0L) {
                    candStart = nowMs
                    candMin = f
                }
                candMin = min(candMin, f)
                val dwell = nowMs - candStart
                // Firmly closed if long enough, or closed very deep (catches a one-frame blink when FPS is low).
                if (dwell >= MIN_CLOSE_DWELL_MS || f < closeThr - 0.15f) {
                    closed = true
                    closedStart = candStart
                    minDuring = candMin
                }
            } else {
                candStart = 0L
            }
        } else {
            minDuring = min(minDuring, f)
            if (f > openThr) {
                val dur = nowMs - closedStart
                if (dur in MIN_BLINK_MS..MAX_BLINK_MS) {
                    o.blinkDurationMs = dur
                    // Low FPS (>55 ms/frame) can miss the bottom of the blink: do not conclude "incomplete".
                    if (dtEma <= INCOMPLETE_MAX_DT_MS) {
                        o.blinkIncomplete = minDuring > INCOMPLETE_MIN_O
                        recordBlink(o.blinkIncomplete)
                    }
                }
                closed = false
                candStart = 0L
            }
        }

        // 8) Discomfort indicators: prolonged squinting, the two eyes opening unevenly.
        var partial = !closed && f >= PARTIAL_LO && f < PARTIAL_HI
        if (!partial && !closed && squintBs > 0.45f && f < 0.9f) partial = true
        squintRatio.record(nowMs, partial)

        val frontal = abs(yawDev) < 15f
        val asymFlag = if (bs != null) asymBs > ASYM_EXTRA_THRESHOLD else abs(og1 - og2) > 0.35f
        asymRatio.record(nowMs, frontal && !closed && f > 0.35f && asymFlag)

        val sq = squintRatio.percent(nowMs)
        val asym = asymRatio.percent(nowMs)
        val inc = incompleteRatio()

        // 9) Slowly tracked baseline: follows changing light/glasses but must not "drift" with fatigue.
        if (f > 0.8f && !closed) {
            if (c1 > base1) {
                if (c1 < base1 * 1.35f) base1 += 0.02f * (c1 - base1)
            } else if (c1 > base1 * 0.85f) {
                base1 -= 0.0004f * (base1 - c1)
            }
            if (c2 > base2) {
                if (c2 < base2 * 1.35f) base2 += 0.02f * (c2 - base2)
            } else if (c2 > base2 * 0.85f) {
                base2 -= 0.0004f * (base2 - c2)
            }
            base1 = base1.coerceIn(calBase1 * 0.85f, calBase1 * 1.25f)
            base2 = base2.coerceIn(calBase2 * 0.85f, calBase2 * 1.25f)
        }

        o.valid = true
        o.openness = f
        o.closed = closed
        o.closedStartMs = closedStart
        o.sigma = sigma
        o.noisy = noisy
        o.squintPct = sq
        o.asymPct = asym
        o.incompleteRatio = inc
        o.squintAlert = sq >= SQUINT_ALERT_PCT
        o.asymAlert = asym >= ASYM_ALERT_PCT
        o.incompleteAlert = !inc.isNaN() && inc >= INCOMPLETE_ALERT_RATIO
        return o
    }
}


// =====================================================================
// LOW-LEVEL IMAGE PROCESSING: adaptive CLAHE, light denoising, face ROI
// =====================================================================

/**
 * Improves the image before it is handed to MediaPipe under poor lighting, working only on the Y luma
 * channel (never touching colour), so it costs far less than processing the RGB image.
 *
 * 1. Measures the REAL light over the face region (ROI) and over the whole frame: brightness, contrast, blown-highlight ratio.
 *    From that it detects backlighting (dark face, glaring background) and a blown-out face.
 * 2. The controller has "lag" (hysteresis) and a minimum hold time: no rapid on/off toggling that would
 *    make the image flicker and shift the calibration baseline.
 * 3. CLAHE (contrast-limited adaptive histogram equalization) on the face ROI, the same algorithm
 *    as OpenCV: per-tile histogram, clipped threshold, redistributed remainder, bilinear interpolation between 4 tiles.
 * 4. Separable 3x3 Gaussian blur only when badly underexposed (CLAHE amplifies noise).
 * 5. Pauses itself for 30 seconds when the processing cost exceeds the time budget, so it cannot drag FPS down.
 */
internal class ImageEnhancer {

    private companion object {
        const val TILES_ROI = 4
        const val TILES_FULL = 8
        const val CLIP_LIMIT = 2.5f
        const val ON_MEAN = 85f
        const val OFF_MEAN = 105f
        const val ON_STD = 28f
        const val OFF_STD = 36f
        const val DENOISE_MEAN = 65f
        const val MIN_TOGGLE_MS = 4_000L
        const val BUDGET_MS = 12f
        const val BUDGET_WARMUP_FRAMES = 30
        const val PAUSE_MS = 30_000L
        const val MIN_REGION = 64
        const val SNAP = 8
        const val GLOBAL_STEP = 8
        const val ROI_STEP = 4
    }

    var active = false
        private set
    var denoising = false
        private set
    var paused = false
        private set
    var userOff = false
        private set
    var usingRoi = false
        private set
    var faceMean = 128f
        private set
    var faceStd = 50f
        private set
    var globalMean = 128f
        private set
    var backlit = false
        private set
    var overexposed = false
        private set
    var costMs = 0f
        private set

    private var lastToggleMs = 0L
    private var pausedUntilMs = 0L
    private var enhancedFrames = 0

    // Temporary measurements produced by measure()
    private var mMean = 128f
    private var mStd = 50f
    private var mBright = 0f

    // ROI in sensor coordinate space: x0, y0, x1, y1
    private val roi = IntArray(4)
    private var cornerX = 0f
    private var cornerY = 0f

    // Reused buffers (no allocation per frame)
    private var hist = IntArray(0)
    private var lut = IntArray(0)
    private var tmp = IntArray(0)
    private var colI0 = IntArray(0)
    private var colI1 = IntArray(0)
    private var colW = IntArray(0)
    private var colT = IntArray(0)
    private var rowI0 = IntArray(0)
    private var rowI1 = IntArray(0)
    private var rowW = IntArray(0)
    private var rowT = IntArray(0)

    /** Measures brightness, standard deviation and blown-pixel ratio of a region by sparse sampling. */
    private fun measure(
        y: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        step: Int
    ) {
        var n = 0
        var sum = 0L
        var sumSq = 0L
        var bright = 0
        var row = y0
        while (row < y1) {
            val base = row * rowStride
            var col = x0
            while (col < x1) {
                val v = y[base + col * pixelStride].toInt() and 0xFF
                sum += v
                sumSq += (v * v).toLong()
                if (v > 245) bright++
                n++
                col += step
            }
            row += step
        }

        if (n == 0) {
            mMean = 128f
            mStd = 50f
            mBright = 0f
            return
        }
        val mean = sum.toDouble() / n
        val variance = sumSq.toDouble() / n - mean * mean
        mMean = mean.toFloat()
        mStd = sqrt(max(variance, 0.0)).toFloat()
        mBright = bright.toFloat() / n.toFloat()
    }

    /**
     * Maps a normalized point (u, v) of the image rotated clockwise by `rotation` degrees
     * back to normalized coordinates of the original sensor image (verified for 0/90/180/270).
     */
    private fun mapCorner(u: Float, v: Float, rotation: Int) {
        when (rotation) {
            90 -> {
                cornerX = v
                cornerY = 1f - u
            }
            180 -> {
                cornerX = 1f - u
                cornerY = 1f - v
            }
            270 -> {
                cornerX = 1f - v
                cornerY = u
            }
            else -> {
                cornerX = u
                cornerY = v
            }
        }
    }

    /** Converts the face box into a pixel region of the Y plane, rounded onto an 8-pixel grid for stability. */
    private fun mapRoi(box: FloatArray, rotation: Int, w: Int, h: Int): Boolean {
        mapCorner(box[0], box[1], rotation)
        val ax = cornerX
        val ay = cornerY
        mapCorner(box[2], box[3], rotation)
        val bx = cornerX
        val by = cornerY

        val fx0 = min(ax, bx)
        val fx1 = max(ax, bx)
        val fy0 = min(ay, by)
        val fy1 = max(ay, by)

        val x0 = ((fx0 * w).toInt() / SNAP * SNAP).coerceIn(0, w)
        val y0 = ((fy0 * h).toInt() / SNAP * SNAP).coerceIn(0, h)
        val x1 = (((fx1 * w).toInt() + SNAP - 1) / SNAP * SNAP).coerceIn(0, w)
        val y1 = (((fy1 * h).toInt() + SNAP - 1) / SNAP * SNAP).coerceIn(0, h)

        if (x1 - x0 < MIN_REGION || y1 - y0 < MIN_REGION) return false
        roi[0] = x0
        roi[1] = y0
        roi[2] = x1
        roi[3] = y1
        return true
    }

    /** Turns on when underexposed or low in contrast, turns off once good enough (separate on/off thresholds). */
    private fun decide(nowMs: Long, mean: Float, std: Float) {
        val need = if (active) {
            !(mean > OFF_MEAN && std > OFF_STD)
        } else {
            mean < ON_MEAN || std < ON_STD
        }
        if (need != active && nowMs - lastToggleMs >= MIN_TOGGLE_MS) {
            active = need
            lastToggleMs = nowMs
        }
    }

    /**
     * Processes one frame (editing the copied Y array in place) and returns the RAW luminance of the face
     * (or of the whole frame when the face position is not known yet) to rate the signal quality.
     */
    fun process(
        y: ByteArray,
        yLast: Int,
        rowStride: Int,
        pixelStride: Int,
        w: Int,
        h: Int,
        rotation: Int,
        box: FloatArray,
        boxFresh: Boolean,
        nowMs: Long,
        auto: Boolean
    ): Float {
        userOff = !auto
        val hasRoi = boxFresh && mapRoi(box, rotation, w, h)
        usingRoi = hasRoi

        // The region being read must lie entirely inside the Y plane buffer.
        val lastNeeded = (h - 1) * rowStride + (w - 1) * pixelStride
        if (lastNeeded > yLast || w < MIN_REGION || h < MIN_REGION) {
            active = false
            denoising = false
            return faceMean
        }

        measure(y, rowStride, pixelStride, 0, 0, w, h, GLOBAL_STEP)
        globalMean = mMean
        val globalStd = mStd
        val globalBright = mBright

        if (hasRoi) {
            measure(y, rowStride, pixelStride, roi[0], roi[1], roi[2], roi[3], ROI_STEP)
            faceMean = mMean
            faceStd = mStd
            overexposed = mBright > 0.18f
        } else {
            faceMean = globalMean
            faceStd = globalStd
            overexposed = globalBright > 0.30f
        }
        backlit = hasRoi && globalMean - faceMean > 45f && faceMean < 95f
        val result = faceMean

        paused = nowMs < pausedUntilMs
        if (!auto || paused) {
            active = false
            denoising = false
            return result
        }

        decide(nowMs, faceMean, faceStd)
        denoising = active && faceMean < DENOISE_MEAN
        if (!active) return result

        val t0 = System.nanoTime()
        val rx0 = if (hasRoi) roi[0] else 0
        val ry0 = if (hasRoi) roi[1] else 0
        val rw = if (hasRoi) roi[2] - roi[0] else w
        val rh = if (hasRoi) roi[3] - roi[1] else h

        if (denoising) blur3(y, rowStride, pixelStride, rx0, ry0, rw, rh)
        val tiles = if (hasRoi) TILES_ROI else TILES_FULL
        clahe(y, rowStride, pixelStride, rx0, ry0, rw, rh, tiles, tiles, CLIP_LIMIT)

        val ms = (System.nanoTime() - t0) / 1_000_000f
        costMs = if (enhancedFrames == 0) ms else costMs * 0.9f + ms * 0.1f
        enhancedFrames++

        // FPS guard (the warmup phase is skipped because the code is still "cold", so it measures slower than reality).
        if (enhancedFrames > BUDGET_WARMUP_FRAMES && costMs > BUDGET_MS) {
            pausedUntilMs = nowMs + PAUSE_MS
            active = false
            denoising = false
            enhancedFrames = 0
            costMs = 0f
        }
        return result
    }

    /** Builds the lookup for one axis: indices of the two neighbouring tiles, weight (256 scale) and the tile holding the point. */
    private fun buildAxis(
        n: Int,
        tiles: Int,
        i0: IntArray,
        i1: IntArray,
        wt: IntArray,
        tileOf: IntArray
    ) {
        for (p in 0 until n) {
            val f = (p + 0.5f) * tiles / n - 0.5f
            if (f <= 0f) {
                i0[p] = 0
                i1[p] = 0
                wt[p] = 0
            } else if (f >= tiles - 1) {
                i0[p] = tiles - 1
                i1[p] = tiles - 1
                wt[p] = 0
            } else {
                val a = f.toInt()
                i0[p] = a
                i1[p] = a + 1
                wt[p] = ((f - a) * 256f).toInt().coerceIn(0, 256)
            }
            tileOf[p] = min(tiles - 1, p * tiles / n)
        }
    }

    /**
     * CLAHE over a rectangular region of the Y plane (honouring rowStride and pixelStride).
     * Same algorithm and same integer arithmetic as cross-checked against OpenCV (max difference of 1 gray level).
     */
    private fun clahe(
        y: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        rx0: Int,
        ry0: Int,
        rw: Int,
        rh: Int,
        tilesX: Int,
        tilesY: Int,
        clipLimit: Float
    ) {
        val nT = tilesX * tilesY
        if (hist.size < nT * 256) {
            hist = IntArray(nT * 256)
            lut = IntArray(nT * 256)
        }
        if (colI0.size < rw) {
            colI0 = IntArray(rw)
            colI1 = IntArray(rw)
            colW = IntArray(rw)
            colT = IntArray(rw)
        }
        if (rowI0.size < rh) {
            rowI0 = IntArray(rh)
            rowI1 = IntArray(rh)
            rowW = IntArray(rh)
            rowT = IntArray(rh)
        }
        java.util.Arrays.fill(hist, 0, nT * 256, 0)

        buildAxis(rw, tilesX, colI0, colI1, colW, colT)
        buildAxis(rh, tilesY, rowI0, rowI1, rowW, rowT)

        // 1) Per-tile histogram
        for (row in 0 until rh) {
            val tRow = rowT[row] * tilesX
            var idx = (ry0 + row) * rowStride + rx0 * pixelStride
            for (col in 0 until rw) {
                hist[(tRow + colT[col]) * 256 + (y[idx].toInt() and 0xFF)]++
                idx += pixelStride
            }
        }

        // 2) Clip to the threshold, redistribute the remainder (evenly, then spread in steps like
        // OpenCV), then build the lookup table from the CDF
        for (t in 0 until nT) {
            val off = t * 256
            var total = 0
            for (i in 0 until 256) total += hist[off + i]
            if (total == 0) {
                for (i in 0 until 256) lut[off + i] = i
                continue
            }

            val limit = max(1, (clipLimit * total / 256f).toInt())
            var excess = 0
            for (i in 0 until 256) {
                val c = hist[off + i]
                if (c > limit) {
                    excess += c - limit
                    hist[off + i] = limit
                }
            }
            val add = excess / 256
            var rem = excess - add * 256
            if (add > 0) {
                for (i in 0 until 256) hist[off + i] += add
            }
            if (rem > 0) {
                val step = max(256 / rem, 1)
                var i = 0
                while (i < 256 && rem > 0) {
                    hist[off + i]++
                    rem--
                    i += step
                }
            }

            val scale = 255f / total
            var cum = 0
            for (i in 0 until 256) {
                cum += hist[off + i]
                lut[off + i] = (cum * scale + 0.5f).toInt().coerceIn(0, 255)
            }
        }

        // 3) Bilinear interpolation between the lookup tables of the 4 neighbouring tiles
        // (integer arithmetic on a 256 x 256 scale)
        for (row in 0 until rh) {
            val r0 = rowI0[row] * tilesX
            val r1 = rowI1[row] * tilesX
            val wy = rowW[row]
            var idx = (ry0 + row) * rowStride + rx0 * pixelStride
            for (col in 0 until rw) {
                val v = y[idx].toInt() and 0xFF
                val wx = colW[col]
                val a = lut[(r0 + colI0[col]) * 256 + v]
                val b = lut[(r0 + colI1[col]) * 256 + v]
                val c = lut[(r1 + colI0[col]) * 256 + v]
                val d = lut[(r1 + colI1[col]) * 256 + v]
                val top = a * (256 - wx) + b * wx
                val bot = c * (256 - wx) + d * wx
                val out = (top * (256 - wy) + bot * wy + 32768) shr 16
                y[idx] = out.coerceIn(0, 255).toByte()
                idx += pixelStride
            }
        }
    }

    /** 3x3 Gaussian filter ([1 2 1] applied per axis, repeating edges) over a region of the Y plane. */
    private fun blur3(
        y: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        rx0: Int,
        ry0: Int,
        rw: Int,
        rh: Int
    ) {
        if (tmp.size < rw * rh) tmp = IntArray(rw * rh)

        for (row in 0 until rh) {
            val base = (ry0 + row) * rowStride + rx0 * pixelStride
            for (col in 0 until rw) {
                val l = if (col > 0) col - 1 else 0
                val r = if (col < rw - 1) col + 1 else rw - 1
                val a = y[base + l * pixelStride].toInt() and 0xFF
                val b = y[base + col * pixelStride].toInt() and 0xFF
                val c = y[base + r * pixelStride].toInt() and 0xFF
                tmp[row * rw + col] = a + 2 * b + c
            }
        }

        for (row in 0 until rh) {
            val u = if (row > 0) row - 1 else 0
            val d = if (row < rh - 1) row + 1 else rh - 1
            val base = (ry0 + row) * rowStride + rx0 * pixelStride
            for (col in 0 until rw) {
                val s = tmp[u * rw + col] + 2 * tmp[row * rw + col] + tmp[d * rw + col]
                y[base + col * pixelStride] = ((s + 8) shr 4).toByte()
            }
        }
    }

    fun describe(): String {
        val state = when {
            userOff -> "tăng cường ảnh: tắt"
            paused -> "CLAHE tạm tắt để giữ FPS"
            active -> String.format(
                Locale.US,
                "CLAHE bật%s (%.1f ms)",
                if (denoising) " + lọc nhiễu" else "",
                costMs
            )
            else -> "ánh sáng đủ, không cần tăng cường"
        }
        return String.format(
            Locale.US,
            "sáng mặt %.0f (toàn khung %.0f) | tương phản %.0f | %s%s%s",
            faceMean,
            globalMean,
            faceStd,
            state,
            if (backlit) " | ngược sáng" else "",
            if (overexposed) " | mặt cháy sáng" else ""
        )
    }
}