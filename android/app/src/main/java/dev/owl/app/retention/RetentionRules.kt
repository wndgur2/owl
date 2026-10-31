package dev.owl.app.retention

import dev.owl.app.data.db.SegmentEntity
import dev.owl.app.model.RetentionPolicy
import dev.owl.app.model.SegmentState

/**
 * A14 / decisions.md #11: audio is deleted after the chosen period unless "음성 보존" is on.
 * Text is never deleted. Age counts from the recording time, or from when we received it if the
 * real time is unknown.
 */
object RetentionRules {
    private const val DAY_MS = 24 * 3_600_000L

    /** When this segment's audio will be deleted, or null if never. */
    fun deleteAt(policy: RetentionPolicy, s: SegmentEntity): Long? {
        if (s.preserveAudio || s.audioDeleted) return null
        val days = policy.days ?: return null
        if (policy == RetentionPolicy.AFTER_TRANSCRIPTION) {
            // Only once the text exists; before that the audio is all we have.
            return if (s.state == SegmentState.DONE) s.receivedAtMs else null
        }
        return (s.startUnixMs ?: s.receivedAtMs) + days * DAY_MS
    }

    fun shouldDelete(policy: RetentionPolicy, s: SegmentEntity, nowMs: Long): Boolean {
        if (s.filePath == null || s.state == SegmentState.RECEIVING) return false
        val at = deleteAt(policy, s) ?: return false
        return at <= nowMs
    }
}
