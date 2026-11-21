package com.focuschange.ai

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.File
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
     * Name of the send-again queue inside the app private storage. Sessions that the server refused
     * are written here so the recording week is not thrown away because the database appeared late.
     */
    private const val PENDING_FILE = "firebase_pending.txt"

    /**
     * Cap for that queue. It exists so a write that will ALWAYS be refused (wrong rules, wrong
     * project) cannot grow the file forever; the oldest records are dropped and counted in
     * [pendingDropped] rather than the newest ones, because the newest are the ones the teacher
     * still expects to see.
     */
    private const val MAX_PENDING = 300

    /** SharedPreferences key holding the session ids Firestore has already accepted. */
    private const val SENT_IDS_KEY = "sent_session_ids"

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
     * How many documents are waiting in the app for another try. Counted apart from [failedCount]:
     * a failure that was kept is a different situation from a failure that was lost, and the panel
     * has to be able to say which one the user is looking at.
     */
    @Volatile
    var pendingCount = 0
        private set

    /** Records pushed out of the queue by [MAX_PENDING]. Should stay at zero in a normal term. */
    @Volatile
    var pendingDropped = 0
        private set

    @Volatile
    var lastPendingError = ""
        private set

    private val pendingLock = Any()

    /** Queued payloads. Every access that can mutate it happens inside [pendingLock]. */
    private var pending: MutableList<PendingUpload> = ArrayList()

    private var pendingLoaded = false

    /** Session ids accepted by Firestore, keyed by student id. Guards the archive resend. */
    private val confirmedIds = HashMap<String, MutableSet<Long>>()
    private var confirmedLoaded = false

    @Volatile
    private var resending = false

    /** True while one batch of records is still waiting for the backend. */
    val sendingNow: Boolean get() = resending

    /** Application context, kept only to reach filesDir and SharedPreferences. */
    @Volatile
    private var storage: Context? = null

    /**
     * Attaches to the FirebaseApp that google-services.json's ContentProvider already started.
     * getInstance() throws IllegalStateException when there is no config file: that has to be
     * caught here, because this is the boundary with an external library, not a bug of ours.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        storage = app
        loadPending()
        loadConfirmed()
        if (db == null) {
            try {
                com.google.firebase.FirebaseApp.initializeApp(app)
                db = FirebaseFirestore.getInstance()
                disabledReason = ""
            } catch (e: Exception) {
                db = null
                disabledReason = e.javaClass.simpleName
            }
        }
        // A queue that survived the app being closed means the database was missing or refusing
        // writes at the time. Try it again as soon as a client exists, without waiting for a tap:
        // the booth demo has nobody watching the panel.
        if (db != null && pendingCount > 0) resendPending(null)
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
        val docPath = "$COLLECTION/" + documentId(r.studentId, r.summary.id)
        inFlight++
        client.document(docPath).set(payload)
            .addOnSuccessListener {
                inFlight--
                sentCount++
                lastError = ""
                markSent(r.studentId, r.summary.id)
                dropPending(docPath)
                onResult(true)
            }
            .addOnFailureListener { e ->
                inFlight--
                failedCount++
                lastError = e.javaClass.simpleName
                // Firestore drops a write the server REFUSED, so the app has to keep its own copy.
                // This payload is the only place the alert counters, the CSV row count and the band
                // outcome of this session exist together: none of them is stored in the profile.
                enqueue(docPath, payload)
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
                // Deliberately NOT queued: a beat means "this student is being measured now".
                // Resending one an hour later would tell the teacher the opposite of what happened.
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
        val docPath = "$BAND_COLLECTION/${sanitize(r.studentId)}"
        val payload = buildBandPayload(r)
        client.document(docPath).set(payload)
            .addOnSuccessListener {
                bandSent++
                lastBandError = ""
                dropPending(docPath)
                onResult(true)
            }
            .addOnFailureListener { e ->
                bandFailed++
                lastBandError = e.javaClass.simpleName
                // Queued like a session record. Re-queueing replaces the older band for the same
                // student, so the resend always carries the newest range rather than a stale one.
                enqueue(docPath, payload)
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
        // Written only when samples actually exist. The mean of an empty accumulator is 0.0, NOT
        // NaN, so isFinite proves nothing here: an unguarded write would put "quality = 0" on a
        // heartbeat that measured nothing, and that reads as "this camera is broken". A missing
        // column is the honest signal; a fabricated zero is a false one.
        if (s.qualityCount > 0) {
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
        // Written only when the session actually recorded a setting. Blink numbers depend on the
        // eyelid threshold this level moves, so a record without "sensitivity" is not "medium by
        // default" - it is "measured by a build that did not say". Absence has to read as absence.
        if (s.sensitivityLevel >= 0) {
            out["sensitivity"] = s.sensitivityLevel
        }
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
        // Same rule as the heartbeat: a session that never saw a usable face has quality 0.0, and
        // writing that as a number would tell the teacher the camera was broken rather than that
        // nothing was measured. So the column is left out.
        if (s.qualityCount > 0) {
            out["quality_mean"] = round2(s.quality)
        }
        out["alerts_total"] = r.alertsTotal
        out["false_alerts"] = r.falseAlerts
        if (s.sensitivityLevel >= 0) {
            out["sensitivity"] = s.sensitivityLevel
        }
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

    /**
     * Put one refused payload into the send-again queue. A record already waiting for the SAME
     * document is replaced, not duplicated: the newest band for a student is the one worth sending,
     * and two copies of one session would only overwrite each other anyway.
     */
    private fun enqueue(docPath: String, payload: HashMap<String, Any>) {
        val fields = ArrayList<Pair<String, Any>>(payload.size)
        for (entry in payload.entries) fields.add(entry.key to entry.value)
        val record = PendingUpload(docPath, fields)
        synchronized(pendingLock) {
            val at = indexOfLocked(docPath)
            if (at >= 0) pending[at] = record else pending.add(record)
            var dropped = 0
            while (pending.size > MAX_PENDING) {
                pending.removeAt(0)
                dropped++
            }
            if (dropped > 0) pendingDropped += dropped
            pendingCount = pending.size
        }
        writeQueue()
    }

    /** Firestore accepted this document, so it must not be sent a second time. */
    private fun dropPending(docPath: String) {
        val removed = synchronized(pendingLock) {
            val at = indexOfLocked(docPath)
            if (at < 0) false else {
                pending.removeAt(at)
                pendingCount = pending.size
                true
            }
        }
        if (removed) writeQueue()
    }

    private fun indexOfLocked(docPath: String): Int {
        for (i in pending.indices) {
            if (pending[i].docPath == docPath) return i
        }
        return -1
    }

    private fun writeQueue() {
        val ctx = storage ?: return
        val text = synchronized(pendingLock) { PendingUploadStore.encode(pending) }
        try {
            val f = File(ctx.filesDir, PENDING_FILE)
            // tmp + rename, exactly like HabitStore.save: writeText truncates the existing file
            // first, so a process killed halfway through would destroy the WHOLE queue instead of
            // only the last record.
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            lastPendingError = e.javaClass.simpleName
        }
    }

    /**
     * Reads the queue file back. Records that cannot be parsed are dropped by [PendingUploadStore.decode],
     * so the ones that survive are written back out at once: leaving the corrupt text in place would
     * make every later start re-read and re-lose the same records.
     */
    private fun loadPending() {
        val ctx = storage ?: return
        val damaged = synchronized(pendingLock) {
            if (pendingLoaded) return
            pendingLoaded = true
            var bad = false
            try {
                val f = File(ctx.filesDir, PENDING_FILE)
                if (f.exists()) {
                    val text = f.readText()
                    val restored = PendingUploadStore.decode(text)
                    // Lines present in the file but not in the queue = unparseable records.
                    val lines = text.count { it == '\n' }
                    bad = lines > restored.size
                    if (restored.isNotEmpty()) pending = ArrayList(restored)
                }
            } catch (e: Exception) {
                lastPendingError = e.javaClass.simpleName
            }
            pendingCount = pending.size
            bad
        }
        if (damaged) writeQueue()
    }

    /**
     * Send everything the queue holds. [onDone] receives (accepted, refused) once every document of
     * this batch has answered; it runs on the main thread, like the Firestore callbacks.
     *
     * A record leaves the queue only when the server accepts it, so a second refusal keeps the data
     * for a third try. That is the whole point of the queue: the SDK has already forgotten the write.
     */
    fun resendPending(onDone: ((Int, Int) -> Unit)?) {
        loadPending()
        val batch = synchronized(pendingLock) { ArrayList(pending) }
        val client = db
        if (batch.isEmpty() || client == null) {
            onDone?.invoke(0, batch.size)
            return
        }
        if (resending) {
            // A previous batch is still waiting for the backend (an offline Firestore write stays
            // pending until the connection returns). Say so instead of silently doing nothing: the
            // the panel would otherwise read "sending again..." forever with no answer.
            onDone?.invoke(0, batch.size)
            return
        }
        resending = true
        var answered = 0
        var accepted = 0
        var refused = 0

        // Local because the three counters above belong to this batch only.
        fun finishBatch() {
            if (answered < batch.size) return
            resending = false
            // ONE rewrite for the whole batch: removing each record with its own writeQueue() would
            // rewrite the entire file once per document, on the main thread, for 300 documents.
            writeQueue()
            onDone?.invoke(accepted, refused)
        }

        for (rec in batch) {
            val payload = HashMap<String, Any>(rec.fields.size)
            for ((key, value) in rec.fields) payload[key] = value
            try {
                client.document(rec.docPath).set(payload)
                    .addOnSuccessListener {
                        accepted++
                        markSentFromPayload(rec)
                        // Removed by IDENTITY, not by document path. If a session ended while this
                        // batch was in the air, the queue holds a NEWER payload for the same path,
                        // and dropping by path would throw that newer record away.
                        dropQueuedInstance(rec)
                        answered++
                        finishBatch()
                    }
                    .addOnFailureListener { e ->
                        refused++
                        lastPendingError = e.javaClass.simpleName
                        answered++
                        finishBatch()
                    }
            } catch (e: Exception) {
                refused++
                lastPendingError = e.javaClass.simpleName
                answered++
                finishBatch()
            }
        }
    }

    /** Removes one exact queued instance and leaves the file rewrite to the caller. */
    private fun dropQueuedInstance(rec: PendingUpload) {
        synchronized(pendingLock) {
            val at = pending.indexOf(rec)
            if (at >= 0) {
                pending.removeAt(at)
                pendingCount = pending.size
            }
        }
    }

    /** Session documents carry their own student and session id, so the queue can confirm itself. */
    private fun markSentFromPayload(rec: PendingUpload) {
        if (!rec.docPath.startsWith("$COLLECTION/")) return
        var studentId: String? = null
        var sessionId = -1L
        for ((key, value) in rec.fields) {
            if (key == "student_id") studentId = value as? String
            else if (key == "session_id") sessionId = (value as? Number)?.toLong() ?: -1L
        }
        if (studentId != null && sessionId >= 0L) markSent(studentId, sessionId)
    }

    private fun prefs(): SharedPreferences? =
        storage?.getSharedPreferences("firebase_sync", Context.MODE_PRIVATE)

    /**
     * Which session ids the cloud already holds. Needed by [resendArchive]: without it, one press of
     * the button would rewrite every session of every student on the server, and rewriting with a
     * rebuilt (leaner) payload is exactly how a good record could be damaged.
     */
    private fun loadConfirmed() {
        if (confirmedLoaded) return
        val p = prefs() ?: return
        confirmedLoaded = true
        try {
            val raw = p.getString(SENT_IDS_KEY, "") ?: ""
            synchronized(confirmedIds) {
                for (token in raw.split(';')) {
                    val cut = token.indexOf(':')
                    if (cut <= 0) continue
                    val id = token.substring(0, cut)
                    for (value in token.substring(cut + 1).split(',')) {
                        val sessionId = value.toLongOrNull() ?: continue
                        getOrCreate(id).add(sessionId)
                    }
                }
            }
        } catch (e: Exception) {
            lastPendingError = e.javaClass.simpleName
        }
    }

    private fun getOrCreate(studentId: String): MutableSet<Long> {
        val existing = confirmedIds[studentId]
        if (existing != null) return existing
        val created = HashSet<Long>()
        confirmedIds[studentId] = created
        return created
    }

    private fun markSent(studentId: String, sessionId: Long) {
        val isNew = synchronized(confirmedIds) { getOrCreate(studentId).add(sessionId) }
        if (isNew) saveConfirmed()
    }

    private fun isConfirmed(studentId: String, sessionId: Long): Boolean =
        synchronized(confirmedIds) { confirmedIds[studentId]?.contains(sessionId) == true }

    private fun saveConfirmed() {
        val p = prefs() ?: return
        val sb = StringBuilder()
        synchronized(confirmedIds) {
            for ((id, set) in confirmedIds) {
                if (set.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append(';')
                sb.append(id).append(':')
                var first = true
                for (value in set) {
                    if (!first) sb.append(',')
                    sb.append(value)
                    first = false
                }
            }
        }
        try {
            p.edit().putString(SENT_IDS_KEY, sb.toString()).apply()
        } catch (e: Exception) {
            lastPendingError = e.javaClass.simpleName
        }
    }

    /** Documents still waiting in the queue, so the archive resend does not race with them. */
    private fun queuedPaths(): Set<String> = synchronized(pendingLock) {
        val out = HashSet<String>(pending.size)
        for (rec in pending) out.add(rec.docPath)
        out
    }

    /**
     * Archived sessions the cloud does not have yet: not confirmed by a past send, and not already
     * waiting in the queue. Shared by [resendArchive] and [unconfirmedCount] so the number on the
     * button and the work the button does cannot disagree with each other.
     */
    private fun archiveTargets(prof: HabitProfile): List<SessionSummary> {
        loadConfirmed()
        loadPending()
        val queued = queuedPaths()
        val targets = ArrayList<SessionSummary>()
        for (s in prof.sessions) {
            if (isConfirmed(prof.id, s.id)) continue
            if (queued.contains("$COLLECTION/" + documentId(prof.id, s.id))) continue
            targets.add(s)
        }
        return targets
    }

    /** Shown on the send-again button: how much the app still holds locally. */
    fun unconfirmedCount(prof: HabitProfile): Int = archiveTargets(prof).size

    /**
     * Rebuild and send the sessions this device remembers but the cloud never received.
     *
     * The profile file keeps every qualifying session, so the averages can be recovered exactly.
     * What cannot be recovered: the alert counters, the CSV row count, the frame rate and the
     * end-of-session band outcome, because those were never written to the file at all. That is why
     * this path MERGES instead of overwriting (a rebuild must not erase columns a live send already
     * placed there) and why each rebuilt record says `backfilled = true`.
     *
     * It deliberately writes NO `device` and no `app_version`. Those two describe the phone that
     * measured, and the phone doing the resend may be a different one, or the same phone on a newer
     * build - merging them would rewrite a true value with a plausible lie. What did the rebuild is
     * recorded separately as `backfilled_by`.
     *
     * Also note that "already sent" is remembered in this install only (SharedPreferences): a session
     * that went up from another phone, or before the app was reinstalled, is rebuilt once more. Since
     * the rebuild never overwrites the columns the live send wrote, the cost is one extra write, not
     * damaged data.
     *
     * [onDone] receives (accepted, refused, skipped) on the main thread.
     */
    fun resendArchive(
        prof: HabitProfile,
        studentName: String,
        appVersion: String,
        onDone: ((Int, Int, Int) -> Unit)?
    ) {
        val client = db
        val targets = archiveTargets(prof)
        val skipped = prof.sessions.size - targets.size
        if (client == null || targets.isEmpty()) {
            onDone?.invoke(0, 0, skipped)
            return
        }
        // Same lock as the queue resend. Without it a second tap on the button put a whole second
        // copy of every session in the air: the deterministic document id means nothing is corrupted,
        // but the free plan counts each of those writes.
        if (resending) {
            onDone?.invoke(0, 0, skipped)
            return
        }
        resending = true
        var answered = 0
        var accepted = 0
        var refused = 0

        fun finishBatch() {
            if (answered < targets.size) return
            resending = false
            onDone?.invoke(accepted, refused, skipped)
        }

        for (s in targets) {
            val docPath = "$COLLECTION/" + documentId(prof.id, s.id)
            val payload = buildArchivePayload(prof, studentName, s, appVersion)
            try {
                client.document(docPath).set(payload, SetOptions.merge())
                    .addOnSuccessListener {
                        accepted++
                        markSent(prof.id, s.id)
                        answered++
                        finishBatch()
                    }
                    .addOnFailureListener { e ->
                        refused++
                        lastPendingError = e.javaClass.simpleName
                        answered++
                        finishBatch()
                    }
            } catch (e: Exception) {
                refused++
                lastPendingError = e.javaClass.simpleName
                answered++
                finishBatch()
            }
        }
    }

    private fun buildArchivePayload(
        prof: HabitProfile,
        studentName: String,
        s: SessionSummary,
        appVersion: String
    ): HashMap<String, Any> {
        val out = HashMap<String, Any>(32)
        out["schema"] = SCHEMA
        out["student_id"] = prof.id
        out["student_name"] = studentName
        out["session_id"] = s.id
        out["valid_ms"] = s.validMs
        out["face_ratio"] = round4(s.faceRatio)
        out["valid_ratio"] = round4(s.validRatio)
        if (s.qualityCount > 0) out["quality_mean"] = round2(s.quality)
        out["backfilled"] = true
        out["backfilled_by"] = appVersion
        // Eligibility CAN be recomputed honestly: ineligibleReason() looks at the session and at the
        // constants only, never at how many sessions the profile had at the time. The profile only
        // stores sessions that qualified when they were measured, so reason is normally null here; if
        // the constants moved since, the rebuilt record says so instead of keeping the old verdict.
        // The reason column is always written (empty when eligible) so a merge cannot leave a stale
        // reason sitting next to eligible = true.
        val reason = prof.ineligibleReason(s)
        out["eligible"] = reason == null
        out["ineligible_reason"] = reason ?: ""
        for (f in 0 until HabitFeature.COUNT) {
            val n = s.count[f]
            if (n > 0) {
                val key = FEATURE_KEYS[f]
                out["mean_$key"] = round2(s.mean[f])
                out["std_$key"] = round2(s.std[f])
                out["n_$key"] = n
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
        // The queue is printed even when it is the only thing to report: an empty cloud table plus a
        // queue of forty sessions means the data is safe at home, which is a different message from
        // "nothing was measured".
        if (pendingCount > 0 || pendingDropped > 0) {
            sb.append(" - chờ gửi lại ").append(pendingCount)
            if (lastPendingError.isNotEmpty()) sb.append(" (").append(lastPendingError).append(')')
            if (pendingDropped > 0) sb.append(", tràn hàng đợi mất ").append(pendingDropped)
        }
        return sb.toString()
    }
}
