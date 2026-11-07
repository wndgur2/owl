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

    private val minute = 60_000L
    private val hour = 60 * minute
    private fun fix(timeMs: Long, place: PlaceEntity, accuracyM: Float? = null) = LocationEntity(0, timeMs, place.latitude, place.longitude, accuracyM)
    private fun nowhere(timeMs: Long, latitude: Double = 37.0) = LocationEntity(0, timeMs, latitude, 127.0, null)
    private fun stays(vararg fixes: LocationEntity, from: Long = 0, to: Long = 100 * hour) =
        summarizeStays(fixes.toList(), listOf(library, cafe), from, to)

    @Test
    fun onlyTimeBetweenFixesAtTheSamePlaceCounts() {
        val rows = stays(fix(0, library), fix(hour, library), fix(2 * hour, cafe), fix(5 * hour, cafe))
        // The hour between the library's last fix and the café's first is travel: neither gets it.
        assertEquals(listOf("학생회관 카페" to 3 * hour, "중앙도서관" to hour), rows.map { it.place.name to it.totalMs })
    }

    @Test
    fun aStayEndsAtItsLastFix() {
        assertEquals(hour, stays(fix(0, library), fix(hour, library)).single().totalMs)
        assertEquals(emptyList<Any>(), stays(fix(0, library)))
    }

    @Test
    fun comingBackIsAnotherVisit() {
        val row = stays(fix(0, library), fix(hour, library), fix(2 * hour, cafe), fix(3 * hour, library), fix(4 * hour, library)).single()
        assertEquals(2, row.visits)
        assertEquals(2 * hour, row.totalMs)
    }

    @Test
    fun aStayAcrossThePeriodEdgesCountsOnlyInside() {
        val row = stays(fix(-hour, library), fix(hour, library), fix(3 * hour, library), from = 0, to = 2 * hour).single()
        assertEquals(2 * hour, row.totalMs)
    }

    @Test
    fun aGapOver24HoursSaysNothing() {
        val row = stays(fix(0, library), fix(hour, library), fix(hour + MAX_STAY_GAP_MS + 1, library), fix(hour + MAX_STAY_GAP_MS + 1 + hour, library)).single()
        assertEquals(2 * hour, row.totalMs)
        assertEquals(2, row.visits)
        assertEquals(MAX_STAY_GAP_MS, stays(fix(0, library), fix(MAX_STAY_GAP_MS, library)).single().totalMs)
    }

    @Test
    fun passingThroughIsNotAStay() {
        assertEquals(emptyList<Any>(), stays(fix(0, library), fix(MIN_STAY_MS - 1, library)))
        assertEquals(MIN_STAY_MS, stays(fix(0, library), fix(MIN_STAY_MS, library)).single().totalMs)
    }

    @Test
    fun aCoarseFixNeitherStartsNorBreaksAStay() {
        val jump = LocationEntity(0, hour, 37.0, 127.0, 900f)
        assertEquals(2 * hour, stays(fix(0, library), jump, fix(2 * hour, library)).single().totalMs)
    }

    @Test
    fun nearbyNamedPlacesStayApart() {
        // The café is ~78 m from the library: inside the radius, but a different place.
        assertEquals(emptyList<Any>(), stays(fix(0, library), fix(hour, cafe)))
    }

    @Test
    fun unnamedPlacesSplitBeyondTheRadiusAndAddUpAsOne() {
        val rows = stays(nowhere(0), nowhere(hour), nowhere(2 * hour, latitude = 36.0), nowhere(3 * hour, latitude = 36.0))
        assertEquals(listOf(null to 2 * hour), rows.map { it.place.name to it.totalMs })
        assertEquals(2, rows.single().visits)
    }

    @Test
    fun aFixJustOutsideANamedPlaceStaysInIt() {
        val edge = LocationEntity(0, hour, library.latitude + 0.00095, library.longitude, null) // ~105 m north, past the radius from its centre
        val anchor = LocationEntity(0, 0, library.latitude + 0.0005, library.longitude, null) // ~55 m north, inside
        val row = summarizeStays(listOf(anchor, edge), listOf(library), 0, 10 * hour).single()
        assertEquals("중앙도서관" to hour, row.place.name to row.totalMs)
    }
}
