package com.focuschange.ai

/** One Firestore document waiting to be sent: path plus typed payload fields. */
internal class PendingUpload(val docPath: String, val fields: List<Pair<String, Any>>)

/**
 * Text format for the send-again queue the app keeps in its own storage.
 *
 * Firestore's own offline queue only covers "no network". When the server REFUSES a write - the
 * database was never created, or a rule denies it - the SDK drops that mutation for good and the
 * session never reaches the cloud, while the recording week is already under way. So the app keeps
 * its own copy and offers it again later.
 *
 * Hand-rolled instead of JSON because org.json is stubbed out in host unit tests, and a lost record
 * is the one failure this file may not have. One record per line, fields split by [FIELD_SEP], key
 * split from value by [KV_SEP], and every value carries a type letter so an Int never comes back as
 * a Double. An unparseable line is skipped by [decode], never thrown: the process can be killed in
 * the middle of a write.
 */
internal object PendingUploadStore {

    private const val FIELD_SEP = "\u001F"
    private const val KV_SEP = "\u001E"

    /** Serializes the whole queue; an empty queue becomes an empty string. */
    fun encode(records: List<PendingUpload>): String {
        val sb = StringBuilder(records.size * 96)
        for (r in records) {
            sb.append(escape(r.docPath))
            for ((key, value) in r.fields) {
                sb.append(FIELD_SEP).append(escape(key)).append(KV_SEP).append(encodeValue(value))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Reads the queue back. Lines that cannot be parsed are dropped silently: a torn last line from
     * a killed process must not take the older, complete records with it.
     */
    fun decode(text: String): List<PendingUpload> {
        val out = ArrayList<PendingUpload>(16)
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val record = parseLine(line) ?: continue
            out.add(record)
        }
        return out
    }

    private fun parseLine(line: String): PendingUpload? {
        val parts = line.split(FIELD_SEP)
        val docPath = unescape(parts[0])
        if (docPath.isEmpty()) return null
        val fields = ArrayList<Pair<String, Any>>(parts.size - 1)
        for (index in 1 until parts.size) {
            val part = parts[index]
            val cut = part.indexOf(KV_SEP)
            if (cut <= 0) return null
            val key = unescape(part.substring(0, cut))
            val value = decodeValue(part.substring(cut + 1)) ?: return null
            if (key.isEmpty()) return null
            fields.add(key to value)
        }
        // A path with no columns is not a record anyone can write, and reading one back as an empty
        // document would create an empty row in the cloud table.
        if (fields.isEmpty()) return null
        return PendingUpload(docPath, fields)
    }

    /** A type letter first so 5 never decodes as 5.0, and so null is impossible rather than implied. */
    private fun encodeValue(value: Any): String = when (value) {
        is Int -> "i$value"
        is Long -> "l$value"
        is Double -> "d$value"
        is Float -> "f$value"
        is Boolean -> if (value) "b1" else "b0"
        else -> "s" + escape(value.toString())
    }

    private fun decodeValue(token: String): Any? {
        if (token.isEmpty()) return null
        val body = token.substring(1)
        return when (token[0]) {
            'i' -> body.toIntOrNull()
            'l' -> body.toLongOrNull()
            'd' -> body.toDoubleOrNull()
            'f' -> body.toFloatOrNull()
            'b' -> if (body == "1") true else if (body == "0") false else null
            's' -> unescape(body)
            else -> null
        }
    }

    /** Backslash and the two separator characters are the only things that can break a line. */
    private fun escape(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u001F' -> sb.append("\\u")
                '\u001E' -> sb.append("\\v")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescape(text: String): String {
        val sb = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c != '\\' || index + 1 >= text.length) {
                sb.append(c)
                index++
                continue
            }
            when (text[index + 1]) {
                '\\' -> sb.append('\\')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                'u' -> sb.append('\u001F')
                'v' -> sb.append('\u001E')
                else -> {
                    sb.append(c).append(text[index + 1])
                    index += 2
                    continue
                }
            }
            index += 2
        }
        return sb.toString()
    }
}