package com.focuschange.ai

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Research data logger: one CSV row per frame, one file per session.
 *
 * Only numbers are written, never images or video, so the file carries no identifying info
 * and stays inside the app private directory (no other app can read it).
 *
 * The camera thread only concatenates strings and pushes them onto the queue; another thread
 * touches the flash. The queue is bounded: when writing cannot keep up, samples are dropped and
 * counted instead of stuttering the preview - a few lost rows are still analysable, a freeze is not.
 */
internal class DataLogger {

    private companion object {
        const val DIR_NAME = "research"

        /** Session files are named frames_<tag>.csv; an empty tag gives the old frames.csv. */
        const val FRAMES_PREFIX = "frames_"
        const val ALERTS_PREFIX = "false_alerts_"
        const val META_PREFIX = "meta_"
        const val CSV_EXT = ".csv"
        const val TAG_CHARS = "0123456789-_"
        const val FLUSH_EVERY = 30
        const val MAX_QUEUE = 900
        const val TAG = "DataLogger"
    }

    /**
     * 37 measurement columns + 8 two-sided band columns + 4 situation columns = 49; the order must
     * match the row builder in MainActivity.
     *
     * This list is public (instead of only the header string) so places that must write an "empty"
     * row can look columns up BY NAME. Counting positions by hand means one column inserted in the
     * middle shifts every later value, and the audit file becomes wrong without anyone noticing.
     *
     * The band columns carry codes, not measurements: -2 = "this frame was not scored", -1 = "no band
     * learned for that feature yet", 0/1/2 = below/in/above. Writing 0 instead of -2 or -1 would turn
     * "not measured" into "inside the band", i.e. the opposite claim.
     *
     * Nothing is ever inserted before the band block: new columns are appended AFTER it, so the 45
     * columns of every file written so far keep their exact position and open with the same script.
     */
    private val measuredColumns: List<String> = listOf(
        "t_ms", "face", "ear1", "ear2", "openness", "sigma", "eye_valid",
        "base_ear1", "base_ear2", "close_thr1", "close_thr2",
        "yaw_deg", "pitch_deg", "roll_deg", "gaze_h", "gaze_v", "mar", "bfr",
        "dist_raw_cm", "dist_cm", "dist_scale", "focal_px",
        "perclos_p60", "perclos_p70", "perclos_p80",
        "bpm", "avg_blink_ms", "inattention_pct",
        "sei", "fatigue", "quality", "alert_code", "chronic",
        "session_s", "fps", "proc_ms", "luma"
    )

    val columnNames: List<String> =
        measuredColumns +
            HabitFeature.BAND_FEATURES.map { "band_" + HabitFeature.KEYS[it] } +
            // situation = posture code (0 forward, 1 bent down, 2 tilted, 3 turned aside, 4 nodding,
            // 5 not classifiable); alert_held = 1 when a threshold fired but the situation layer delayed
            // it. alert_code keeps the threshold's own answer on those frames, which is what makes the
            // delay countable instead of invisible.
            // closed_ms and pose_std_deg are the two inputs the layer needed to decide them. Written
            // rather than re-derived offline so a recorded session can be replayed to the same answer:
            // a replay that has to guess the closure timer is a second implementation of it, free to
            // drift from the real one without anything failing.
            listOf("situation", "alert_held", "closed_ms", "pose_std_deg")

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
     * Rows dropped because their column count did not match the header.
     *
     * Losing one row is better than writing a row with shifted columns: a short row pushes every later
     * value into the wrong column, and nobody opening the file on a computer can see the shift - they
     * only see strange numbers. Counted here so this session's file can be proven clean.
     */
    @Volatile var malformedRows = 0L
        private set

    private val queue = ConcurrentLinkedQueue<String>()
    private val queued = AtomicInteger(0)
    private var thread: Thread? = null

    /**
     * Session suffix used for every file name, or null when no session has run since the app started.
     *
     * One file per session instead of one shared frames.csv: the shared file was truncated inside
     * start(), so a week of collected data only ever kept the LAST session's frame detail. The
     * change-detection comparison needs frames from many sessions, and the name must tie back to the
     * cloud record, so the suffix is HabitSession.id - the same number used in sessions/<id>-<session>.
     *
     * Nothing here deletes old session files. The phone holds the only copy of this data, so freeing
     * space stays a decision a person makes after copying the folder to a computer; [dirUsage] only
     * reports how big it has become.
     */
    @Volatile private var sessionSuffix: String? = null

    /** Returns the file path when writing worked, null when it did not. */
    fun start(context: Context, meta: String, sessionId: Long): String? {
        if (isActive) return null
        val dir = dirFor(context) ?: return null

        val suffix = sanitizeSessionId(sessionId)
        sessionSuffix = suffix

        // Clean up leftovers from the previous write BEFORE opening a new file.
        // stop() only waits 2 seconds for the old pump; if the queue still holds rows (slow flash, or
        // the pump died of an IOException while writing), rows from the old session would get
        // written into the new session's file - t_ms jumps back into the past and nobody knows which
        // row belongs to which session.
        // Bump the generation BEFORE waiting: the old pump sees its number is no longer current and
        // stops at once instead of waiting out the 2 seconds, and nothing writes into the old file.
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
            // discard leftover rows of the old session
        }
        queued.set(0)

