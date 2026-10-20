package com.example.sanporoad

import android.content.Context
import androidx.room.*

@Entity(tableName = "walks", indices = [Index("startedAt")])
data class WalkEntity(@PrimaryKey val key: String, val owner: String, val routeId: Long,
    val startedAt: Long, val updatedAt: Long, val deletedAt: Long?, val syncState: String,
    val metadata: String)
@Entity(tableName = "track_points", primaryKeys = ["walkKey", "position"],
    foreignKeys = [ForeignKey(entity = WalkEntity::class, parentColumns = ["key"],
        childColumns = ["walkKey"], onDelete = ForeignKey.CASCADE)])
data class TrackPointEntity(val walkKey: String, val position: Int, val lat: Double,
    val lng: Double, val timeMs: Long, val accuracyM: Float?, val segment: Int)
@Entity(tableName = "store_metadata")
data class StoreMetadata(@PrimaryKey val key: String, val value: String)
@Database(entities = [WalkEntity::class, TrackPointEntity::class, StoreMetadata::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun walks(): WalkDao
    abstract fun points(): TrackPointDao
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sanpo.db")
                .build().also { instance = it }
        }
    }
}
