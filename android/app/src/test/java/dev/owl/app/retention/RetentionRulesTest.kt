package dev.owl.app.retention

import dev.owl.app.data.db.SegmentEntity
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.SegmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionRulesTest {
    private val day = 24 * 3_600_000L
    private val t0 = 1_793_440_000_000L

    private fun seg(state: SegmentState = SegmentState.DONE, preserve: Boolean = false, start: Long? = t0) = SegmentEntity(
        deviceAddress = "a", segmentId = 1, type = 1, sizeBytes = 100, startUnixMs = start, sortTimeMs = t0,
        filePath = "/x", state = state, preserveAudio = preserve, receivedAtMs = t0 + day,
    )

    @Test
    fun daysCountFromRecordingTime() {
        assertEquals(t0 + 30 * day, RetentionRules.deleteAt(RetentionPolicy.DAYS_30, seg()))
        assertFalse(RetentionRules.shouldDelete(RetentionPolicy.DAYS_30, seg(), t0 + 29 * day))
        assertTrue(RetentionRules.shouldDelete(RetentionPolicy.DAYS_30, seg(), t0 + 30 * day))
    }

    @Test
    fun unknownTimeCountsFromReceipt() {
        assertEquals(t0 + day + 7 * day, RetentionRules.deleteAt(RetentionPolicy.DAYS_7, seg(start = null)))
    }

    @Test
    fun preservedAndNeverAreKept() {
        assertNull(RetentionRules.deleteAt(RetentionPolicy.DAYS_7, seg(preserve = true)))
        assertNull(RetentionRules.deleteAt(RetentionPolicy.NEVER, seg()))
        assertFalse(RetentionRules.shouldDelete(RetentionPolicy.DAYS_7, seg(preserve = true), t0 + 365 * day))
    }

    @Test
    fun afterTranscriptionWaitsForText() {
        assertFalse(RetentionRules.shouldDelete(RetentionPolicy.AFTER_TRANSCRIPTION, seg(SegmentState.RECEIVED), t0 + 2 * day))
        assertFalse(RetentionRules.shouldDelete(RetentionPolicy.AFTER_TRANSCRIPTION, seg(SegmentState.TRANSCRIBING), t0 + 2 * day))
        assertTrue(RetentionRules.shouldDelete(RetentionPolicy.AFTER_TRANSCRIPTION, seg(SegmentState.DONE), t0 + 2 * day))
    }

    @Test
    fun receivingIsNeverDeleted() {
        assertFalse(RetentionRules.shouldDelete(RetentionPolicy.DAYS_7, seg(SegmentState.RECEIVING), t0 + 100 * day))
    }
}
