package dev.owl.app.data

import androidx.room.withTransaction
import dev.owl.app.data.db.LocationEntity
import dev.owl.app.data.db.OwlDatabase
import dev.owl.app.data.db.PlaceEntity
import dev.owl.app.location.LocationSink
import dev.owl.app.model.NamedPlace
import dev.owl.app.model.Place
import dev.owl.app.model.PlaceSummary
import dev.owl.app.model.PlacesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A name the user gives a place covers every location within this distance of where it was
 * named (A5/A7). 100 m is about one building plus typical phone GPS error with the balanced-power
 * setting LocationRecorder uses; small enough that a library and the café next door stay apart.
 * Real-world accuracy still has to be checked on a phone (indoors GPS can be off by more).
 */
const val PLACE_RADIUS_M: Double = 100.0

/** Stores phone GPS fixes (A5). */
class LocationStore(private val db: OwlDatabase) : LocationSink {
    override suspend fun record(timeMs: Long, latitude: Double, longitude: Double, accuracyM: Float?) {
        db.locations().insert(LocationEntity(0, timeMs, latitude, longitude, accuracyM))
    }
}

/**
 * Gives each recording a location — the GPS fix closest in time to its middle, within
 * [MAX_GAP_MS] — and a name if that location is inside a place the user named. There is no
 * reverse geocoding: Android's Geocoder asks a server, which PRD N8 rules out.
 */
class PlaceResolver(private val db: OwlDatabase) {
    /** New recordings get a location, then every located recording is (re)labelled. */
    suspend fun resolve() {
        for (s in db.segments().withoutPlace()) {
            val mid = s.startUnixMs!! + (s.durationMs ?: 0) / 2
            val fix = db.locations().nearest(mid, mid - MAX_GAP_MS, mid + MAX_GAP_MS) ?: continue
            db.segments().update(s.copy(latitude = fix.latitude, longitude = fix.longitude))
        }
        relabel()
    }

    /** Applies the named places to all past recordings (after a place is added, renamed or deleted). */
    suspend fun relabel() {
        db.withTransaction {
            val places = db.places().all()
            for (s in db.segments().located()) {
                val p = nearestPlace(places, s.latitude!!, s.longitude!!)
                if (s.placeId != p?.rowId || s.placeName != p?.name) db.segments().setPlace(s.rowId, p?.rowId, p?.name)
            }
        }
    }

    companion object {
        const val MAX_GAP_MS = 15 * 60_000L

        fun nearestPlace(places: List<PlaceEntity>, lat: Double, lon: Double): PlaceEntity? = places
            .map { it to distanceM(it.latitude, it.longitude, lat, lon) }
            .filter { (p, d) -> d <= p.radiusM }
            .minByOrNull { it.second }?.first

        /** Equirectangular approximation; plenty for "within 100 m". */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val k = Math.PI / 180
            val x = (lon2 - lon1) * k * cos((lat1 + lat2) / 2 * k)
            val y = (lat2 - lat1) * k
            return sqrt(x * x + y * y) * 6_371_000.0
        }
    }
}

class PlacesRepositoryImpl(private val db: OwlDatabase, private val resolver: PlaceResolver) : PlacesRepository {
    override fun places(): Flow<List<NamedPlace>> = db.places().withCounts().map { list ->
        list.map { NamedPlace(it.place.rowId, it.place.name, it.place.latitude, it.place.longitude, it.entryCount) }
    }

    override suspend fun nameEntryPlace(entryId: Long, name: String): Result<Unit> {
        val clean = name.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("이름을 입력해 주세요"))
        val located = db.segments().entrySegments(entryId).firstOrNull { it.latitude != null }
            ?: return Result.failure(IllegalStateException("이 기록에는 위치가 없어요"))
        if (located.placeId != null) {
            db.places().rename(located.placeId, clean)
        } else {
            db.places().insert(PlaceEntity(0, clean, located.latitude!!, located.longitude!!, PLACE_RADIUS_M))
        }
        resolver.relabel()
        return Result.success(Unit)
    }

    override suspend fun renamePlace(id: Long, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        db.places().rename(id, clean)
        resolver.relabel()
    }

    override suspend fun deletePlace(id: Long) {
        db.places().delete(id)
        resolver.relabel()
    }

    override fun stays(fromMs: Long, toMs: Long): Flow<List<PlaceSummary>> =
        combine(db.locations().observeRange(fromMs, toMs), db.places().observeAll()) { fixes, places ->
            summarizeStays(listOfNotNull(db.locations().lastBefore(fromMs)) + fixes, places, fromMs, toMs, System.currentTimeMillis())
        }
}

/**
 * A fix counts as "still there" for at most this long. LocationRecorder gets no fix while the phone
 * stays within 50 m, so a long gap is normally a long stay; beyond this it more likely means 장소 기록
 * was off or the phone was dead, and we cannot tell which.
 */
const val MAX_STAY_PER_FIX_MS: Long = 24 * 3_600_000L

/**
 * Time spent at each place within [fromMs, toMs) (A19), most first: every fix holds until the next
 * one, up to [MAX_STAY_PER_FIX_MS], and never past [nowMs]. Unnamed places count as one
 * "알 수 없는 장소". [fixes] are oldest first and should start with the last fix before [fromMs],
 * which is where the period begins. A visit is an unbroken stretch at one place.
 */
fun summarizeStays(fixes: List<LocationEntity>, places: List<PlaceEntity>, fromMs: Long, toMs: Long, nowMs: Long): List<PlaceSummary> {
    val periodEnd = minOf(toMs, nowMs)
    val summaries = LinkedHashMap<Long?, PlaceSummary>()
    var lastPlace: Long? = null
    var lastEnd: Long? = null
    fixes.forEachIndexed { i, fix ->
        val start = maxOf(fix.timeMs, fromMs)
        val end = minOf(fixes.getOrNull(i + 1)?.timeMs ?: periodEnd, fix.timeMs + MAX_STAY_PER_FIX_MS, periodEnd)
        if (end <= start) return@forEachIndexed
        val named = PlaceResolver.nearestPlace(places, fix.latitude, fix.longitude)
        val key = named?.rowId
        val sameVisit = lastEnd == start && lastPlace == key
        val before = summaries[key] ?: PlaceSummary(
            Place(named?.name, named?.latitude ?: fix.latitude, named?.longitude ?: fix.longitude, key),
            visits = 0,
            totalMs = 0,
        )
        summaries[key] = before.copy(visits = before.visits + if (sameVisit) 0 else 1, totalMs = before.totalMs + end - start)
        lastPlace = key
        lastEnd = end
    }
    return summaries.values.sortedByDescending { it.totalMs }
}
