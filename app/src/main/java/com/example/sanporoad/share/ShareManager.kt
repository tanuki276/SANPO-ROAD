package com.example.sanporoad
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShareManager(private val context: Context) {
    suspend fun create(route: SavedRoute, map: Boolean, note: Boolean, expiryDays: Int?): String {
        check(route.deletedAt == null) { "削除した散歩は共有できません" }
        val owned = withContext(Dispatchers.IO) { RouteStore(context).claimForShare(route) }
        return SupabaseRouteSync.createShareLink(owned,map,note,expiryDays).getOrThrow()
    }
}
