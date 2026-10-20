package com.example.sanporoad
import androidx.room.*
@Dao interface WalkDao {
    @Query("SELECT * FROM walks WHERE key NOT LIKE 'live:%' ORDER BY startedAt DESC") fun all(): List<WalkEntity>
    @Query("SELECT * FROM walks WHERE `key` = :key") fun find(key: String): WalkEntity?
    @Upsert fun put(walk: WalkEntity)
    @Query("DELETE FROM walks WHERE `key` = :key") fun delete(key: String)
    @Query("SELECT value FROM store_metadata WHERE `key` = :key") fun metadata(key: String): String?
    @Upsert fun metadata(value: StoreMetadata)
}
