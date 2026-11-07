package dev.owl.app.data

import dev.owl.app.data.db.LocationEntity
import dev.owl.app.data.db.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private val library = PlaceEntity(1, "중앙도서관", 37.5665, 126.9780)
    private val cafe = PlaceEntity(2, "학생회관 카페", 37.5672, 126.9780) // ~78 m north of the library

    @Test
    fun distanceIsAboutRight() {
        // 0.001° latitude ≈ 111 m
        val d = PlaceResolver.distanceM(37.0, 127.0, 37.001, 127.0)
        assertTrue(d in 110.0..112.5)
    }

    @Test
    fun defaultRadiusIs100m() {
        assertEquals(100.0, library.radiusM, 0.0)
    }

    @Test
    fun nearestNamedPlaceWithinRadiusWins() {
        val places = listOf(library, cafe)
        // 30 m north of the library: both within 100 m, the library is nearer.
        assertEquals("중앙도서관", PlaceResolver.nearestPlace(places, 37.56677, 126.9780)?.name)
        // 10 m south of the café.
        assertEquals("학생회관 카페", PlaceResolver.nearestPlace(places, 37.56711, 126.9780)?.name)
        // ~220 m away from both: unnamed ("알 수 없는 장소").
        assertNull(PlaceResolver.nearestPlace(places, 37.5645, 126.9780))
    }

    private fun fix(timeMs: Long, place: PlaceEntity) = LocationEntity(0, timeMs, place.latitude, place.longitude, null)
    private fun nowhere(timeMs: Long) = LocationEntity(0, timeMs, 37.0, 127.0, null)
    private val hour = 3_600_000L

    @Test
    fun eachFixHoldsUntilTheNextOne() {
        val rows = summarizeStays(
            listOf(fix(0, library), fix(2 * hour, cafe), fix(3 * hour, library)),
            listOf(library, cafe), fromMs = 0, toMs = 10 * hour, nowMs = 5 * hour,
        )
        assertEquals(listOf("중앙도서관" to 4 * hour, "학생회관 카페" to hour), rows.map { it.place.name to it.totalMs })
        assertEquals(listOf(2, 1), rows.map { it.visits })
    }

    @Test
    fun aStayAcrossThePeriodStartCountsFromTheStart() {
        val rows = summarizeStays(listOf(fix(-5 * hour, library), fix(hour, cafe)), listOf(library, cafe), 0, 2 * hour, 10 * hour)
        assertEquals(listOf(hour, hour), rows.map { it.totalMs })
    }

    @Test
    fun stopsAtThePeriodEnd() {
        val rows = summarizeStays(listOf(fix(hour, library), fix(5 * hour, cafe)), listOf(library, cafe), 0, 2 * hour, 10 * hour)
        assertEquals(listOf("중앙도서관" to hour), rows.map { it.place.name to it.totalMs })
    }

    @Test
    fun aFixHoldsForAtMost24Hours() {
        val rows = summarizeStays(listOf(fix(0, library)), listOf(library), 0, 100 * hour, 100 * hour)
        assertEquals(MAX_STAY_PER_FIX_MS, rows.single().totalMs)
    }

    @Test
    fun movingWithinOnePlaceIsOneVisitAndGapsSplitVisits() {
        val within = summarizeStays(listOf(fix(0, library), fix(hour, library)), listOf(library), 0, 2 * hour, 2 * hour)
        assertEquals(1, within.single().visits)
        val apart = summarizeStays(listOf(fix(0, library), fix(30 * hour, library)), listOf(library), 0, 31 * hour, 31 * hour)
        assertEquals(2, apart.single().visits)
        assertEquals(25 * hour, apart.single().totalMs)
    }

    @Test
    fun unnamedPlacesAddUpAsOne() {
        val rows = summarizeStays(listOf(nowhere(0), nowhere(hour).copy(latitude = 36.0), fix(2 * hour, library)), listOf(library), 0, 3 * hour, 3 * hour)
        assertEquals(listOf(null to 2 * hour, "중앙도서관" to hour), rows.map { it.place.name to it.totalMs })
    }

    @Test
    fun nothingBeforeTheFirstFixAndNothingInTheFuture() {
        assertEquals(emptyList<Any>(), summarizeStays(emptyList(), listOf(library), 0, hour, hour))
        assertEquals(emptyList<Any>(), summarizeStays(listOf(fix(5 * hour, library)), listOf(library), 0, 10 * hour, 3 * hour))
    }
}
