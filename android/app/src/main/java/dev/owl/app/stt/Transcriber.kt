package dev.owl.app.stt

import java.io.File

/** A sentence with its position inside the recording. */
data class TranscribedSentence(val startMs: Long, val endMs: Long, val text: String)

/**
 * On-device speech-to-text (PRD A6, decisions.md #2/#10: never a cloud engine).
 * The real engine (whisper.cpp or sherpa-onnx) is chosen in M6; it will decode the segment's
 * Opus frames to 16 kHz PCM and run Korean recognition here.
 */
interface Transcriber {
    /** False → recordings stay in RECEIVED ("텍스트 추출 중") until an engine exists. */
    val available: Boolean

    suspend fun transcribe(segmentFile: File, durationMs: Long): List<TranscribedSentence>
}

/** Until M6: no engine. */
class PlaceholderTranscriber : Transcriber {
    override val available: Boolean = false
    override suspend fun transcribe(segmentFile: File, durationMs: Long): List<TranscribedSentence> =
        throw UnsupportedOperationException("on-device STT engine is chosen in M6")
}

/**
 * Debug only, used with the fake pendant: canned Korean sentences spread over the recording so
 * the timeline, search and sentence-synced player can be exercised. Not speech recognition.
 */
class DemoTranscriber : Transcriber {
    override val available: Boolean = true

    override suspend fun transcribe(segmentFile: File, durationMs: Long): List<TranscribedSentence> {
        val seed = segmentFile.nameWithoutExtension.toIntOrNull() ?: segmentFile.name.hashCode()
        val count = (durationMs / 9_000).toInt().coerceIn(1, 8)
        val step = durationMs / count
        return List(count) { i ->
            TranscribedSentence(i * step, if (i == count - 1) durationMs else (i + 1) * step, LINES[Math.floorMod(seed * 3 + i, LINES.size)])
        }
    }

    private companion object {
        val LINES = listOf(
            "그러니까 이번 과제가 스케줄러 구현이잖아. 라운드 로빈이랑 MLFQ 둘 다 해야 되는 거지?",
            "응, 둘 다. 근데 MLFQ는 boost 주기도 바꿔 가면서 실험해서 보고서에 넣으래.",
            "아 맞다, 과제 마감 금요일 자정 아니고 목요일 자정이래. 조교님이 공지 다시 올린다고 했어.",
            "헐, 그럼 오늘부터 시작해야겠다. 저녁에 도서관 갈래? 나 7시 이후엔 괜찮아.",
            "좋아. 그 전에 테스트 케이스부터 같이 짜자.",
            "타임 퀀텀은 일단 10ms로 두고, 나중에 바꿔 보면서 비교하자.",
            "캡스톤 발표는 데모를 먼저 보여 주고, 구조 설명은 뒤로 빼는 게 나을 것 같아.",
            "내일 9시 수업이니까 펌웨어는 주말에 마저 보고, 오늘은 플래시 배선만 다시 확인하자.",
            "그래서 페이지 폴트가 나면 커널이 디스크에서 그 페이지를 읽어 오고, 이때 TLB도 같이 갱신해야 돼요.",
            "다음 주 퀴즈 범위는 8장까지예요.",
            "연결 간격을 15ms로 잡으면 전송은 빠른데, 평소엔 아예 끊어 두는 게 배터리엔 제일 낫대.",
            "캡스톤 중간발표 자료 마감이 다음 주 화요일이니까 주말엔 데모 영상부터 찍자.",
            "장학금 서류 마감이 언제였지? 메일 다시 찾아봐야겠다.",
            "제안서 마감 하루만 연장해 주실 수 있는지 메일 드려 볼까?",
            "점심은 학생회관에서 먹고 바로 실험실 가자.",
            "배터리가 3.6볼트 밑으로 내려가면 LED가 30초마다 세 번 깜빡여.",
        )
    }
}
