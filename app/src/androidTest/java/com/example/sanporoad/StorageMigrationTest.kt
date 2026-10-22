package com.example.sanporoad
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageMigrationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val route=SavedRoute(1,"legacy",10,2000,5.0,listOf(LatLngPoint(35.0,139.0,10,5f)))
    @Before fun reset() {
        AppDatabase.get(context).clearAllTables()
        context.getSharedPreferences("sanpo_routes",0).edit().clear().commit()
        File(context.noBackupFilesDir,"recovery/routes-corrupt.json").delete()
    }
    @Test fun importsLegacyOnceAndKeepsSource() {
        val prefs=context.getSharedPreferences("sanpo_routes",0)
        val raw=Json.encodeToString(ListSerializer(SavedRoute.serializer()),listOf(route))
        prefs.edit().putString("routes",raw).commit()
        val repo=RouteRepository(context)
        assertEquals(listOf(route),repo.all());assertEquals(raw,prefs.getString("routes",null))
        repo.put(route.copy(name="edited"));assertEquals("edited",RouteRepository(context).all().single().name)
    }
    @Test fun damagedLegacyIsPreservedAndBlocksWrites() {
        val prefs=context.getSharedPreferences("sanpo_routes",0);prefs.edit().putString("routes","broken").commit()
        val repo=RouteRepository(context)
        assertThrows(IllegalStateException::class.java){repo.replaceAll(emptyList())}
        assertEquals("broken",prefs.getString("routes",null))
        assertEquals("broken",File(context.noBackupFilesDir,"recovery/routes-corrupt.json").readText())
        assertTrue(AppDatabase.get(context).walks().all().isEmpty())
    }
    @Test fun versionOneDatabaseMigratesWithoutLosingPoints() {
        val name="migration-test.db";context.deleteDatabase(name)
        val file=context.getDatabasePath(name);file.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE walks (`key` TEXT NOT NULL PRIMARY KEY, owner TEXT NOT NULL, routeId INTEGER NOT NULL, startedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, syncState TEXT NOT NULL, metadata TEXT NOT NULL)")
            db.execSQL("CREATE INDEX index_walks_startedAt ON walks(startedAt)")
            db.execSQL("CREATE TABLE track_points (walkKey TEXT NOT NULL, position INTEGER NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, timeMs INTEGER NOT NULL, accuracyM REAL, segment INTEGER NOT NULL, PRIMARY KEY(walkKey,position), FOREIGN KEY(walkKey) REFERENCES walks(`key`) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE store_metadata (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO walks VALUES ('a','',1,10,10,NULL,'PENDING','{}')")
            db.execSQL("INSERT INTO track_points VALUES ('a',0,35,139,10,5,0)")
            db.version=1
        }
        val db = Room.databaseBuilder(context,AppDatabase::class.java,name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        try {
            assertEquals(35.0,db.points().forWalk("a").single().lat,0.0)
            assertEquals(2,db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
        context.deleteDatabase(name)
    }
    @Test fun cr11BackupRestoresMetadataAndPhotoWithoutRecordEligibility() {
        val store=RouteStore(context)
        val source=File(context.filesDir,"walk_photos/test-original.png").apply { parentFile?.mkdirs() }
        val bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val original=route.copy(distanceGoalMeters=3000.0,photoPath=source.absolutePath,tags=listOf("犬の散歩","川"),restDurationMs=4000,
            restIntervals=listOf(RestInterval(100,4100)),gpsQuality=GpsQualityReport(3,10,3.2f))
        val backup=RouteBackup.encode(context,listOf(original))
        assertEquals(1,RouteBackup.restore(context,store,backup))
        val restored=store.getAll().single()
        assertTrue(restored.imported);assertEquals(original.tags,restored.tags)
        assertEquals(original.gpsQuality,restored.gpsQuality)
        assertEquals(original.distanceGoalMeters,restored.distanceGoalMeters)
        assertEquals(original.restIntervals,restored.restIntervals)
        assertArrayEquals(source.readBytes(),File(restored.photoPath!!).readBytes())
        assertEquals(0,RouteBackup.restore(context,store,backup))
        store.save(restored.copy(imported=false,name="edited"))
        assertTrue(store.getAll().single().imported)
        assertEquals(0,WalkStats.calculate(store.getAll()).totalWalks)
    }
    @Test fun cr11CheckpointAndMapFixesAreCommittedTogether() {
        context.getSharedPreferences("sanpo_tracking",0).edit().putLong("sessionId",12345).commit()
        LiveTrackStore.load(context)
        val accumulator=GpsDistanceAccumulator()
        val points=(0..10).map { LatLngPoint(35.0+it/111194.9266,139.0,1000L+it*1000,3.2f) }
        points.forEach { accumulator.add(it) }
        LiveTrackStore.append(context,points,accumulator)
        LiveTrackStore.load(context)
        assertEquals(points,LiveTrackStore.points())
        assertEquals(accumulator.report(),LiveTrackStore.accumulator(context)!!.report())
        LiveTrackStore.clear(context)
    }
}

