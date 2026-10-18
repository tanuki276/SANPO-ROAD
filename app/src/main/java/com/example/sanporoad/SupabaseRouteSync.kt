package com.example.sanporoad

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

object SupabaseRouteSync {
    private const val PHOTO_BUCKET = "walk-photos"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val client by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
            install(Auth) {
                autoLoadFromStorage = false
                alwaysAutoRefresh = false
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            install(Storage)
        }
    }
    private suspend fun loadSession() {
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull() == null) client.auth.loadFromStorage(autoRefresh = false)
    }
    suspend fun requestOtp(email: String) {
        client.auth.awaitInitialization()
        client.auth.signInWith(OTP) { this.email = email }
    }
    suspend fun verifyOtp(email: String, token: String): String {
        client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = email, token = token)
        return requireAccount()
    }
    private fun requireAccount(): String {
        val user = client.auth.currentUserOrNull() ?: error("設定からログインしてください")
        check(!user.email.isNullOrBlank()) { "設定からメールでログインしてください" }
        return user.id
    }
    suspend fun prepareAccount(): String {
        loadSession()
        requireAccount()
        client.auth.refreshCurrentSession()
        return requireAccount()
    }
    suspend fun logout() {
        loadSession()
        try { client.auth.signOut() } finally { client.auth.clearSession() }
    }
    suspend fun forgetSession() { client.auth.clearSession() }

    suspend fun push(route: SavedRoute, syncPhotos: Boolean): Result<String?> = result {
        val user = requireAccount()
        check(route.ownerId == user)
        var photo = route.photoObjectPath?.takeIf { it.startsWith("$user/") }
        if (route.deletedAt != null) {
            client.postgrest.rpc("upsert_sanpo_route_v1", buildJsonObject {
                put("p_route", json.encodeToJsonElement(RemoteRoute.serializer(), RemoteRoute.from(route, user, null)))
            })
        }
        if (route.deletedAt != null || route.photoRemoved) {
            client.storage[PHOTO_BUCKET].delete(listOf("$user/${route.id}.jpg", "$user/${route.id}.png", "$user/${route.id}.webp") + listOfNotNull(photo))
            photo = null
        } else if (syncPhotos) {
            PhotoStore.localFile(route.photoPath)?.let { local ->
                photo = "$user/${route.id}.${local.extension.ifBlank { "jpg" }}"
                client.storage[PHOTO_BUCKET].upload(photo!!, local.readBytes()) { upsert = true }
            }
        }
        val payload = RemoteRoute.from(route, user, photo)
        client.postgrest.rpc("upsert_sanpo_route_v1", buildJsonObject {
            put("p_route", json.encodeToJsonElement(RemoteRoute.serializer(), payload))
        })
        photo
    }
    suspend fun pull(): Result<List<SavedRoute>> = result {
        val user = requireAccount()
        val rows = mutableListOf<RemoteRoute>()
        var offset = 0L
        do {
            val page = client.from("sanpo_routes").select {
                filter { eq("user_id", user) }
                order("local_id", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                range(offset, offset + 499)
            }.decodeList<RemoteRoute>()
            rows += page
            offset += page.size
        } while (page.size == 500)
        rows.map { it.toLocal() }
    }
    suspend fun signedPhotoUrl(objectPath: String): String? = result {
        loadSession()
        val user = requireAccount()
        check(objectPath.startsWith("$user/"))
        client.storage[PHOTO_BUCKET].createSignedUrl(objectPath, 5.minutes)
    }.getOrNull()

    suspend fun createShareLink(route: SavedRoute, shareMap: Boolean = false): Result<String> = result {
        val user = prepareAccount()
        check(route.ownerId == null || route.ownerId == user)
        val parameters = buildJsonObject {
            put("p_route_local_id", route.id)
            put("p_name", route.name)
            put("p_started_at", Instant.ofEpochMilli(route.startedAt).toString())
            put("p_duration_ms", route.durationMs)
            put("p_distance_meters", route.distanceMeters)
            put("p_points", json.encodeToJsonElement(ListSerializer(LatLngPoint.serializer()), if (shareMap) route.points else emptyList()))
            put("p_note", route.note)
            put("p_share_map", shareMap)
        }
        val token = client.postgrest.rpc("create_sanpo_share_v1", parameters).decodeAs<String>()
        shareUrl(token)
    }
    fun shareUrl(token: String) = BuildConfig.SHARE_API_URL.removeSuffix("/api/share") + "/s/$token"
    suspend fun shares(): List<ManagedShare> {
        val user = prepareAccount()
        val rows = mutableListOf<ManagedShare>()
        var offset = 0L
        do {
            val page = client.from("sanpo_shares").select {
                filter { eq("owner_id", user) }
                order("token", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                range(offset, offset + 499)
            }.decodeList<ManagedShare>()
            rows += page; offset += page.size
        } while (page.size == 500)
        return rows.sortedByDescending { it.createdAt }
    }
    suspend fun revokeShare(token: String) {
        val user = prepareAccount()
        client.from("sanpo_shares").delete { filter { eq("owner_id", user); eq("token", token) } }
    }
    suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        prepareAccount()
        val token = client.auth.currentAccessTokenOrNull() ?: error("ログインしてください")
        val connection = java.net.URL("${BuildConfig.SUPABASE_URL}/functions/v1/sanpo-delete-account").openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write("{\"confirm\":\"DELETE\"}".toByteArray()) }
            check(connection.responseCode in 200..299) {
                if (connection.responseCode == 409) "同じ認証アカウントが別サービスでも使われています。運営者に削除を依頼してください。"
                else "アカウントを削除できませんでした。通信状態を確認して再試行してください。"
            }
            client.auth.clearSession()
        } finally { connection.disconnect() }
    }
    private suspend fun <T> result(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}

@Serializable
data class ManagedShare(
    val token: String, val name: String,
    @SerialName("show_map") val showMap: Boolean,
    @SerialName("created_at") val createdAt: String
)

@Serializable
private data class RemoteRoute(
    @SerialName("user_id") val userId: String,
    @SerialName("local_id") val localId: Long,
    val name: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("distance_meters") val distanceMeters: Double,
    val points: List<LatLngPoint>, val note: String = "",
    @SerialName("photo_object_path") val photoObjectPath: String? = null,
    @SerialName("client_updated_at") val updatedAt: Long = 0,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    val favorite: Boolean = false
) {
    fun toLocal() = SavedRoute(localId, name, Instant.parse(startedAt).toEpochMilli(), durationMs,
        distanceMeters, points, note, null, photoObjectPath, userId, updatedAt, deletedAt, favorite)
    companion object {
        fun from(route: SavedRoute, user: String, photo: String?) = RemoteRoute(user, route.id,
            route.name, Instant.ofEpochMilli(route.startedAt).toString(), route.durationMs,
            route.distanceMeters, route.points, route.note, photo, route.updatedAt, route.deletedAt, route.favorite)
    }
}
