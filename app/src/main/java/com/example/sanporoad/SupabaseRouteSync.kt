package com.example.sanporoad

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

object SupabaseRouteSync {
    private val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        ) {
            install(Auth)
            install(Postgrest)
        }
    }

    suspend fun push(route: SavedRoute): Result<Unit> = runCatching {
        val userId = ensureUserId()
        client.from("sanpo_routes").upsert(route.toRemote(userId)) {
            onConflict = "user_id,local_id"
        }
    }

    suspend fun pull(): Result<List<SavedRoute>> = runCatching {
        ensureUserId()
        client.from("sanpo_routes")
            .select()
            .decodeList<RemoteRoute>()
            .mapNotNull { it.toLocalOrNull() }
    }

    private suspend fun ensureUserId(): String {
        client.auth.currentUserOrNull()?.let { return it.id }
        client.auth.signInAnonymously()
        return client.auth.currentUserOrNull()?.id
            ?: error("Supabase anonymous sign-in did not create a user")
    }

    private fun SavedRoute.toRemote(userId: String) = RemoteRoute(
        userId = userId,
        localId = id,
        name = name,
        startedAt = Instant.ofEpochMilli(startedAt).toString(),
        durationMs = durationMs,
        distanceMeters = distanceMeters,
        points = points,
        note = note
    )
}

@Serializable
private data class RemoteRoute(
    @SerialName("user_id") val userId: String,
    @SerialName("local_id") val localId: Long,
    val name: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("distance_meters") val distanceMeters: Double,
    val points: List<LatLngPoint>,
    val note: String = ""
) {
    fun toLocalOrNull(): SavedRoute? = runCatching {
        SavedRoute(
            id = localId,
            name = name,
            startedAt = Instant.parse(startedAt).toEpochMilli(),
            durationMs = durationMs,
            distanceMeters = distanceMeters,
            points = points,
            note = note
        )
    }.getOrNull()
}
