package dev.owl.app.usage

import dev.owl.app.data.ENTRY_MERGE_GAP_MS
import dev.owl.app.data.db.AppUsageEntity
import dev.owl.app.model.AppUsageCard
import dev.owl.app.model.AppUsageSummary
import dev.owl.app.usage.UsageEvent.Type.ALL_STOPPED
import dev.owl.app.usage.UsageEvent.Type.PAUSED
import dev.owl.app.usage.UsageEvent.Type.RESUMED
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageSessionsTest {
    private fun e(pkg: String, type: UsageEvent.Type, t: Long) = UsageEvent(pkg, type, t)

    @Test
    fun resumeThenPauseIsOneSession() {
        val w = UsageSessions.build(listOf(e("a", RESUMED, 1_000), e("a", PAUSED, 61_000)), toMs = 100_000)
        assertEquals(listOf(UsageSession("a", 1_000, 61_000)), w.finished)
        assertEquals(100_000, w.nextCursorMs)
    }

    @Test
    fun sameAppBackWithinTheGapIsJoined() {
        val events = listOf(e("a", RESUMED, 0), e("a", PAUSED, 10_000), e("a", RESUMED, 10_000 + UsageSessions.MERGE_GAP_MS), e("a", PAUSED, 30_000))
        assertEquals(listOf(UsageSession("a", 0, 30_000)), UsageSessions.build(events, toMs = 100_000).finished)
    }

    @Test
    fun sameAppBackAfterTheGapIsANewSession() {
        val events = listOf(e("a", RESUMED, 0), e("a", PAUSED, 10_000), e("a", RESUMED, 10_001 + UsageSessions.MERGE_GAP_MS), e("a", PAUSED, 30_000))
        assertEquals(2, UsageSessions.build(events, toMs = 100_000).finished.size)
    }

    @Test
    fun otherAppBetweenIsNotCountedTwice() {
        val events = listOf(
            e("a", RESUMED, 0), e("a", PAUSED, 10_000),
            e("b", RESUMED, 10_000), e("b", PAUSED, 12_000),
            e("a", RESUMED, 12_000), e("a", PAUSED, 20_000),
        )
        assertEquals(
            listOf(UsageSession("a", 0, 10_000), UsageSession("b", 10_000, 12_000), UsageSession("a", 12_000, 20_000)),
            UsageSessions.build(events, toMs = 100_000).finished,
        )
    }

    @Test
    fun screenOffEndsEveryOpenApp() {
        val events = listOf(e("a", RESUMED, 0), e("b", RESUMED, 1_000), e("", ALL_STOPPED, 9_000), e("a", PAUSED, 500_000))
        assertEquals(
            listOf(UsageSession("a", 0, 9_000), UsageSession("b", 1_000, 9_000)),
            UsageSessions.build(events, toMs = 600_000).finished,
        )
    }

    @Test
    fun runningAppIsReadAgainFromItsStart() {
        val w = UsageSessions.build(listOf(e("a", RESUMED, 5_000), e("b", RESUMED, 7_000), e("b", PAUSED, 8_000)), toMs = 100_000)
        assertEquals(listOf(UsageSession("b", 7_000, 8_000)), w.finished)
        assertEquals(5_000, w.nextCursorMs)
    }

    @Test
    fun justClosedSessionWaitsForTheGap() {
        val w = UsageSessions.build(listOf(e("a", RESUMED, 5_000), e("a", PAUSED, 98_000)), toMs = 100_000)
        assertEquals(emptyList<UsageSession>(), w.finished)
        assertEquals(5_000, w.nextCursorMs)
    }

    @Test
    fun pauseWithoutResumeIsIgnored() {
        val w = UsageSessions.build(listOf(e("a", PAUSED, 5_000)), toMs = 100_000)
        assertEquals(emptyList<UsageSession>(), w.finished)
        assertEquals(100_000, w.nextCursorMs)
    }

    @Test
    fun tooShortSessionIsDropped() {
        val events = listOf(e("a", RESUMED, 0), e("a", PAUSED, UsageSessions.MIN_SESSION_MS - 1))
        assertEquals(emptyList<UsageSession>(), UsageSessions.build(events, toMs = 100_000).finished)
    }

    @Test
    fun sessionsAtMostTwoMinutesApartShareACard() {
        val rows = listOf(
            AppUsageEntity(rowId = 1, packageName = "a", label = "에이", startMs = 0, endMs = 60_000),
            AppUsageEntity(rowId = 2, packageName = "b", label = "비", startMs = 60_000 + ENTRY_MERGE_GAP_MS, endMs = 400_000),
            AppUsageEntity(rowId = 3, packageName = "a", label = "에이", startMs = 400_001 + ENTRY_MERGE_GAP_MS, endMs = 600_000),
        )
        val cards = UsageSessions.cards(rows)
        assertEquals(listOf(1L, 3L), cards.map { it.id })
        assertEquals(0L to 400_000L, cards[0].startMs to cards[0].endMs)
        assertEquals(listOf("b", "a"), cards[0].apps.map { it.packageName })
        assertEquals(60_000 + 400_000 - 180_000L, cards[0].totalMs)
    }

    @Test
    fun noSessionsNoCards() {
        assertEquals(emptyList<AppUsageCard>(), UsageSessions.cards(emptyList()))
    }

    @Test
    fun summaryClipsToTheDayAndSortsByTime() {
        val rows = listOf(
            AppUsageEntity(packageName = "a", label = "옛 이름", startMs = 900, endMs = 1_100),
            AppUsageEntity(packageName = "b", label = "비", startMs = 1_200, endMs = 1_600),
            AppUsageEntity(packageName = "a", label = "새 이름", startMs = 1_700, endMs = 2_300),
        )
        assertEquals(
            listOf(AppUsageSummary("a", "새 이름", 100 + 300, 2), AppUsageSummary("b", "비", 400, 1)).sortedBy { it.label },
            UsageSessions.summarize(rows, fromMs = 1_000, toMs = 2_000).sortedBy { it.label },
        )
        assertEquals("b", UsageSessions.summarize(rows.drop(1), fromMs = 1_000, toMs = 2_000).first().packageName)
    }
}
