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
    val points: List<LatLngPoint>,
    // Game metadata is optional/defaulted for backward compatibility with existing saves.
    val gameContract: String? = null,
    val gameScore: Int = 0,
    val gameCleared: Boolean = false,
    val facility: String? = null
)
