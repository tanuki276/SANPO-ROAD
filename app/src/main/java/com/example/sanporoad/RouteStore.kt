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

    fun getAll(): List<SavedRoute> {
        val raw = prefs.getString("routes", "[]") ?: "[]"
        return runCatching { json.decodeFromString(routesSerializer, raw) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.startedAt }
    }

    fun save(route: SavedRoute) {
        val routes = getAll().plus(route)
        prefs.edit().putString("routes", json.encodeToString(routesSerializer, routes)).apply()
    }
}
