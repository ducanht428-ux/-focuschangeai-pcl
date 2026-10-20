
package com.focuschange.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionStorage(context: Context) {

    private val folder = File(context.filesDir, "eye_sessions")

    init {
        if (!folder.exists()) {
            folder.mkdirs()
        }
    }

    /**
     * Lưu dữ liệu một phiên theo dõi.
     * Nếu cùng sessionId thì cập nhật file cũ, không tạo bản trùng.
     */
    @Synchronized
    fun saveSession(
        sessionId: String,
        data: JSONObject
    ): Boolean {
        return try {
            val safeId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(folder, "$safeId.json")

            data.put("sessionId", safeId)
            data.put("savedAt", System.currentTimeMillis())

            val tempFile = File(folder, "$safeId.tmp")
            tempFile.writeText(data.toString(2), Charsets.UTF_8)

            if (file.exists()) file.delete()

            if (!tempFile.renameTo(file)) {
                file.writeText(data.toString(2), Charsets.UTF_8)
                tempFile.delete()
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Đọc lại toàn bộ các phiên đã lưu.
     */
    @Synchronized
    fun getAllSessions(): List<JSONObject> {
        return try {
            folder.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?.mapNotNull { file ->
                    try {
                        JSONObject(file.readText(Charsets.UTF_8))
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Tổng số phiên đã lưu.
     */
    fun getSessionCount(): Int = getAllSessions().size
}
