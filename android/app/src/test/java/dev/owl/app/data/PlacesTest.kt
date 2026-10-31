package dev.owl.app.data

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
}
