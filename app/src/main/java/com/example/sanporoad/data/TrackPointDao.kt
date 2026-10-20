package com.example.sanporoad
import androidx.room.*
@Dao interface TrackPointDao {
    @Query("SELECT * FROM track_points WHERE walkKey = :key ORDER BY position") fun forWalk(key: String): List<TrackPointEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insert(points: List<TrackPointEntity>)
    @Query("DELETE FROM track_points WHERE walkKey = :key") fun delete(key: String)
}
