package com.focuschange.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the send-again queue format.
 *
 * A record lost here is the failure mode this app cannot accept: the queue is the only copy of a
 * session the server refused, and it is read back days later, after the recording week is over.
 * So the tests check the exact things that would silently corrupt a rebuilt Firestore document -
 * types, Vietnamese names, characters that could break a line, and a file torn by a kill.
 */
class PendingUploadStoreTest {

    private fun record() = PendingUpload(
        "sessions/hs1-1700000000000",
        listOf(
            "schema" to 1,
            "student_id" to "hs1",
            "student_name" to "Trần Thị Hòa",
            "session_id" to 1700000000000L,
            "valid_ms" to 180000L,
            "face_ratio" to 0.9812,
            "avg_fps" to 8.5f,
            "eligible" to true,
            "ineligible_reason" to "",
            "mean_sei" to 61.25
        )
    )

    @Test
    fun everyValueComesBackWithItsOwnType() {
        val back = PendingUploadStore.decode(PendingUploadStore.encode(listOf(record())))
        assertEquals(1, back.size)
        val fields = back[0].fields.toMap()
        assertEquals("sessions/hs1-1700000000000", back[0].docPath)
        // An Int that returns as a Double would be written to Firestore as 1.0, and a schema or
        // counter column read as a decimal is a record the teacher cannot filter on.
        assertEquals(1, fields["schema"])
        assertEquals(1700000000000L, fields["session_id"])
        assertEquals(180000L, fields["valid_ms"])
        assertEquals(0.9812, fields["face_ratio"])
        assertEquals(8.5f, fields["avg_fps"])
        assertEquals(true, fields["eligible"])
        assertEquals("Trần Thị Hòa", fields["student_name"])
        assertEquals(61.25, fields["mean_sei"])
    }

    @Test
    fun fieldOrderAndCountSurvive() {
        val text = PendingUploadStore.encode(listOf(record()))
        val fields = PendingUploadStore.decode(text)[0].fields
        assertEquals(10, fields.size)
        assertEquals("schema", fields[0].first)
        assertEquals("mean_sei", fields[9].first)
    }

    @Test
    fun anEmptyValueIsStillAnEmptyValue() {
        val fields = PendingUploadStore.decode(PendingUploadStore.encode(listOf(record())))[0].fields.toMap()
        assertEquals("", fields["ineligible_reason"])
    }

    @Test
    fun separatorsAndNewlinesInsideAValueDoNotSplitTheRecord() {
        val tricky = PendingUpload(
            "sessions/hs 2-1",
            listOf(
                "student_name" to "A\u001FB\u001Ec\u001Dd",
                "note" to "hai\nba",
                "back" to "C:\\Users\\test"
            )
        )
        val text = PendingUploadStore.encode(listOf(tricky))
        assertEquals(1, text.count { it == '\n' })
        val back = PendingUploadStore.decode(text)
        assertEquals(1, back.size)
        assertEquals("sessions/hs 2-1", back[0].docPath)
        val fields = back[0].fields.toMap()
        assertEquals("A\u001FB\u001Ec\u001Dd", fields["student_name"])
        assertEquals("hai\nba", fields["note"])
        assertEquals("C:\\Users\\test", fields["back"])
    }

    @Test
    fun emptyQueueIsAnEmptyFileAndBack() {
        assertEquals("", PendingUploadStore.encode(emptyList()))
        assertTrue(PendingUploadStore.decode("").isEmpty())
    }

    @Test
    fun severalRecordsKeepTheirOwnPaths() {
        val a = PendingUpload("sessions/hs1-1", listOf("v" to 1))
        val b = PendingUpload("bands/hs1", listOf("v" to 2))
        val back = PendingUploadStore.decode(PendingUploadStore.encode(listOf(a, b)))
        assertEquals(listOf("sessions/hs1-1", "bands/hs1"), back.map { it.docPath })
        assertEquals(1, back[0].fields[0].second)
        assertEquals(2, back[1].fields[0].second)
    }

    @Test
    fun aTornOrGarbledLineIsSkippedNotThrown() {
        val good = PendingUploadStore.encode(listOf(record()))
        // Case one: the process was killed in the middle of the last record.
        val torn = good + "sessions/hs1-2\u001Fstudent_id\u001E"
        val afterTorn = PendingUploadStore.decode(torn)
        assertEquals(1, afterTorn.size)
        assertEquals("sessions/hs1-1700000000000", afterTorn[0].docPath)

        // Case two: a field with no key separator, and a value whose type letter is gone.
        val junk = "sessions/x\u001Fbrokenfield\nsessions/y\u001Fk\u001E???\n"
        assertTrue(PendingUploadStore.decode(junk).isEmpty())

        // Case three: bytes that are not a record at all must not crash the read.
        assertTrue(PendingUploadStore.decode("\u001E\u001F\nrandom text\n").isEmpty())
    }

    @Test
    fun aWrongTypedNumberDoesNotBecomeZeroSilently() {
        // "i" with a body that is not an integer makes the whole record unparseable, and decode
        // drops it. Returning 0 here would put a false measurement into Firestore instead.
        val text = PendingUploadStore.encode(listOf(PendingUpload("sessions/z", listOf("n" to 5))))
        val broken = text.trim().replace("i5", "i5x") + "\n"
        assertTrue(PendingUploadStore.decode(broken).isEmpty())
        assertEquals(5, PendingUploadStore.decode(text)[0].fields[0].second)
    }
}
