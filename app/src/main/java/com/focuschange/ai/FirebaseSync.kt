package com.focuschange.ai

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import java.util.HashMap
import kotlin.math.roundToInt

/**
 * What one measurement session leaves behind in the cloud. These are AVERAGES over the whole
 * session, not individual frames: a session of ~2,000 frames costs exactly one Firestore write.
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
     * How this session compared with the same student's own two-sided band. Empty when the
     * profile still has too few sessions to build any band.
     *
     * Kept separate from the alert counters on purpose: a session can cross no alert threshold at
     * all and still sit outside its own familiar band - which is exactly what a session average
     * hides.
     */
    val bandOutcomes: List<BandOutcome>
)

/**
 * One heartbeat: "this student is being measured right now, the session has run this long, and
 * here is the data so far".
 *
 * Sent on its own and far leaner than the session summary: the teacher's screen needs to know a
 * measurement is alive, not twelve averages every five minutes. Full numbers live in the
 * `sessions` record written when the session ends.
 *
 * There is deliberately no `ineligible_reason` here: a running session is by definition not long
 * enough yet, so every beat would be marked ineligible and the teacher would read it as "this
 * student's measurement is broken". Eligibility is judged only on the end-of-session summary.
 *
 * [sessionId] and [startedAtWallMs] are the same value by construction (HabitSession uses the
 * wall-clock time of session start as its id), but they are written as two columns so the record
 * explains itself instead of making the reader infer a meaning from a 13-digit number.
 */
