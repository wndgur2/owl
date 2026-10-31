package dev.owl.app.data

import dev.owl.app.data.db.LocationEntity
import dev.owl.app.data.db.OwlDatabase
import dev.owl.app.location.LocationSink
import kotlin.math.cos
import kotlin.math.sqrt

/** Stores phone GPS fixes (A5). */
class LocationStore(private val db: OwlDatabase) : LocationSink {
    override suspend fun record(timeMs: Long, latitude: Double, longitude: Double, accuracyM: Float?) {
        db.locations().insert(LocationEntity(0, timeMs, latitude, longitude, accuracyM))
    }
}

/**
 * Gives each recording a place: the GPS fix closest in time to its middle (within
 * [MAX_GAP_MS]), and a name if that fix is inside a named place. There is no reverse geocoding —
 * Android's Geocoder asks a server, which PRD N8 rules out — so unnamed places show coordinates.
 */
class PlaceResolver(private val db: OwlDatabase) {
    suspend fun resolve(): Int {
        val places = db.places().all()
        var n = 0
        for (s in db.segments().withoutPlace()) {
            val mid = s.startUnixMs!! + (s.durationMs ?: 0) / 2
            val fix = db.locations().nearest(mid, mid - MAX_GAP_MS, mid + MAX_GAP_MS) ?: continue
            val name = places
                .map { it to distanceM(it.latitude, it.longitude, fix.latitude, fix.longitude) }
                .filter { (p, d) -> d <= p.radiusM }
                .minByOrNull { it.second }?.first?.name
            db.segments().update(s.copy(latitude = fix.latitude, longitude = fix.longitude, placeName = name))
            n++
        }
        return n
    }

    companion object {
        const val MAX_GAP_MS = 15 * 60_000L

        /** Equirectangular approximation; plenty for "within 150 m". */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val k = Math.PI / 180
            val x = (lon2 - lon1) * k * cos((lat1 + lat2) / 2 * k)
            val y = (lat2 - lat1) * k
            return sqrt(x * x + y * y) * 6_371_000.0
        }
    }
}
