package dev.owl.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.owl.app.model.SegmentState

@Database(
    entities = [
        SegmentEntity::class, EventEntity::class, TimeSyncEntity::class, LocationEntity::class,
        PlaceEntity::class, SentenceEntity::class, StatusSampleEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OwlDatabase : RoomDatabase() {
    abstract fun segments(): SegmentDao
    abstract fun events(): EventDao
    abstract fun timeSyncs(): TimeSyncDao
    abstract fun locations(): LocationDao
    abstract fun places(): PlaceDao
    abstract fun sentences(): SentenceDao
    abstract fun status(): StatusDao

    companion object {
        fun create(context: Context): OwlDatabase =
            Room.databaseBuilder(context, OwlDatabase::class.java, "owl.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * v2: timeline entries (merged segments), bookmark → entry assignment, named places.
         * Additive only, so recordings stored by v1 survive; EntryIndexer fills the new columns.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE segments ADD COLUMN entryKey INTEGER")
                db.execSQL("ALTER TABLE segments ADD COLUMN placeId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_segments_entryKey ON segments (entryKey)")
                db.execSQL("ALTER TABLE events ADD COLUMN entryKey INTEGER")
                db.execSQL("UPDATE places SET radiusM = ${dev.owl.app.data.PLACE_RADIUS_M}")
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromState(state: SegmentState): String = state.name

    @TypeConverter
    fun toState(value: String): SegmentState = SegmentState.valueOf(value)
}
