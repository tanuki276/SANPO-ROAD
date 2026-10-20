package com.example.sanporoad

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable data class BackupPhoto(val routeId: Long, val base64: String)
@Serializable data class SanpoBackup(val kind: String = "SANPO-ROAD", val version: Int = 1,
    val routes: List<SavedRoute>, val photos: List<BackupPhoto> = emptyList())

object RouteBackup {
    const val MAX_BYTES = 50 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(context: Context, routes: List<SavedRoute>): String {
        val photos = mutableListOf<BackupPhoto>()
        val visible=routes.filter { it.deletedAt == null }
        visible.forEach { route ->
            val file=PhotoStore.localFile(route.photoPath)
            check(route.photoObjectPath == null || file != null) { "端末にない写真があります。写真を端末に復元してからバックアップしてください" }
            if(file != null) {
                require(file.canonicalPath.startsWith(File(context.filesDir,"walk_photos").canonicalPath+File.separator))
                require(file.length() <= 10 * 1024 * 1024) { "写真が大きすぎます" }
                photos += BackupPhoto(route.id,Base64.encodeToString(file.readBytes(),Base64.NO_WRAP))
            }
        }
        val text=json.encodeToString(SanpoBackup.serializer(),SanpoBackup(routes=visible.map {
            it.copy(ownerId=null,photoPath=null,photoObjectPath=null,syncState="PENDING",syncedPhotoHash=null)
        },photos=photos))
        require(text.toByteArray().size <= MAX_BYTES) { "バックアップが50MBを超えます" }
        return text
    }
    fun decode(text: String): SanpoBackup {
        require(text.toByteArray().size <= MAX_BYTES) { "JSONは50MBまでです" }
        val backup=if(text.trimStart().startsWith("[")) SanpoBackup(routes=json.decodeFromString<List<SavedRoute>>(text))
            else json.decodeFromString<SanpoBackup>(text)
        require(backup.kind == "SANPO-ROAD" && backup.version == 1) { "未対応のSANPOバックアップです" }
        require(backup.routes.size <= 10000 && backup.routes.sumOf { it.points.size.toLong() } <= 500000) { "記録数が多すぎます" }
        require(backup.routes.map { it.id }.distinct().size == backup.routes.size) { "記録IDが重複しています" }
        backup.routes.forEach { r ->
            require(r.id > 0 && r.startedAt > 0 && r.formatVersion <= 2 && r.durationMs in 0..604800000L && r.restDurationMs in 0..604800000L && r.distanceMeters.isFinite() && r.distanceMeters in 0.0..1000000.0) { "不正な記録です" }
            require(r.name.length <= 1000 && r.note.length <= 10000 && r.tags.size <= 20 && r.tags.all { it.length <= 30 })
            require(r.points.all { it.lat.isFinite() && it.lng.isFinite() && it.lat in -90.0..90.0 && it.lng in -180.0..180.0 && it.timeMs >= 0 && (it.accuracyM == null || it.accuracyM.isFinite() && it.accuracyM > 0) }) { "不正なGPS座標です" }
        }
        require(backup.photos.map { it.routeId }.distinct().size == backup.photos.size && backup.photos.all { p -> backup.routes.any { it.id==p.routeId } })
        return backup
    }
    // Never trust the imported flag, owner, file path, or origin IDs supplied by the file.
    fun restored(route: SavedRoute, id: Long): SavedRoute = route.copy(id=id,ownerId=null,
        imported=true,importKey=key(route),photoPath=null,photoObjectPath=null,photoHash=null,
        syncedPhotoHash=null,updatedAt=0,deletedAt=null,syncState="PENDING",sourceRouteId=null)
    fun key(route: SavedRoute): String = route.importKey?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: MessageDigest.getInstance("SHA-256")
        .digest("SANPO:${route.id}:${route.startedAt}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun restore(context: Context, store: RouteStore, text: String): Int {
        val backup=decode(text)
        val created=mutableListOf<File>()
        try {
            val existing=store.getAll()
            val keys=existing.map { key(it) }.toMutableSet()
            val records=backup.routes.mapNotNull { original ->
                if (!keys.add(key(original))) return@mapNotNull null
                var restored=restored(original,java.util.UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE))
                backup.photos.find { it.routeId==original.id }?.let { p ->
                    val bytes=Base64.decode(p.base64,Base64.DEFAULT)
                    require(bytes.size <= 10 * 1024 * 1024) { "写真が大きすぎます" }
                    val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    require(options.outWidth in 1..20000 && options.outHeight in 1..20000) { "不正な写真です" }
                    val file=File(File(context.filesDir,"walk_photos").apply { mkdirs() },"${restored.id}-${java.util.UUID.randomUUID()}.jpg")
                    created += file
                    file.outputStream().use { it.write(bytes); it.fd.sync() }
                    restored=restored.copy(photoPath=file.absolutePath)
                }
                restored
            }
            store.saveImported(records)
            return records.size
        } catch(e: Exception) { created.forEach { it.delete() }; throw e }
    }
}
