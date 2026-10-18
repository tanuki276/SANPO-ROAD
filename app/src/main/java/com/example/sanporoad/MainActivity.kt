package com.example.sanporoad

import android.Manifest
import android.content.pm.PackageManager
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

    var showHistory by remember { mutableStateOf(false) }
    var selectedRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var syncVersion by remember { mutableIntStateOf(0) }
    var syncStatus by remember { mutableStateOf("ローカル保存") }
    var lastSavedRoute by remember { mutableStateOf<SavedRoute?>(null) }

    val routes = remember(syncVersion) { store.getAll() }
    val stats = remember(syncVersion) { WalkStats.calculate(routes) }

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
                        TextButton(onClick = { showHistory = !showHistory }) {
                            Text(if (showHistory) "地図" else "記録")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                WalkSummaryCard(stats)

                if (showHistory) {
                    WalkHistoryScreen(routes) { route ->
                        selectedRoute = route
                        showHistory = false
                    }
                } else {
                    MapRecordScreen(
                        restoredRoute = selectedRoute,
                        onClearRestoredRoute = { selectedRoute = null },
                        onSave = { route ->
                            store.save(route)
                            lastSavedRoute = route
                            selectedRoute = route
                            syncVersion++
                            scope.launch {
                                syncStatus = "同期中…"
                                val synced = store.syncRoute(route)
                                syncStatus = if (synced) "ローカル + Supabase" else "ローカル保存（同期待ち）"
                            }
                        },
                        onShowSaved = { showHistory = true }
                    )
                }
            }
        }
    }

    lastSavedRoute?.let { saved ->
        WalkResultDialog(
            route = saved,
            previousRoutes = routes.filterNot { it.id == saved.id },
            onClose = { lastSavedRoute = null }
        )
    }
}

@Composable
private fun WalkSummaryCard(stats: WalkStatsSnapshot) {
    val weekComparison = when {
        stats.totalWalks == 0 -> "最初の散歩を記録してみよう"
        stats.weekDeltaMeters > 100 -> "先週より ${formatKm(stats.weekDeltaMeters)} 多く歩いています"
        stats.weekDeltaMeters < -100 -> "先週より ${formatKm(-stats.weekDeltaMeters)} 少なめです"
        else -> "先週とほぼ同じペースです"
    }

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("今週", style = MaterialTheme.typography.labelMedium)
                    Text(formatKm(stats.thisWeekDistanceMeters), style = MaterialTheme.typography.headlineSmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${stats.thisWeekWalks}回", style = MaterialTheme.typography.titleMedium)
                    if (stats.currentStreakDays > 0) {
                        Text("${stats.currentStreakDays}日連続", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(weekComparison, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("累計 ${formatKm(stats.totalDistanceMeters)}", style = MaterialTheme.typography.labelLarge)
                Text("最長 ${formatKm(stats.longestDistanceMeters)}", style = MaterialTheme.typography.labelLarge)
                Text("合計 ${stats.totalWalks}回", style = MaterialTheme.typography.labelLarge)
            }
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
    var showSaveDialog by remember { mutableStateOf(LocationTrackingService.hasPendingRecording(context)) }
    var routeName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<GeoPoint?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
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
            if (!stateRecording && LocationTrackingService.hasPendingRecording(context)) showSaveDialog = true
            kotlinx.coroutines.delay(500L)
        }
    }

    val pending = LocationTrackingService.hasPendingRecording(context)
    val showingRecordedSession = recording || pending || restoredRoute == null
    val poly = if (showingRecordedSession) points.map { GeoPoint(it.lat, it.lng) }
    else restoredRoute?.points?.map { GeoPoint(it.lat, it.lng) }.orEmpty()

    DisposableEffect(points, restoredRoute, recording, pending, current, copyrightOverlay) {
        mapView.overlays.clear()
        if (poly.size >= 2) {
            mapView.overlays.add(Polyline().apply {
                setPoints(poly)
                width = 10f
            })
        }
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
                gpsAccuracy <= 0 -> "GPS探索中"
                gpsAccuracy <= 10 -> "GPS良好 ±${gpsAccuracy.roundToInt()}m"
                gpsAccuracy <= 20 -> "GPS普通 ±${gpsAccuracy.roundToInt()}m"
                else -> "GPS不安定 ±${gpsAccuracy.roundToInt()}m"
            }
            Text(
                gpsText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }

        if (poly.isNotEmpty()) {
            val shownDistance = if (showingRecordedSession) distance else restoredRoute?.distanceMeters ?: 0.0
            val shownDuration = if (showingRecordedSession) durationMs else restoredRoute?.durationMs ?: 0L
            val pace = paceText(shownDistance, shownDuration)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatBox("距離", formatKm(shownDistance), Modifier.weight(1f))
                StatBox("時間", formatDuration(shownDuration), Modifier.weight(1f))
                StatBox("ペース", pace, Modifier.weight(1f))
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
            ) { Text(if (recording) "散歩を終了" else if (pending) "保存待ち" else "散歩を開始") }

            OutlinedButton(
                onClick = onShowSaved,
                modifier = Modifier.weight(1f),
                enabled = !recording && !pending
            ) { Text("記録を見る") }
        }
    }

    if (showSaveDialog && pending) AlertDialog(
        onDismissRequest = { },
        title = { Text("散歩を保存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatKm(distance)} ・ ${formatDuration(durationMs)} ・ ${paceText(distance, durationMs)}")
                OutlinedTextField(
                    value = routeName,
                    onValueChange = { routeName = it },
                    label = { Text("散歩の名前") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("ひとことメモ（任意）") },
                    minLines = 2,
                    maxLines = 4
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
                    onSave(
                        SavedRoute(
                            id = System.currentTimeMillis(),
                            name = safeName,
                            startedAt = savedStartedAt,
                            durationMs = savedDuration,
                            distanceMeters = savedDistance,
                            points = savedPoints,
                            note = note.trim()
                        )
                    )
                }
                LocationTrackingService.clearPending(context)
                points = emptyList()
                distance = 0.0
                durationMs = 0L
                gpsAccuracy = -1.0
                routeName = ""
                note = ""
                showSaveDialog = false
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = {
                LocationTrackingService.clearPending(context)
                points = emptyList()
                distance = 0.0
                durationMs = 0L
                gpsAccuracy = -1.0
                routeName = ""
                note = ""
                showSaveDialog = false
            }) { Text("破棄") }
        }
    )
}

