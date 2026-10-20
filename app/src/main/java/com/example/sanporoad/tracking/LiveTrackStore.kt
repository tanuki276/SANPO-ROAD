package com.example.sanporoad
import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
/** Session points are appended individually; preferences contain only small session metadata. */
object LiveTrackStore {
    @Volatile private var cached: List<LatLngPoint> = emptyList()
    @Volatile var error: String? = null
        private set
    private fun key(context: Context) = "live:${LocationTrackingService.getSessionId(context)}"
    fun points(): List<LatLngPoint> = cached
    @Synchronized fun load(context: Context) {
        val db = AppDatabase.get(context); val key = key(context)
        if (db.walks().find(key) == null) {
            val prefs = context.getSharedPreferences("sanpo_tracking", Context.MODE_PRIVATE)
            val raw = prefs.getString("points", "[]") ?: "[]"
            val legacy = try { Json { ignoreUnknownKeys = true }.decodeFromString<List<LatLngPoint>>(raw) }
            catch (e: Exception) {
                val file = File(context.noBackupFilesDir,"recovery/tracking-corrupt.json")
                file.parentFile?.mkdirs(); if (!file.exists()) file.writeText(raw)
                error = "記録を読み込めません。復旧用データを保持しています。"
                throw IllegalStateException(error,e)
            }
            db.runInTransaction {
                db.walks().put(WalkEntity(key,"",LocationTrackingService.getSessionId(context),
                    LocationTrackingService.getStartedAt(context),0,null,"LIVE","{}"))
                db.points().insert(legacy.mapIndexed { i,p -> TrackPointEntity(key,i,p.lat,p.lng,p.timeMs,p.accuracyM,p.segment) })
            }
            check(prefs.edit().remove("points").commit())
        }
        cached = db.points().forWalk(key).map { LatLngPoint(it.lat,it.lng,it.timeMs,it.accuracyM,it.segment) }
        error = null
    }
    internal fun accumulator(context: Context): GpsDistanceAccumulator? = runCatching {
        Json.decodeFromString<GpsDistanceAccumulator>(AppDatabase.get(context).walks().find(key(context))!!.metadata)
    }.getOrNull()
    @Synchronized internal fun append(context: Context, points: List<LatLngPoint>, accumulator: GpsDistanceAccumulator) {
        try {
            val offset = cached.size
            val db = AppDatabase.get(context)
            db.runInTransaction {
                db.points().insert(points.mapIndexed { i,p ->
                    TrackPointEntity(key(context),offset+i,p.lat,p.lng,p.timeMs,p.accuracyM,p.segment) })
                val row = checkNotNull(db.walks().find(key(context)))
                db.walks().put(row.copy(metadata=Json.encodeToString(GpsDistanceAccumulator.serializer(),accumulator)))
            }
            cached = cached + points; error = null
        } catch(e: Exception) { error = "GPS記録の保存に失敗しました。空き容量を確認してください。"; throw e }
    }
    @Synchronized fun clear(context: Context) {
        AppDatabase.get(context).walks().delete(key(context)); cached = emptyList(); error = null
    }
}

