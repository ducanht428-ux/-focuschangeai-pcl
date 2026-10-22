package com.focuschange.ai

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Quản lý danh sách học sinh (SharedPreferences) và lưu hồ sơ từng em ra file JSON
 * trong bộ nhớ riêng của ứng dụng. Không gửi dữ liệu ra ngoài.
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
                // Lưu nguyên văn bản hỏng trước khi tạo danh sách mới: persist() bên dưới sẽ đè
                // lên key này, và nếu không giữ lại thì tên của mọi em từng nhập mất luôn, chỉ
                // vì một lần SharedPreferences bị đọc sai.
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

        // Đọc một lần rồi giữ nguyên văn bản trong bộ nhớ. Lúc cần backup thì ghi lại văn bản đó,
        // không phụ thuộc renameTo() - rename có thể thất bại (khác thiết bị, file đang bị mở)
        // và khi đó file gốc vẫn còn nguyên, save() lần sau sẽ đè lên và mất sạch dữ liệu.
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
        } catch (e: Exception) {
            parseError = e
            profile.sessions.clear()
        }

        val err = parseError
        if (err != null || dropped > 0) {
            // Một phần hồ sơ không đọc được. Phải nhân bản nguyên văn ra .corrupt TRƯỚC khi
            // quay lại: hồ sơ một phần vẫn được coi là "đã nạp" và autosave sẽ ghi đè file gốc,
            // nên nếu không có bản sao thì những phiên bị bỏ qua biến mất vĩnh viễn.
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
