package com.example.sanporoad

import android.content.Context
import kotlinx.serialization.json.Json

class RouteStore(context: Context) {
    private val prefs = context.getSharedPreferences("sanpo_routes", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getAll(): List<SavedRoute> {
        val raw = prefs.getString("routes", "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<SavedRoute>>(raw) }.getOrDefault(emptyList()).sortedByDescending { it.startedAt }
    }

    fun save(route: SavedRoute) {
        prefs.edit().putString("routes", json.encodeToString(getAll().plus(route))).apply()
    }
}