        return try {
            val file = File(dir, FRAMES_PREFIX + suffix + CSV_EXT)
            val w = BufferedWriter(FileWriter(file, false))
            w.write(header)
            w.newLine()
            w.flush()
            File(dir, META_PREFIX + suffix + ".txt").writeText(meta)

            // Each session gets its own alert-label file, opened empty. Labels from an earlier session
            // must not land here: they would attach a "this alert was wrong" verdict to frames that
            // produced that alert nowhere in this file.
            try {
                BufferedWriter(FileWriter(File(dir, ALERTS_PREFIX + suffix + CSV_EXT), false)).use {
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

    private fun sanitizeSessionId(sessionId: Long): String {
        val raw = sessionId.toString()
        val clean = raw.filter { TAG_CHARS.contains(it) }
        // An id that survives to nothing must not silently become "the legacy file": that name is
        // reused by the reader as "no session has run", so two different sessions could share one file.
        return clean.ifEmpty { "s$sessionId" }
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

    /** Written straight away when the false-alert button is pressed - rare, so no queue needed. */
    fun appendFalseAlert(context: Context, line: String): Boolean {
        val dir = dirFor(context) ?: return false
        return try {
            val f = File(dir, ALERTS_PREFIX + currentSuffix(dir) + CSV_EXT)
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
     * Write generation, incremented by every start(). pump() uses it to know whether it is still the
     * current writer or a newer start() has already overtaken it.
     *
     * Without this numbering, an old pump (stuck in flush() for more than 2 seconds so stop() gave
     * up) would keep running, write old rows into the new file, then set isActive = false on exit
     * - muting the writing of the new session that is running in parallel.
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
            // Only clear the writing flag if this is still our own generation. If a newer start() squeezed in,
            // the old pump exiting here and setting isActive = false would silently stop the writing
            // of the new session - the screen still shows the recording state while the file gains no rows.
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

    /**
     * Suffix of the session whose files the screen should show.
     *
     * After an app restart [sessionSuffix] is empty, so the newest frames_*.csv on disk is picked up
     * instead. Without that, opening the raw-data screen the next day would report "no file yet" while
     * yesterday's data is still sitting in the folder, and the reader concludes the recording was lost.
     * An empty result is the old shared frames.csv name, used only when the folder holds nothing newer.
     */
    private fun currentSuffix(dir: File): String {
        sessionSuffix?.let { return it }
        val sessions = dir.listFiles { f ->
            f.isFile && f.name.startsWith(FRAMES_PREFIX) && f.name.endsWith(CSV_EXT)
        } ?: return ""
        val newest = sessions.maxByOrNull { it.lastModified() } ?: return ""
        val suffix = newest.name.removePrefix(FRAMES_PREFIX).removeSuffix(CSV_EXT)
        sessionSuffix = suffix
        return suffix
    }

    /** Data file currently (or last) written, so the raw-data screen can read it back. */
    fun framesFile(context: Context): File? =
        dirFor(context)?.let { File(it, FRAMES_PREFIX + currentSuffix(it) + CSV_EXT) }

    fun alertsFile(context: Context): File? =
        dirFor(context)?.let { File(it, ALERTS_PREFIX + currentSuffix(it) + CSV_EXT) }

    /**
     * Number of per-session frame files kept and their total size in bytes, including the alert-label
     * and meta files.
     *
     * Reported instead of enforced: this folder is the only copy of the week's measurements, so growth
     * has to be visible on screen rather than fixed by deleting research data in the background.
     */
    fun dirUsage(context: Context): Pair<Int, Long> {
        val dir = dirFor(context) ?: return 0 to 0L
        val files = dir.listFiles() ?: return 0 to 0L
        var sessions = 0
        var bytes = 0L
        for (f in files) {
            if (!f.isFile) continue
            bytes += f.length()
            if (f.name.startsWith(FRAMES_PREFIX) && f.name.endsWith(CSV_EXT)) sessions++
        }
        return sessions to bytes
    }

    /**
     * Every file kept in the research folder, newest first - or null when the folder exists but
     * Android refuses to list it.
     *
     * [framesFile] only ever points at the latest session, but the week's measurements are spread over
     * one file per session, so anything that hands the data over has to take the whole list.
     *
     * The two empty outcomes are kept apart on purpose: saying "no files" when the folder is really
     * unreadable would tell the reader the week's measurements are gone.
     *
     * The meta_ files travel with the CSVs because each one records the thresholds and the baseline
     * that session ran on. Rows without that file cannot be replayed: the same angles score different
     * alerts under different baselines.
     */
    fun researchFiles(context: Context): List<File>? {
        val dir = dirFor(context) ?: return null
        val files = dir.listFiles() ?: return null
        return files.filter {
            it.isFile &&
                (it.name.endsWith(CSV_EXT) || it.name.startsWith(META_PREFIX))
        }.sortedByDescending { it.lastModified() }
    }

    /**
     * Rows handed to the writer but not yet flushed to flash: still in the queue plus up to
     * [FLUSH_EVERY] rows sitting in the buffer.
     *
     * Only needed so the export can say "the newest 30 rows are not in the file yet" instead of
     * letting a mid-session ZIP look like a complete record of the session.
     */
    fun pendingLines(): Int = queued.get()

    /**
     * DATA rows already on disk (header excluded), unlike [rows] which does not count what is
     * still sitting in the queue.
     *
     * The header is excluded because these two numbers are printed side by side to compare "counted
     * in memory - written on disk". Counting the header would make a perfectly normal session always
     * differ by exactly 1, and the reader would think the file was incomplete.
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