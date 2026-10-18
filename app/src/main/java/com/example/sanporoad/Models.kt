package com.example.sanporoad

import kotlinx.serialization.Serializable

@Serializable
data class LatLngPoint(
    val lat: Double,
    val lng: Double,
    val timeMs: Long,
    // Optional so routes recorded by older app versions still deserialize correctly.
    val accuracyM: Float? = null
)

@Serializable
data class SavedRoute(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val durationMs: Long,
    val distanceMeters: Double,
    val points: List<LatLngPoint>
)
