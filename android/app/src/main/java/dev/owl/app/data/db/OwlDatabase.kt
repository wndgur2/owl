package dev.owl.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dev.owl.app.model.SegmentState

@Database(
    entities = [
        SegmentEntity::class, EventEntity::class, TimeSyncEntity::class, LocationEntity::class,
        PlaceEntity::class, SentenceEntity::class, StatusSampleEntity::class,
    ],
    version = 1,
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
            Room.databaseBuilder(context, OwlDatabase::class.java, "owl.db").build()
    }
}

class Converters {
    @TypeConverter
    fun fromState(state: SegmentState): String = state.name

    @TypeConverter
    fun toState(value: String): SegmentState = SegmentState.valueOf(value)
}
