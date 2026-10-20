package com.example.sanporoad

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun WalkHistoryScreen(routes: List<SavedRoute>, onStartWalk: () -> Unit, onEdit: (SavedRoute) -> Unit, onShare: (SavedRoute) -> Unit, onCompare: (SavedRoute) -> Unit, onRepeat: (SavedRoute) -> Unit, onFavorite: (SavedRoute) -> Unit, onSelect: (SavedRoute) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(HistorySort.NEWEST) }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    var longOnly by rememberSaveable { mutableStateOf(false) }
    var photosOnly by rememberSaveable { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val visibleRoutes = remember(routes, query, sort, recentOnly, longOnly, photosOnly, favoritesOnly) {
        filterWalkHistory(routes, query, sort, HistoryFilter(
            since = if (recentOnly) System.currentTimeMillis() - 30L * 86400000 else 0L,
            minimumMeters = if (longOnly) 3000.0 else 0.0,
            photosOnly = photosOnly, favoritesOnly = favoritesOnly
        ))
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("散歩の記録", style = MaterialTheme.typography.headlineSmall)
            Text("${routes.size}回の思い出", style = MaterialTheme.typography.bodyMedium)
        }
        if (routes.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("最初の一歩を残そう", style = MaterialTheme.typography.titleLarge)
                        Text("散歩を終了して保存すると、歩いた道や写真、メモをここから振り返れます。")
                        Button(onClick = onStartWalk) { Text("散歩の画面へ") }
                    }
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("名前・メモで検索") },
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("クリア") } }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistorySort.entries.forEach { option ->
                        FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.label) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(recentOnly, { recentOnly = !recentOnly }, label = { Text("30日以内") })
                    FilterChip(longOnly, { longOnly = !longOnly }, label = { Text("3km以上") })
                    FilterChip(photosOnly, { photosOnly = !photosOnly }, label = { Text("写真あり") })
                    FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, label = { Text("お気に入り") })
                }
                Text("${visibleRoutes.size}件", style = MaterialTheme.typography.labelMedium)
            }
            if (visibleRoutes.isEmpty()) item {
                Text("一致する記録がありません。別の名前やメモで検索してみてください。", Modifier.padding(vertical = 24.dp))
            }
            items(visibleRoutes, key = { "${it.ownerId}:${it.id}" }) { route ->
                val comparison = remember(route, routes) { WalkInsights.compareWithPreviousSimilar(route, routes) }
                Card(onClick = { onSelect(route) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(SimpleDateFormat("yyyy年M月d日（E） HH:mm", Locale.JAPAN).format(Date(route.startedAt)), style = MaterialTheme.typography.labelMedium)
                        Text(route.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        RoutePhoto(route, Modifier.fillMaxWidth().height(140.dp))
                        Text("${formatKm(route.distanceMeters)} / ${timeSummary(route.durationMs, route.restDurationMs)}")
                        if (route.tags.isNotEmpty()) Text(route.tags.joinToString(" / "))
                        if (route.imported) Text("インポート記録・自己記録対象外")
                        if (route.sourceRouteId != null) Text("再走元 #${route.sourceRouteId}", style = MaterialTheme.typography.labelSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { onRepeat(route) }) { Icon(Icons.Default.DirectionsWalk,null,Modifier.size(20.dp)); Text("もう一度歩く") }
                            TextButton(onClick = { onCompare(route) }) { Icon(Icons.Default.CompareArrows,null,Modifier.size(20.dp)); Text("比較") }
                            TextButton(onClick = { onShare(route) }) { Icon(Icons.Default.Share,null,Modifier.size(20.dp)); Text("共有") }
                            val scale by animateFloatAsState(if (route.favorite) 1.1f else 1f, tween(180), label = "favorite")
                            IconButton(onClick = { onFavorite(route) }) { Icon(Icons.Default.Favorite, "お気に入り ${if(route.favorite) "ON" else "OFF"}", Modifier.size(24.dp).scale(scale), tint = if(route.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                        }
                        comparison?.let { Text("前回比 ${comparisonCompactText(it)}", style = MaterialTheme.typography.bodySmall) }
                        if (route.note.isNotBlank()) Text(route.note, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { onSelect(route) }) { Icon(Icons.Default.Route, null, Modifier.size(20.dp)); Text("地図") }
                            TextButton(onClick = { onEdit(route) }) { Icon(Icons.Default.Edit, null, Modifier.size(20.dp)); Text("編集・削除") }
                        }
                    }
                }
            }
        }
    }
}


