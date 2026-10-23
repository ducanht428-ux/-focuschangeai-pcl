package com.focuschange.ai

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages the student list (SharedPreferences) and saves each student's profile as
 * a JSON file inside the app's private storage. No data is sent anywhere outside.
 */
internal class HabitStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("focus_habit_prefs", Context.MODE_PRIVATE)

    private companion object {
        const val TAG = "HabitStore"
        const val KEY_STUDENTS = "students"
        const val KEY_STUDENTS_CORRUPT = "students_corrupt_backup"
        const val KEY_CURRENT = "current_student"
    }

    fun students(): List<HabitStudent> {
        val out = ArrayList<HabitStudent>()
        val raw = prefs.getString(KEY_STUDENTS, null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(HabitStudent(o.getString("id"), o.getString("name")))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Danh sách học sinh lỗi, tạo lại", e)
                out.clear()
                // Save the corrupt text verbatim before building a new list: persist() below will
                // overwrite this key, and if no copy is kept then the name of every student ever
                // entered is lost, just because SharedPreferences was misread one time.
                try {
                    prefs.edit().putString(KEY_STUDENTS_CORRUPT, raw).apply()
                } catch (e2: Exception) {
                    Log.w(TAG, "Không giữ được bản sao danh sách học sinh hỏng", e2)
                }
            }
        }
        if (out.isEmpty()) {
            out.add(HabitStudent("hs1", "Học sinh 1"))
            persist(out)
        }
        return out
    }

    private fun persist(list: List<HabitStudent>) {
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("name", s.name)
            arr.put(o)
        }
        prefs.edit().putString(KEY_STUDENTS, arr.toString()).apply()
    }

    fun currentId(): String {
        val list = students()
        val cur = prefs.getString(KEY_CURRENT, null)
        return list.firstOrNull { it.id == cur }?.id ?: list[0].id
    }

    fun setCurrent(id: String) {
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    fun nameOf(id: String): String = students().firstOrNull { it.id == id }?.name ?: id

    fun addStudent(name: String): HabitStudent {
        val list = students().toMutableList()
        val clean = name.trim().take(24).ifEmpty { "Học sinh ${list.size + 1}" }
        val st = HabitStudent("hs" + System.currentTimeMillis(), clean)
        list.add(st)
        persist(list)
        return st
    }

    private fun file(id: String): File = File(context.filesDir, "habit_$id.json")

    fun load(id: String): HabitProfile {
        val profile = HabitProfile(id, nameOf(id))
        val f = file(id)
        if (!f.exists()) return profile

        // Read once and keep the exact text in memory. When a backup is needed, rewrite that text;
        // do not depend on renameTo() - rename can fail (different device, file currently open),
        // and then the original file stays intact while the next save() overwrites it and all data is lost.
        val raw = try {
            f.readText()
        } catch (e: Exception) {
            Log.w(TAG, "Không đọc được hồ sơ $id", e)
            return profile
        }

        var parseError: Exception? = null
        var dropped = 0
        try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = try {
                    SessionSummary.fromJson(arr.getJSONObject(i))
                } catch (e: Exception) {
                    null
                }
                if (s == null) dropped++ else profile.sessions.add(s)
            }
            // The calibration block is read in its own try: a profile that was never calibrated
            // (or an old-generation file with no "cal" entry) only means uncalibrated, it must not
            // eat the sessions already learned. In the outer try, a single NaN runs sessions.clear().
            try {
                val cal = root.optJSONObject("cal")
                if (cal != null) {
                    profile.distanceScale = cal.optDouble("distance_scale", Double.NaN).toFloat()
                    profile.calRawDistanceCm = cal.optDouble("raw_distance_cm", Double.NaN).toFloat()
                    profile.calibratedAtMs = cal.optLong("calibrated_at_ms", 0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Hồ sơ $id không đọc được khối hiệu chuẩn", e)
            }
        } catch (e: Exception) {
            parseError = e
            profile.sessions.clear()
        }

        val err = parseError
        if (err != null || dropped > 0) {
            // Part of the profile could not be read. The exact text must be cloned to .corrupt
            // BEFORE returning: a partial profile still counts as "loaded" and autosave would
            // overwrite the original file, so without a copy the dropped sessions vanish forever.
            val note = if (err != null) "toàn bộ file hỏng" else "$dropped mục trong file hỏng"
            val backedUp = try {
                File(f.parentFile, f.name + ".corrupt").writeText(raw)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Hồ sơ $id ($note) và không ghi được bản sao để phục hồi", e)
                false
            }
            if (backedUp) {
                Log.w(TAG, "Hồ sơ $id: $note, đã giữ bản gốc tại ${f.name}.corrupt để phục hồi", err)
            }
        }
        return profile
    }

    fun save(p: HabitProfile) {
        try {
            val root = JSONObject()
            root.put("id", p.id)
            root.put("name", p.name)
            val arr = JSONArray()
            for (s in p.sessions) arr.put(s.toJson())
            root.put("sessions", arr)

            if (p.hasCalibration()) {
                val cal = JSONObject()
                // JSONObject.put(String, Double) throws an exception on NaN, so every value must
                // be checked on its own instead of writing all three and letting the outer try catch it.
                cal.put("distance_scale", p.distanceScale.toDouble())
                if (p.calRawDistanceCm.isFinite()) {
                    cal.put("raw_distance_cm", p.calRawDistanceCm.toDouble())
                }
                if (p.calibratedAtMs > 0L) cal.put("calibrated_at_ms", p.calibratedAtMs)
                root.put("cal", cal)
            }

            val f = file(p.id)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không lưu được hồ sơ ${p.id}", e)
        }
    }

    fun clear(id: String) {
        try {
            file(id).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Không xóa được hồ sơ $id", e)
        }
    }
}