internal class HeartbeatReport(
    val studentId: String,
    val studentName: String,
    val sessionId: Long,
    val startedAtWallMs: Long,
    val beatAtWallMs: Long,
    /**
     * Session time counted so far, on the same clock as earlier beats. This is the value that
     * grows while the camera runs and freezes when the screen goes off - the shortest way to tell
     * "this student is on a break" apart from "this device is not measuring".
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
 * The two-sided range one student is currently scored against - what this student looks like when
 * they are just being themselves, learned from that student's own past sessions.
 *
 * A separate document rather than only a copy inside every session record: the session table answers
 * "how did this visit go", this one answers "what does the app consider normal for this student right
 * now". Without it, comparing a week of sessions means opening the last record to find the range the
 * earlier ones were judged by.
 *
 * It is a DESCRIPTION, not a pass mark. Nothing in the app raises an alert because a value left this
 * range, and no alert is silenced because a value stayed inside it.
 */
internal class BandReport(
    val studentId: String,
    val studentName: String,
    /** How many qualifying sessions the range was learned from. */
    val sessionsUsed: Int,
    val updatedAtWallMs: Long,
    /** Only the features that actually have a range. A missing feature is absent, never zero. */
    val bands: List<FeatureBand>,
    val device: String,
    val appVersion: String
)

/** One feature's range, in that feature's own unit. */
internal class FeatureBand(
    val feature: Int,
    val typical: Float,
    val min: Float,
    val max: Float,
    val spread: Float
)

/**
 * Uploads session summaries and heartbeats to Cloud Firestore.
 *
 * Split out of MainActivity so the rest of the app is unaffected when Firebase is unavailable: a
 * missing config file, no network, a permission denial - all of them only turn one line in the
 * panel red, none of them disturb the measurement in progress.
 */
internal object FirebaseSync {
    private const val COLLECTION = "sessions"

    /**
     * One document per student, overwritten by each heartbeat. Separate from `sessions` because
     * the teacher's screen needs the question "who is being measured right now" without scanning
     * and filtering an entire history table.
     */
    private const val PRESENCE_COLLECTION = "presence"

    /**
     * One document per student holding the two-sided range they are scored against. Separate from
     * both tables above because it answers a different question: not "what happened in this session"
     * and not "is a measurement alive now", but "what is normal for this student at the moment".
     */
    private const val BAND_COLLECTION = "bands"

    /**
     * Record structure version. Bump it when a column is RENAMED so old data is not read shifted
     * onto the wrong meaning. The band columns were only ADDED - nothing was renamed and no old
     * column changed meaning - so a version 1 record still reads correctly, and this stays 1
     * rather than becoming 2.
     */
    private const val SCHEMA = 1

    /**
     * Keys of the 12 features. Taken from [HabitFeature.KEYS] instead of being listed again here:
     * the key names in Firebase and the band column names in the CSV must be one list. Write it
     * twice and the two copies will drift by one character sooner or later, and the cloud record
     * stops being readable.
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
     * Heartbeats are counted separately, not merged into [sentCount]: the two record types differ
     * in cadence and in consequence. A failed beat only means "the teacher could not see this
     * student at this moment", while a failed session means the data is gone. Merged, the panel
     * cannot tell the two situations apart.
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
     * Wall-clock timestamp of the most recent beat we TRIED to send, not of a beat Firestore
     * accepted. Printed as an absolute time rather than "n minutes ago" because a relative number
     * on screen is only refreshed when something else redraws it - so it would lie.
     */
    @Volatile
    var lastHeartbeatWallMs = 0L
        private set

    val enabled: Boolean
        get() = db != null

    /**
     * Band documents are counted apart from sessions and beats: one send per student per session,
     * and a failure there changes no measurement, it only leaves the teacher's reference stale.
     */
    @Volatile
    var bandSent = 0L
        private set

    @Volatile
    var bandFailed = 0L
        private set

    @Volatile
    var lastBandError = ""
        private set

    /**
     * Attaches to the FirebaseApp that google-services.json's ContentProvider already started.
     * getInstance() throws IllegalStateException when there is no config file: that has to be
     * caught here, because this is the boundary with an external library, not a bug of ours.
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

    /** Builds the record and sends it. [onResult] runs on the main thread, at the moment the screen text changes. */
    fun upload(r: SessionReport, onResult: (Boolean) -> Unit) {
        val client = db
        if (client == null) {
            onResult(false)
            return
        }
        val payload = buildPayload(r)
        // Deterministic document name from student + session: sending the same session twice (two
        // taps, or Firestore replaying a queued write) overwrites, instead of creating a second record.
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
     * Turns every character that is unsafe in a Firestore document name into a dash. Written in
     * one place because both tables need it: `sessions/<id>-<session>` and `presence/<id>`. If
     * each table filtered its own way, a student name containing a space would create two
     * different documents for one student and the teacher's screen would show only one of them.
     */
    private fun sanitize(studentId: String): String {
        val safe = StringBuilder(studentId.length + 8)
        for (ch in studentId) {
            safe.append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-')
        }
        return safe.toString()
    }

    /**
     * Overwrites the `presence/<student>` document with one beat.
     *
     * `set` on a deterministic document name rather than add(): one "currently measuring" row per
     * student, each beat replacing the previous one, and a duplicate send creating no extra
     * record. [onResult] runs on the main thread.
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

    /**
     * Overwrites `bands/<student>` with the range this student is scored against, sent once per
     * session at the moment the band is built.
     *
     * `set` on a name derived from the student, like [uploadHeartbeat]: a resend replaces its own
     * document instead of adding a second copy, so the table always holds one current answer per
     * student rather than an archive nobody queries correctly.
     */
    fun uploadBand(r: BandReport, onResult: (Boolean) -> Unit) {
        val client = db
        if (client == null) {
            onResult(false)
            return
        }
        client.document("$BAND_COLLECTION/${sanitize(r.studentId)}").set(buildBandPayload(r))
            .addOnSuccessListener {
                bandSent++
                lastBandError = ""
                onResult(true)
            }
            .addOnFailureListener { e ->
                bandFailed++
                lastBandError = e.javaClass.simpleName
                onResult(false)
            }
    }

    private fun buildBandPayload(r: BandReport): HashMap<String, Any> {
        val out = HashMap<String, Any>(8 + 4 * r.bands.size)
        out["schema_b"] = SCHEMA
        out["student_id"] = r.studentId
        out["student_name"] = r.studentName
        out["sessions_used"] = r.sessionsUsed
        out["updated_at_wall_ms"] = r.updatedAtWallMs
        // The multiplier travels with the range: without "2.5" a reader cannot tell a genuinely
        // restless student from a well-behaved one whose band is wide for another reason.
        out["band_k"] = HabitConfig.BAND_K.toDouble()
        out["device"] = r.device
        out["app_version"] = r.appVersion
        for (b in r.bands) {
            val key = FEATURE_KEYS[b.feature]
            out["band_typical_$key"] = round2(b.typical)
            out["band_min_$key"] = round2(b.min)
            out["band_max_$key"] = round2(b.max)
            out["band_spread_$key"] = round2(b.spread)
        }
        return out
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
        // These two are written only when samples actually exist. round2(NaN) returns 0, and
        // "SEI = 0" reads as "this student fell asleep" while "quality = 0" reads as "the camera
        // is broken" - both wrong: nothing was measured yet. A missing column is the honest
        // signal; a fabricated zero is a false one. The mean of an empty accumulator is NaN, so
        // isFinite is exactly "were there samples".
        if (s.quality.isFinite()) {
            out["quality_mean"] = round2(s.quality)
        }
        val seiN = s.count[HabitFeature.SEI]
        if (seiN > 0) {
            // SEI is the first number a teacher looks at, so it gets its own column instead of
            // making the reader look up "mean_sei" as in the sessions table. This is the average
            // over the whole session up to this beat, NOT the average of the last five minutes -
            // short-term speed has to come from the CSV.
            out["sei_mean"] = round2(s.mean[HabitFeature.SEI])
            out["sei_n"] = seiN
        }
        out["alerts_total"] = r.alertsTotal
        out["false_alerts"] = r.falseAlerts
        out["device"] = r.device
        out["app_version"] = r.appVersion
        // The band is only three aggregate numbers until the session ends; per-feature detail
        // lives in the sessions record, and repeating it every five minutes would triple the data
        // for no added information.
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
            // n = 0 means the feature got no sample at all in this session. Writing 0 would read
            // as "measured 0", so only features that really have samples are written.
            val n = s.count[f]
            if (n > 0) {
                out["mean_$key"] = round2(s.mean[f])
                out["std_$key"] = round2(s.std[f])
                out["n_$key"] = n
            }
        }
        if (r.bandOutcomes.isNotEmpty()) {
            // The band's multiplier travels with the record: only by reading "band = typical value
            // +/- 2.5 times the natural spread" can a teacher see why two students with the same
            // average can have very different band widths.
            out["band_k"] = HabitConfig.BAND_K.toDouble()
            for (b in r.bandOutcomes) {
                val key = FEATURE_KEYS[b.feature]
                // Frame counts are written, not percentages: averaging over a week means adding
                // counters up, while the mean of several rounded percentages means nothing.
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

    /** One short line for the control panel: how many sessions went out, and what is still queued. */
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
                // Built per call: SimpleDateFormat is not thread-safe, and this status line can be
                // asked for from the camera thread as well as from the main thread.
                .append(
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(lastHeartbeatWallMs))
                )
        }
        sb.append(" - khoảng: ").append(bandSent).append(" đã gửi")
        if (bandFailed > 0L) {
            sb.append(", ").append(bandFailed).append(" thất bại")
            if (lastBandError.isNotEmpty()) sb.append(" (").append(lastBandError).append(')')
        }
        return sb.toString()
    }
}
