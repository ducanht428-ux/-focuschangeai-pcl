package com.focuschange.ai

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================
// CONVERT DATA TO TEXT / JSON - this part only runs on Android
// (split out of HabitModel.kt so the habit model stays pure Kotlin)
// =====================================================================

internal fun FloatArray.toJsonArray(): JSONArray {
    val a = JSONArray()
    for (v in this) a.put(v.toDouble())
    return a
}

internal fun SessionSummary.toJson(): JSONObject {
    val o = JSONObject()
    o.put("id", id)
    o.put("validMs", validMs)
    o.put("fr", faceRatio.toDouble())
    o.put("vr", validRatio.toDouble())
    o.put("q", quality.toDouble())
    o.put("m", mean.toJsonArray())
    o.put("s", std.toJsonArray())
    val n = JSONArray()
    for (c in count) n.put(c)
    o.put("n", n)
    o.put("qn", qualityCount)
    o.put("bi", bandIn)
    o.put("bb", bandBelow)
    o.put("ba", bandAbove)
    o.put("sl", sensitivityLevel)
    return o
}

internal fun SessionSummary.Companion.fromJson(o: JSONObject): SessionSummary? {
    return try {
        val c = HabitFeature.COUNT
        val m = o.getJSONArray("m")
        val s = o.getJSONArray("s")
        val n = o.getJSONArray("n")
        if (m.length() != c || s.length() != c || n.length() != c) return null

        SessionSummary(
            id = o.getLong("id"),
            validMs = o.getLong("validMs"),
            faceRatio = o.getDouble("fr").toFloat(),
            validRatio = o.getDouble("vr").toFloat(),
            quality = o.getDouble("q").toFloat(),
            mean = FloatArray(c) { m.getDouble(it).toFloat() },
            std = FloatArray(c) { s.getDouble(it).toFloat() },
            count = IntArray(c) { n.getInt(it) },
            // Files written before qn existed have no count stored. A session can only be IN the
            // profile if it passed the quality bar, so a stored positive mean is proof enough that
            // samples existed; guessing 0 here would blank the quality column for every old profile.
            qualityCount = o.optInt("qn", if (o.optDouble("q", 0.0) > 0.0) 1 else 0),
            // Old files have no band counts. 0 samples is what actually happened - the session was
            // never scored against a band - and the day table reads that as "no data", never "0%".
            bandIn = o.optInt("bi", 0),
            bandBelow = o.optInt("bb", 0),
            bandAbove = o.optInt("ba", 0),
            // A file with no "sl" was written by a build that did not record the setting. -1 keeps
            // that as "unknown"; reading it as "medium" would invent a measurement condition.
            sensitivityLevel = o.optInt("sl", -1)
        )
    } catch (e: Exception) {
        Log.w("HabitProfile", "Bỏ qua phiên lỗi định dạng", e)
        null
    }
}

/** History of the most recent sessions with the SEI trend (used for the screen and reports). */
internal fun HabitProfile.historyText(maxLines: Int): String {
    if (sessions.isEmpty()) return "Chưa có phiên nào đạt chuẩn."

    val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)
    val sb = StringBuilder()
    val from = max(0, sessions.size - maxLines)
    for (i in sessions.size - 1 downTo from) {
        val s = sessions[i]
        sb.append(fmt.format(Date(s.id)))
        sb.append(" - ").append(s.validMs / 60000L).append(" phút hợp lệ")
        if (s.count[HabitFeature.SEI] > 0) {
            sb.append(", SEI TB ").append(s.mean[HabitFeature.SEI].roundToInt())
        }
        if (s.count[HabitFeature.BPM] > 0) {
            sb.append(", chớp ").append(s.mean[HabitFeature.BPM].roundToInt()).append("/phút")
        }
        if (s.sensitivityLevel >= 0) {
            sb.append(", độ nhạy ").append(sensitivityName(s.sensitivityLevel))
        }
        sb.append('\n')
    }

    if (sessions.size >= 4) {
        val n = sessions.size
        val recent = avgSei(n - 3, n)
        val previous = avgSei(n - 6, n - 3)
        if (recent != null && previous != null && recent.second >= 2 && previous.second >= 2) {
            val diff = recent.first - previous.first
            val word = when {
                diff >= 5f -> "đang tăng"
                diff <= -5f -> "đang giảm"
                else -> "ổn định"
            }
            sb.append(
                String.format(
                    Locale.US,
                    "Xu hướng SEI: %s (%+.0f điểm: %d phiên gần nhất so với %d phiên trước đó).\n",
                    word, diff, recent.second, previous.second
                )
            )
        } else {
            // While too few sessions exist, no claim about a "trend" is allowed at all.
            sb.append("Chưa đủ số phiên đạt chuẩn để kết luận xu hướng SEI.\n")
        }
    }
    return sb.toString().trimEnd()
}
