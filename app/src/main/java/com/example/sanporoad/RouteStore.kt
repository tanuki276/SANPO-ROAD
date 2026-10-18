package com.example.sanporoad

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RouteStore(context: Context) {
    private val prefs = context.getSharedPreferences("sanpo_routes", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val routesSerializer = ListSerializer(SavedRoute.serializer())

    fun getAll(): List<SavedRoute> = readAll().sortedByDescending { it.startedAt }

    fun save(route: SavedRoute) {
        val merged = readAll().associateBy { it.id }.toMutableMap().apply {
            put(route.id, route)
        }.values.toList()
        writeAll(merged)
    }

    fun merge(routes: List<SavedRoute>) {
        if (routes.isEmpty()) return
        val merged = readAll().associateBy { it.id }.toMutableMap()
        routes.forEach { merged[it.id] = it }
        writeAll(merged.values.toList())
    }

    suspend fun syncAll(): SyncResult {
        val local = getAll()
        var uploaded = 0
        var failedUploads = 0

        local.forEach { route ->
            if (SupabaseRouteSync.push(route).isSuccess) uploaded++ else failedUploads++
        }

        val pulled = SupabaseRouteSync.pull()
        if (pulled.isSuccess) merge(pulled.getOrDefault(emptyList()))

        return SyncResult(
            uploaded = uploaded,
            failedUploads = failedUploads,
            downloaded = pulled.getOrDefault(emptyList()).size,
            cloudAvailable = pulled.isSuccess || uploaded > 0
        )
    }

    suspend fun syncRoute(route: SavedRoute): Boolean = SupabaseRouteSync.push(route).isSuccess

    private fun readAll(): List<SavedRoute> {
        val raw = prefs.getString("routes", "[]") ?: "[]"
        return runCatching { json.decodeFromString(routesSerializer, raw) }
            .getOrDefault(emptyList())
    }

    private fun writeAll(routes: List<SavedRoute>) {
        prefs.edit().putString("routes", json.encodeToString(routesSerializer, routes)).apply()
    }
}

data class SyncResult(
    val uploaded: Int,
    val failedUploads: Int,
    val downloaded: Int,
    val cloudAvailable: Boolean
)
