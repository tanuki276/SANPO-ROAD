package com.example.sanporoad

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.time.Duration.Companion.hours

object SupabaseRouteSync {
    private const val PHOTO_BUCKET = "walk-photos"
    private val json = Json { encodeDefaults = true }

    private val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Storage)
        }
    }

    suspend fun push(route: SavedRoute): Result<Unit> = runCatching {
        val userId = ensureUserId()
        val photoObjectPath = uploadPhotoIfNeeded(route, userId)
        client.from("sanpo_routes").upsert(route.toRemote(userId, photoObjectPath)) {
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

    suspend fun signedPhotoUrl(objectPath: String): String? = runCatching {
        ensureUserId()
        client.storage[PHOTO_BUCKET].createSignedUrl(objectPath, 1.hours)
    }.getOrNull()

    suspend fun createShareLink(route: SavedRoute): Result<String> = runCatching {
        val parameters = buildJsonObject {
            put("p_route_local_id", route.id)
            put("p_name", route.name)
            put("p_started_at", Instant.ofEpochMilli(route.startedAt).toString())
            put("p_duration_ms", route.durationMs)
            put("p_distance_meters", route.distanceMeters)
            put("p_points", json.encodeToJsonElement(ListSerializer, route.points))
            put("p_note", route.note)
        }
        val token = client.postgrest.rpc(
            function = "create_sanpo_share",
            parameters = parameters
        ).decodeAs<String>()
        BuildConfig.SHARE_API_URL.removeSuffix("/api/share") + "/s/$token"
    }

    private suspend fun uploadPhotoIfNeeded(route: SavedRoute, userId: String): String? {
        val local = PhotoStore.localFile(route.photoPath)
        if (local == null) return route.photoObjectPath

        val ext = local.extension.ifBlank { "jpg" }
        val objectPath = "$userId/${route.id}.$ext"
        client.storage[PHOTO_BUCKET].upload(
            path = objectPath,
            data = local.readBytes()
        ) {
            upsert = true
        }
        return objectPath
    }

    private suspend fun ensureUserId(): String {
        client.auth.currentUserOrNull()?.let { return it.id }
        client.auth.signInAnonymously()
        return client.auth.currentUserOrNull()?.id
            ?: error("Supabase anonymous sign-in did not create a user")
    }

    private fun SavedRoute.toRemote(userId: String, syncedPhotoPath: String?) = RemoteRoute(
        userId = userId,
        localId = id,
        name = name,
        startedAt = Instant.ofEpochMilli(startedAt).toString(),
        durationMs = durationMs,
        distanceMeters = distanceMeters,
        points = points,
        note = note,
        photoObjectPath = syncedPhotoPath
    )
}

private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(LatLngPoint.serializer())

@Serializable
private data class RemoteRoute(
    @SerialName("user_id") val userId: String,
    @SerialName("local_id") val localId: Long,
    val name: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("distance_meters") val distanceMeters: Double,
    val points: List<LatLngPoint>,
    val note: String = "",
    @SerialName("photo_object_path") val photoObjectPath: String? = null
) {
    fun toLocalOrNull(): SavedRoute? = runCatching {
        SavedRoute(
            id = localId,
            name = name,
            startedAt = Instant.parse(startedAt).toEpochMilli(),
            durationMs = durationMs,
            distanceMeters = distanceMeters,
            points = points,
            note = note,
            photoPath = null,
            photoObjectPath = photoObjectPath
        )
    }.getOrNull()
}
