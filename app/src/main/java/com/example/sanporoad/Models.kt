package com.example.sanporoad

import kotlinx.serialization.Serializable

@Serializable
data class LatLngPoint(val lat: Double, val lng: Double, val timeMs: Long)

@Serializable
data class SavedRoute(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val durationMs: Long,
    val distanceMeters: Double,
    val points: List<LatLngPoint>
)
