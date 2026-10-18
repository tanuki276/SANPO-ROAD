package com.example.sanporoad

import kotlinx.serialization.Serializable

@Serializable
data class LatLngPoint(
    val lat: Double,
    val lng: Double,
    val timeMs: Long,
    // Optional so routes recorded by older app versions still deserialize correctly.
    val accuracyM: Float? = null,
    val segment: Int = 0
)

@Serializable
data class SavedRoute(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val durationMs: Long,
    val distanceMeters: Double,
    val points: List<LatLngPoint>,
    // Free-form reflection after a walk. Defaults keep older local/cloud records compatible.
    val note: String = "",
    // Local app-private photo path. Not shared outside this device.
    val photoPath: String? = null,
    // Private Supabase Storage object path used to back up the photo across devices.
    val photoObjectPath: String? = null,
    val ownerId: String? = null,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
    val favorite: Boolean = false,
    val photoRemoved: Boolean = false
)
