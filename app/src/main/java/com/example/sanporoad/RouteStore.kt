package com.example.sanporoad

import android.content.Context
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class RouteStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("sanpo_routes", Context.MODE_PRIVATE)
    private val settings = WalkPreferences(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(SavedRoute.serializer())

    @Synchronized
    fun getAll(): List<SavedRoute> = visible().filter { it.deletedAt == null }.sortedByDescending { it.startedAt }

    @Synchronized
    private fun visible(): List<SavedRoute> = readAll().filter {
        it.ownerId == null || it.ownerId == settings.activeAccountId
    }

    @Synchronized
    fun save(route: SavedRoute) {
        val owned = route.copy(ownerId = route.ownerId ?: settings.activeAccountId,
            updatedAt = maxOf(System.currentTimeMillis(), route.updatedAt + 1))
        check(route.ownerId == null || route.ownerId == settings.activeAccountId)
        writeAll(readAll().filterNot { it.id == route.id && (it.ownerId == route.ownerId || it.ownerId == owned.ownerId) } + owned)
    }

    @Synchronized
    private fun upsert(route: SavedRoute) {
        val all = readAll().filterNot { it.id == route.id && it.ownerId == route.ownerId } + route
        writeAll(all)
    }

    @Synchronized
    fun claimForShare(route: SavedRoute): SavedRoute {
        val account = settings.activeAccountId ?: error("ログインしてください")
        check(route.ownerId == null || route.ownerId == account)
        val owned = route.copy(ownerId = account)
        writeAll(readAll().filterNot { it.id == route.id && it.ownerId == route.ownerId } + owned)
        return owned
    }

    @Synchronized
    fun delete(route: SavedRoute) {
        val stamp = maxOf(System.currentTimeMillis(), route.updatedAt + 1)
        // Persist deletion before removing any photo. Keep a tombstone for offline retries.
        upsert(route.copy(deletedAt = stamp, updatedAt = stamp, name = "削除済み", note = "",
            points = emptyList(), distanceMeters = 0.0, durationMs = 0, photoPath = null))
        PhotoStore.localFile(route.photoPath)?.delete()
    }

    @Synchronized
    fun deleteVisible() { getAll().forEach(::delete) }

    @Synchronized
    fun eraseAccountLocally(account: String) {
        val all = readAll()
        writeAll(all.filterNot { it.ownerId == account })
        all.filter { it.ownerId == account }.forEach { PhotoStore.localFile(it.photoPath)?.delete() }
    }

    @Synchronized
    private fun adoptGuestRecords(account: String, remote: List<SavedRoute>) {
        val all = readAll().toMutableList()
        all.indices.forEach { index ->
            val route = all[index]
            if (route.ownerId == null) {
                val other = remote.find { it.id == route.id } ?: all.find { it.id == route.id && it.ownerId == account }
                // Legacy timestamp IDs can collide across devices. Preserve both walks.
                val collision = other != null && (other.startedAt != route.startedAt || other.points != route.points)
                val id = if (collision) java.util.UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE) else route.id
                all[index] = route.copy(id = id, ownerId = account)
            }
        }
        writeAll(all.groupBy { it.ownerId to it.id }.values.map { copies ->
            copies.reduce { a, b -> mergeRoute(a, b) }
        })
    }

    @Synchronized
    fun merge(routes: List<SavedRoute>) {
        val all = readAll().associateBy { it.ownerId to it.id }.toMutableMap()
        routes.forEach { remote ->
            val key = remote.ownerId to remote.id
            val previous = all[key]
            val merged = mergeRoute(previous, remote)
            all[key] = merged
        }
        val old = readAll()
        writeAll(all.values.toList())
        old.filter { route -> all[route.ownerId to route.id]?.deletedAt != null }.forEach {
            PhotoStore.localFile(it.photoPath)?.delete()
        }
    }

    suspend fun syncAll(): SyncResult = operations.withLock {
        check(settings.cloudSync) { "クラウド同期はOFFです" }
        val account = SupabaseRouteSync.prepareAccount()
        check(account == settings.activeAccountId) { "アカウントを確認してください" }
        val remote = SupabaseRouteSync.pull().getOrThrow()
        check(settings.cloudSync && settings.activeAccountId == account)
        adoptGuestRecords(account, remote)
        merge(remote) // Restore BEFORE upload: reinstall must never replace the server with stale data.
        var failed = 0
        val local = visible().filter { it.ownerId == account }
        local.forEach { route ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            check(settings.cloudSync && settings.activeAccountId == account)
            SupabaseRouteSync.push(route, settings.photoSync).onSuccess { path ->
                synchronized(this) {
                    val current = readAll().find { it.ownerId == account && it.id == route.id }
                    if (current?.updatedAt == route.updatedAt) upsert(current.copy(photoObjectPath = path))
                }
            }.onFailure { failed++ }
        }
        SyncResult(local.size - failed, failed, remote.count { it.deletedAt == null }, true)
    }

    suspend fun flushDeletes() = operations.withLock {
        val account = SupabaseRouteSync.prepareAccount()
        check(account == settings.activeAccountId)
        visible().filter { it.ownerId == account && it.deletedAt != null }.forEach {
            SupabaseRouteSync.push(it, false).getOrThrow()
        }
    }

    private fun readAll(): List<SavedRoute> {
        val raw = prefs.getString("routes", "[]") ?: "[]"
        // Never silently replace a damaged store with an empty list.
        return json.decodeFromString(serializer, raw)
    }
    private fun writeAll(routes: List<SavedRoute>) {
        check(prefs.edit().putString("routes", json.encodeToString(serializer, routes)).commit()) { "端末に保存できませんでした" }
    }
    companion object { val operations = Mutex() }
}

data class SyncResult(val uploaded: Int, val failedUploads: Int, val downloaded: Int, val cloudAvailable: Boolean)
