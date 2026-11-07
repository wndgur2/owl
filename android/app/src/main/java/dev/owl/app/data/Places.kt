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
            val edges = db.locations().lastBefore(fromMs) to db.locations().firstFrom(toMs)
            summarizeStays(listOfNotNull(edges.first) + fixes + listOfNotNull(edges.second), places, fromMs, toMs)
        }
}

/** Fixes further apart than this say nothing: 장소 기록 was off, the phone was dead, or anything happened. */
const val MAX_STAY_GAP_MS: Long = 24 * 3_600_000L

/** A shorter stretch at one place is passing through, not staying. */
const val MIN_STAY_MS: Long = 5 * 60_000L

/** Coarser fixes (cell towers) cannot tell one place from the next, so they neither start nor break a stay. */
const val MAX_FIX_ACCURACY_M: Float = 200f

private class Stay(val anchor: LocationEntity, var named: PlaceEntity?) {
    val startMs = anchor.timeMs
    var endMs = anchor.timeMs

    /** Two different named places stay apart however close; otherwise within [PLACE_RADIUS_M] of where this began. */
    fun includes(fix: LocationEntity, fixNamed: PlaceEntity?): Boolean {
        val here = named
        if (fix.timeMs - endMs > MAX_STAY_GAP_MS) return false
        if (here != null && fixNamed != null) return here.rowId == fixNamed.rowId
        return PlaceResolver.distanceM(anchor.latitude, anchor.longitude, fix.latitude, fix.longitude) <= PLACE_RADIUS_M
    }
}

/**
 * Time spent at each place within [fromMs, toMs) (A19), most first. Only the time between fixes at
 * the same place counts: time between place A and place B, which is travel, goes to neither, and a
 * stay ends at its last fix (decisions #53). Fixes come only a few times an hour in the background,
 * so a stay is cut short by up to that gap at each end. [fixes] are oldest first and should include
 * the last fix before [fromMs] and the first from [toMs], so stays crossing the edges count.
 * Unnamed places add up as one "알 수 없는 장소".
 */
fun summarizeStays(fixes: List<LocationEntity>, places: List<PlaceEntity>, fromMs: Long, toMs: Long): List<PlaceSummary> {
    val stays = ArrayList<Stay>()
    for (fix in fixes) {
        if ((fix.accuracyM ?: 0f) > MAX_FIX_ACCURACY_M) continue
        val named = PlaceResolver.nearestPlace(places, fix.latitude, fix.longitude)
        val current = stays.lastOrNull()
        if (current != null && current.includes(fix, named)) {
            current.endMs = fix.timeMs
            current.named = current.named ?: named
        } else {
            stays += Stay(fix, named)
        }
    }
    val summaries = LinkedHashMap<Long?, PlaceSummary>()
    for (stay in stays) {
        val ms = minOf(stay.endMs, toMs) - maxOf(stay.startMs, fromMs)
        if (ms < MIN_STAY_MS) continue
        val named = stay.named
        val key = named?.rowId
        val before = summaries[key] ?: PlaceSummary(
            Place(named?.name, named?.latitude ?: stay.anchor.latitude, named?.longitude ?: stay.anchor.longitude, key),
            visits = 0,
            totalMs = 0,
        )
        summaries[key] = before.copy(visits = before.visits + 1, totalMs = before.totalMs + ms)
    }
    return summaries.values.sortedByDescending { it.totalMs }
}