@Composable
private fun WalkResultDialog(
    route: SavedRoute,
    previousRoutes: List<SavedRoute>,
    onClose: () -> Unit
) {
    val previousLongest = previousRoutes.maxOfOrNull { it.distanceMeters } ?: 0.0
    val isDistanceBest = route.distanceMeters > previousLongest && previousLongest > 0.0

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (isDistanceBest) "自己ベスト！" else "散歩を記録しました") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(route.name, style = MaterialTheme.typography.titleMedium)
                Text("${formatKm(route.distanceMeters)} ・ ${formatDuration(route.durationMs)}")
                Text("平均ペース ${paceText(route.distanceMeters, route.durationMs)}")
                if (route.note.isNotBlank()) {
                    HorizontalDivider()
                    Text(route.note)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } }
    )
}

@Composable
fun WalkHistoryScreen(routes: List<SavedRoute>, onSelect: (SavedRoute) -> Unit) {
    if (routes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("まだ散歩記録がありません")
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(routes) { route ->
                ListItem(
                    headlineContent = { Text(route.name) },
                    supportingContent = {
                        Column {
                            Text("${formatKm(route.distanceMeters)} / ${formatDuration(route.durationMs)} / ${paceText(route.distanceMeters, route.durationMs)}")
                            if (route.note.isNotBlank()) Text(route.note, maxLines = 1)
                        }
                    },
                    leadingContent = { Text("🚶") },
                    trailingContent = { TextButton(onClick = { onSelect(route) }) { Text("地図") } }
                )
                HorizontalDivider()
            }
        }
    }
}

private fun formatKm(distanceMeters: Double): String =
    String.format(Locale.JAPAN, "%.2f km", distanceMeters.coerceAtLeast(0.0) / 1000.0)

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) String.format(Locale.JAPAN, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.JAPAN, "%02d:%02d", minutes, seconds)
}

private fun paceText(distanceMeters: Double, durationMs: Long): String {
    if (distanceMeters < 50.0 || durationMs <= 0L) return "--"
    val minPerKm = (durationMs / 60_000.0) / (distanceMeters / 1000.0)
    if (!minPerKm.isFinite() || minPerKm <= 0.0 || minPerKm > 99.0) return "--"
    val minutes = minPerKm.toInt()
    val seconds = ((minPerKm - minutes) * 60).roundToInt().coerceIn(0, 59)
    return String.format(Locale.JAPAN, "%d:%02d/km", minutes, seconds)
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
