package com.example.sanporoad

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** All calls run on Dispatchers.IO. No destructive fallback on a damaged store. */
class RouteRepository(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun key(route: SavedRoute) = "${route.ownerId.orEmpty()}:${route.id}"
    fun ensureMigrated() = synchronized(migrationLock) {
        if (db.walks().metadata("legacyMigrated") != null) return@synchronized
        val prefs = context.getSharedPreferences("sanpo_routes", Context.MODE_PRIVATE)
        val raw = prefs.getString("routes", null)
        val routes = try {
            raw?.let { json.decodeFromString(ListSerializer(SavedRoute.serializer()), it) }.orEmpty()
        } catch (e: Exception) {
            val backup = File(context.noBackupFilesDir, "recovery/routes-corrupt.json")
            backup.parentFile?.mkdirs()
            if (!backup.exists()) backup.outputStream().use { it.write(raw.orEmpty().toByteArray()); it.fd.sync() }
            throw IllegalStateException("過去の記録を読み込めません。原本と復旧用コピーを保持しています。上書きせず復旧を依頼してください。", e)
        }
        db.runInTransaction {
            routes.forEach { require(it.formatVersion <= 2) { "未対応の保存形式です" }; put(it) }
            db.walks().metadata(StoreMetadata("legacyMigrated", "1"))
        }
        // Keep the source for recovery, but never write the old JSON store again.
    }
    fun all(): List<SavedRoute> { ensureMigrated(); return db.walks().all().map(::decode) }
    private fun decode(row: WalkEntity): SavedRoute = json.decodeFromString<SavedRoute>(row.metadata)
        .copy(points = db.points().forWalk(row.key).map { LatLngPoint(it.lat,it.lng,it.timeMs,it.accuracyM,it.segment) })
    fun put(route: SavedRoute) {
        require(route.formatVersion <= 2)
        val key = key(route)
        db.runInTransaction {
            val old = db.walks().find(key)
            db.walks().put(WalkEntity(key, route.ownerId.orEmpty(), route.id, route.startedAt,
                route.updatedAt,route.deletedAt,route.syncState,json.encodeToString(SavedRoute.serializer(),route.copy(points=emptyList()))))
            val points = route.points.mapIndexed { i,p -> TrackPointEntity(key,i,p.lat,p.lng,p.timeMs,p.accuracyM,p.segment) }
            if (old == null || db.points().forWalk(key) != points) {
                db.points().delete(key); db.points().insert(points)
            }
        }
    }
    fun replaceAll(routes: List<SavedRoute>) {
        ensureMigrated()
        db.runInTransaction {
            val retained = routes.map(::key).toSet()
            db.walks().all().filterNot { it.key in retained }.forEach { db.walks().delete(it.key) }
            routes.forEach(::put)
        }
    }
    companion object { private val migrationLock = Any() }
}
