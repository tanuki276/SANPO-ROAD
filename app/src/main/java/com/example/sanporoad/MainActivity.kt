package com.example.sanporoad

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlin.math.roundToInt

private val SANPO_OSM_TILE_SOURCE = XYTileSource(
    "SanpoRoadOSM-v2",
    0,
    19,
    256,
    ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    "© OpenStreetMap contributors",
    TileSourcePolicy(
        2,
        TileSourcePolicy.FLAG_NO_BULK or
            TileSourcePolicy.FLAG_NO_PREVENTIVE or
            TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "SANPO-ROAD/${BuildConfig.VERSION_NAME} (+https://github.com/tanuki276)"
        }
        setContent { SanpoRoadApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SanpoRoadApp() {
    val context = LocalContext.current
    val store = remember { RouteStore(context) }
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var selectedRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var syncVersion by remember { mutableIntStateOf(0) }
    var syncStatus by remember { mutableStateOf("ローカル保存") }

    val routes = remember(syncVersion) { store.getAll() }
    val gameProgress = remember(syncVersion) { WalkingGame.progress(routes) }

    LaunchedEffect(Unit) {
        syncStatus = "同期中…"
        val result = store.syncAll()
        syncVersion++
        syncStatus = if (result.cloudAvailable) {
            if (result.failedUploads == 0) "ローカル + Supabase" else "ローカル保存（一部同期待ち）"
        } else {
            "ローカル保存（オフライン）"
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("サンポロード")
                            Text(syncStatus, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            if (loggedIn) loggedIn = false else showLogin = true
                        }) {
                            Text(if (loggedIn) "ログアウト" else "ログイン")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                GameProgressCard(gameProgress)

                if (showSaved) {
                    SavedRoutesScreen(routes) { route ->
                        selectedRoute = route
                        showSaved = false
                    }
                } else {
                    MapRecordScreen(
                        restoredRoute = selectedRoute,
                        onClearRestoredRoute = { selectedRoute = null },
                        onSave = { route ->
                            store.save(route)
                            selectedRoute = route
                            syncVersion++
                            scope.launch {
                                syncStatus = "同期中…"
                                val synced = store.syncRoute(route)
                                syncStatus = if (synced) "ローカル + Supabase" else "ローカル保存（同期待ち）"
                            }
                        },
                        onShowSaved = { showSaved = true }
                    )
                }
            }
        }
    }

    if (showLogin) AlertDialog(
        onDismissRequest = { showLogin = false },
        title = { Text("アカウント") },
        text = { Text("散歩記録は端末に保存され、Supabaseへ匿名アカウントで同期されます。") },
        confirmButton = {
            TextButton(onClick = { loggedIn = true; showLogin = false }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = { showLogin = false }) { Text("閉じる") }
        }
    )
}

@Composable
private fun GameProgressCard(progress: GameProgress) {
    val completed = progress.missions.count { it.complete }
    val nextMission = progress.missions.firstOrNull { !it.complete }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Lv.${progress.level}  ✨ ${progress.totalXp} XP", style = MaterialTheme.typography.titleSmall)
                Text("🔥 ${progress.streakDays}日  🏅 ${progress.badges.size}", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = progress.levelFraction,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (nextMission == null) "デイリー達成！ 3/3 🎉"
                else "デイリー $completed/3 ・ 次: ${nextMission.title}",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
fun MapRecordScreen(
    restoredRoute: SavedRoute?,
    onClearRestoredRoute: () -> Unit,
    onSave: (SavedRoute) -> Unit,
    onShowSaved: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    val fallbackCenter = remember { GeoPoint(36.2048, 138.2529) }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(SANPO_OSM_TILE_SOURCE)
            setMultiTouchControls(true)
            controller.setZoom(6.0)
            controller.setCenter(fallbackCenter)
        }
    }
    val copyrightOverlay = remember {
        CopyrightOverlay(context).apply {
            setAlignBottom(true)
            setAlignRight(false)
            setOffset(12, 12)
        }
    }

    var recording by remember { mutableStateOf(LocationTrackingService.isRecording(context)) }
    var points by remember { mutableStateOf(LocationTrackingService.getPoints(context)) }
    var startedAt by remember { mutableLongStateOf(LocationTrackingService.getStartedAt(context)) }
    var distance by remember { mutableDoubleStateOf(LocationTrackingService.getDistanceMeters(context)) }
    var durationMs by remember { mutableLongStateOf(LocationTrackingService.getDurationMs(context)) }
    var gpsAccuracy by remember { mutableDoubleStateOf(LocationTrackingService.getLastAccuracyMeters(context)) }
    var showName by remember { mutableStateOf(LocationTrackingService.hasPendingRecording(context)) }
    var routeName by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<GeoPoint?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true || perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            onClearRestoredRoute()
            LocationTrackingService.start(context)
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            try {
                fused.lastLocation.addOnSuccessListener { location ->
                    location?.let {
                        val p = GeoPoint(it.latitude, it.longitude)
                        current = p
                        if (restoredRoute == null && !LocationTrackingService.isRecording(context)) {
                            mapView.controller.setZoom(16.0)
                            mapView.controller.setCenter(p)
                        }
                        mapView.invalidate()
                    }
                }
            } catch (_: SecurityException) { }
        }

        while (true) {
            val stateRecording = LocationTrackingService.isRecording(context)
            val statePoints = LocationTrackingService.getPoints(context)
            recording = stateRecording
            points = statePoints
            startedAt = LocationTrackingService.getStartedAt(context)
            distance = LocationTrackingService.getDistanceMeters(context)
            durationMs = LocationTrackingService.getDurationMs(context)
            gpsAccuracy = LocationTrackingService.getLastAccuracyMeters(context)
            statePoints.lastOrNull()?.let {
                val p = GeoPoint(it.lat, it.lng)
                current = p
                if (stateRecording) mapView.controller.animateTo(p)
            }
            if (!stateRecording && LocationTrackingService.hasPendingRecording(context)) showName = true
            kotlinx.coroutines.delay(500L)
        }
    }

    val pending = LocationTrackingService.hasPendingRecording(context)
    val showingRecordedSession = recording || pending || restoredRoute == null
    val poly = if (showingRecordedSession) points.map { GeoPoint(it.lat, it.lng) }
    else restoredRoute.points.map { GeoPoint(it.lat, it.lng) }

    DisposableEffect(points, restoredRoute, recording, pending, current, copyrightOverlay) {
        mapView.overlays.clear()
        if (poly.size >= 2) mapView.overlays.add(Polyline().apply { setPoints(poly); width = 10f })
        current?.let { p ->
            mapView.overlays.add(Marker(mapView).apply {
                position = p
                title = "現在地"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        mapView.overlays.add(copyrightOverlay)
        mapView.invalidate()
        onDispose { }
    }

    LaunchedEffect(restoredRoute, recording, pending) {
        if (restoredRoute != null && restoredRoute.points.isNotEmpty() && !recording && !pending) {
            val last = GeoPoint(restoredRoute.points.last().lat, restoredRoute.points.last().lng)
            current = last
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(last)
        }
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.weight(1f).fillMaxWidth())

        if (recording || pending) {
            val gpsText = when {
                gpsAccuracy <= 0 -> "📡 GPS探索中"
                gpsAccuracy <= 10 -> "🟢 GPS良好 ±${gpsAccuracy.roundToInt()}m"
                gpsAccuracy <= 20 -> "🟡 GPS普通 ±${gpsAccuracy.roundToInt()}m"
                else -> "🔴 GPS不安定 ±${gpsAccuracy.roundToInt()}m"
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(gpsText, style = MaterialTheme.typography.labelMedium)
                Text("✨ +${WalkingGame.pendingXp(distance, durationMs)} XP予定", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (poly.isNotEmpty()) {
            val shownDistance = if (showingRecordedSession) distance else restoredRoute?.distanceMeters ?: 0.0
            val shownDuration = if (showingRecordedSession) durationMs else restoredRoute?.durationMs ?: 0L
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatBox("距離", String.format(Locale.JAPAN, "%.2f km", shownDistance / 1000.0), Modifier.weight(1f))
                StatBox("時間", formatDuration(shownDuration), Modifier.weight(1f))
                StatBox("地点", poly.size.toString(), Modifier.weight(1f))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (recording) {
                        LocationTrackingService.stop(context)
                    } else if (!pending) {
                        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) {
                            onClearRestoredRoute()
                            LocationTrackingService.start(context)
                        } else {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !pending
            ) { Text(if (recording) "停止" else if (pending) "保存待ち" else "冒険開始") }

            OutlinedButton(
                onClick = onShowSaved,
                modifier = Modifier.weight(1f),
                enabled = !recording && !pending
            ) { Text("冒険ログ") }
        }
    }

    if (showName && pending) AlertDialog(
        onDismissRequest = { },
        title = { Text("冒険を記録") },
        text = {
            Column {
                Text("獲得予定: ${WalkingGame.pendingXp(distance, durationMs)} XP")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = routeName,
                    onValueChange = { routeName = it },
                    label = { Text("コース名") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val savedPoints = LocationTrackingService.getPoints(context)
                val savedStartedAt = LocationTrackingService.getStartedAt(context).takeIf { it > 0 } ?: startedAt
                val savedDistance = LocationTrackingService.getDistanceMeters(context)
                val savedDuration = LocationTrackingService.getDurationMs(context)
                if (savedPoints.isNotEmpty()) {
                    val safeName = routeName.ifBlank {
                        "${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(savedStartedAt))}の散歩"
                    }
                    onSave(SavedRoute(System.currentTimeMillis(), safeName, savedStartedAt, savedDuration, savedDistance, savedPoints))
                }
                LocationTrackingService.clearPending(context)
                points = emptyList()
                distance = 0.0
                durationMs = 0L
                gpsAccuracy = -1.0
                routeName = ""
                showName = false
            }) { Text("記録してXP獲得") }
        },
        dismissButton = {
            TextButton(onClick = {
                LocationTrackingService.clearPending(context)
                points = emptyList()
                distance = 0.0
                durationMs = 0L
                gpsAccuracy = -1.0
                routeName = ""
                showName = false
            }) { Text("破棄") }
        }
    )
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) String.format(Locale.JAPAN, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.JAPAN, "%02d:%02d", minutes, seconds)
}

@Composable
fun SavedRoutesScreen(routes: List<SavedRoute>, onSelect: (SavedRoute) -> Unit) {
    if (routes.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("冒険ログはまだありません")
    } else LazyColumn(Modifier.fillMaxSize()) {
        items(routes) { route ->
            ListItem(
                headlineContent = { Text(route.name) },
                supportingContent = {
                    Text(
                        String.format(
                            Locale.JAPAN,
                            "%.2f km / %s / +%d XP",
                            route.distanceMeters / 1000.0,
                            formatDuration(route.durationMs),
                            WalkingGame.pendingXp(route.distanceMeters, route.durationMs)
                        )
                    )
                },
                leadingContent = { Text("🗺️") },
                trailingContent = { TextButton(onClick = { onSelect(route) }) { Text("地図") } }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
