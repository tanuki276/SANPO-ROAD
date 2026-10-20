package com.example.sanporoad

/** Account + local ID is the identity. Tombstones always win over stale devices. */
fun mergeRoute(local: SavedRoute?, remote: SavedRoute): SavedRoute {
    if (local == null) return remote
    require(local.id == remote.id && local.ownerId == remote.ownerId)
    val winner = when {
        local.deletedAt != null -> local
        remote.deletedAt != null -> remote
        local.updatedAt >= remote.updatedAt -> local
        else -> remote
    }
    return if (winner.deletedAt != null) winner.copy(
        name = "削除済み", points = emptyList(), note = "", photoPath = null,
        photoObjectPath = null, distanceMeters = 0.0, durationMs = 0L
    ) else winner.copy(
        photoPath = if (winner.photoRemoved) null else local.photoPath,
        photoHash = local.photoHash, syncedPhotoHash = local.syncedPhotoHash,
        sourceRouteId = winner.sourceRouteId ?: local.sourceRouteId,
        photoObjectPath = if (winner.photoRemoved) null else winner.photoObjectPath ?: remote.photoObjectPath
    )
}

