package com.example.sanporoad

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
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

private enum class AppScreen { MAP, HISTORY, INSIGHTS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "SANPO-ROAD/${BuildConfig.VERSION_NAME} (+https://github.com/tanuki276)"
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(
                primary = Color(0xFF91D5B1), secondary = Color(0xFFB4CCBB)
            ) else lightColorScheme(
                primary = Color(0xFF246B4B), secondary = Color(0xFF506455),
                primaryContainer = Color(0xFFB8F1CE), background = Color(0xFFF7FAF5)
            )) { SanpoRoadApp() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SanpoRoadApp() {
    val context = LocalContext.current
    val store = remember { RouteStore(context) }
    val preferences = remember { WalkPreferences(context) }

    var screen by rememberSaveable { mutableStateOf(AppScreen.MAP) }
    var selectedRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var syncVersion by remember { mutableIntStateOf(0) }
    var syncStatus by remember { mutableStateOf("端末に保存") }
    var syncing by remember { mutableStateOf(false) }
    var syncRequest by remember { mutableIntStateOf(0) }
    var lastSavedRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var weeklyGoalMeters by remember { mutableDoubleStateOf(preferences.weeklyGoalMeters) }

    val routes = remember(syncVersion) { store.getAll() }

    BackHandler(enabled = screen != AppScreen.MAP) { screen = AppScreen.MAP }

    LaunchedEffect(syncRequest) {
        syncing = true
        syncStatus = "記録を同期中…"
        try {
            val result = withContext(Dispatchers.IO) { store.syncAll() }
            syncVersion++
            syncStatus = if (result.cloudAvailable && result.failedUploads == 0) {
                "記録は同期済み"
            } else {
                "端末に保存済み・同期待ち"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            syncStatus = "端末に保存済み・同期できませんでした"
        } finally {
            syncing = false
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
                        IconButton(onClick = { syncRequest++ }, enabled = !syncing) {
                            if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, contentDescription = "記録を再同期")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf(
                        Triple(AppScreen.MAP, "散歩", Icons.Default.Home),
                        Triple(AppScreen.HISTORY, "記録", Icons.Default.List),
                        Triple(AppScreen.INSIGHTS, "振り返り", Icons.Default.DateRange)
                    ).forEach { (destination, label, icon) ->
                        NavigationBarItem(
                            selected = screen == destination,
                            onClick = { screen = destination },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                // Keep map space available on small screens; the full summary lives in Insights.

                when (screen) {
                    AppScreen.HISTORY -> {
                        WalkHistoryScreen(routes, onStartWalk = { screen = AppScreen.MAP }) { route ->
                            selectedRoute = route
                            screen = AppScreen.MAP
                        }
                    }
                    AppScreen.INSIGHTS -> {
                        WalkInsightsScreen(
                            routes = routes,
                            weeklyGoalMeters = weeklyGoalMeters,
                            onWeeklyGoalChange = { meters ->
                                preferences.weeklyGoalMeters = meters
                                weeklyGoalMeters = meters
                            }
                        )
                    }
                    AppScreen.MAP -> {
                        MapRecordScreen(
                            restoredRoute = selectedRoute,
                            onClearRestoredRoute = { selectedRoute = null },
                            onSave = { route ->
                                withContext(Dispatchers.IO) { store.save(route) }
                                lastSavedRoute = route
                                selectedRoute = route
                                syncVersion++
                                syncRequest++
                            },
                            onShowSaved = { screen = AppScreen.HISTORY }
                        )
                    }
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
fun MapRecordScreen(
    restoredRoute: SavedRoute?,
    onClearRestoredRoute: () -> Unit,
    onSave: suspend (SavedRoute) -> Unit,
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
    var routeName by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var photoUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val photoUri = photoUriText?.let(Uri::parse)
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var locationProblem by remember { mutableStateOf<String?>(null) }
    var followLocation by rememberSaveable { mutableStateOf(true) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification denial does not prevent foreground recording. */ }

    fun beginRecording() {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(manager)) {
            locationProblem = "端末の位置情報がオフです。設定で位置情報をオンにしてください。"
            return
        }
        try {
            onClearRestoredRoute()
            followLocation = true
            LocationTrackingService.start(context)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } catch (_: SecurityException) {
            locationProblem = "記録を開始できませんでした。位置情報の権限を確認してください。"
        } catch (_: IllegalStateException) {
            locationProblem = "記録を開始できませんでした。アプリを開いた状態でもう一度お試しください。"
        }
    }
    var current by remember { mutableStateOf<GeoPoint?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) photoUriText = uri.toString()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            beginRecording()
        } else {
            locationProblem = "散歩の道筋を記録するには「正確な位置情報」の許可が必要です。アプリの設定で許可してください。"
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
                if (stateRecording && followLocation) mapView.controller.animateTo(p)
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
        current?.takeIf { restoredRoute == null || recording || pending }?.let { p ->
            mapView.overlays.add(Marker(mapView).apply {
                position = p
                title = "現在地"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        if (poly.isNotEmpty()) {
            mapView.overlays.add(Marker(mapView).apply {
                position = poly.first()
                title = "スタート"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
            if (!recording && poly.size > 1) mapView.overlays.add(Marker(mapView).apply {
                position = poly.last()
                title = "ゴール"
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
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(last)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (restoredRoute != null && !recording && !pending) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保存した散歩", style = MaterialTheme.typography.labelSmall)
                    Text(restoredRoute.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onClearRestoredRoute) { Text("閉じる") }
            }
        } else if (!recording && !pending) {
            Text("散歩を開始すると、歩いた道を記録します。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (recording) {
                FilledTonalButton(
                    onClick = { followLocation = !followLocation },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) { Text(if (followLocation) "追従中・解除" else "現在地を追従") }
            } else if (current != null && restoredRoute == null) {
                FilledTonalButton(onClick = {
                    current?.let { mapView.controller.setZoom(16.0); mapView.controller.animateTo(it) }
                }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Text("現在地") }
            }
        }

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

        if (poly.isNotEmpty() || recording || pending) {
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
                        if (fine) {
                            beginRecording()
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (points.isEmpty()) Text("位置情報を取得できなかったため保存できません。屋外など空の開けた場所でお試しください。")
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("${formatKm(distance)} ・ ${formatDuration(durationMs)} ・ ${paceText(distance, durationMs)}")
                OutlinedTextField(
                    value = routeName,
                    onValueChange = { routeName = it.take(80) },
                    enabled = !saving,
                    label = { Text("散歩の名前") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(1000) },
                    enabled = !saving,
                    label = { Text("ひとことメモ（任意）") },
                    minLines = 2,
                    maxLines = 4
                )
                OutlinedButton(onClick = { photoPicker.launch("image/*") }, enabled = !saving) {
                    Text(if (photoUri == null) "写真を添える" else "写真を変更")
                }
                if (photoUri != null) TextButton(onClick = { photoUriText = null }, enabled = !saving) { Text("写真を外す") }
                photoUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "選択した散歩写真",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(140.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && points.isNotEmpty(), onClick = {
                saving = true
                saveError = null
                scope.launch {
                    try {
                        val savedPoints = LocationTrackingService.getPoints(context)
                        check(savedPoints.isNotEmpty())
                        val savedStartedAt = LocationTrackingService.getStartedAt(context).takeIf { it > 0 } ?: startedAt
                        // A stable ID makes retrying a failed save idempotent.
                        val routeId = savedStartedAt
                        val photoPath = withContext(Dispatchers.IO) {
                            photoUri?.let { PhotoStore.copyIntoAppStorage(context, it, routeId)
                                ?: error("photo_copy_failed") }
                        }
                        onSave(SavedRoute(
                            id = routeId,
                            name = routeName.trim().ifBlank {
                                "${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(savedStartedAt))}の散歩"
                            },
                            startedAt = savedStartedAt,
                            durationMs = LocationTrackingService.getDurationMs(context),
                            distanceMeters = LocationTrackingService.getDistanceMeters(context),
                            points = savedPoints,
                            note = note.trim(),
                            photoPath = photoPath
                        ))
                        LocationTrackingService.clearPending(context)
                        points = emptyList()
                        distance = 0.0
                        durationMs = 0L
                        routeName = ""
                        note = ""
                        photoUriText = null
                        showSaveDialog = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        saveError = "保存できませんでした。空き容量や写真を確認して再試行してください。散歩の記録は保持しています。"
                    } finally {
                        saving = false
                    }
                }
            }) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = { confirmDiscard = true }) { Text("破棄") }
        }
    )

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("この散歩を破棄しますか？") },
        text = { Text("距離・時間・歩いた道の記録が削除されます。この操作は取り消せません。") },
        confirmButton = { TextButton(onClick = {
            LocationTrackingService.clearPending(context)
            points = emptyList()
            distance = 0.0
            durationMs = 0L
            gpsAccuracy = -1.0
            routeName = ""
            note = ""
            photoUriText = null
            saveError = null
            showSaveDialog = false
            confirmDiscard = false
        }) { Text("破棄する", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("保存に戻る") } }
    )

    locationProblem?.let { message ->
        AlertDialog(
            onDismissRequest = { locationProblem = null },
            title = { Text("位置情報を確認してください") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = {
                locationProblem = null
                val manager = context.getSystemService(LocationManager::class.java)
                val intent = if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(manager)) {
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }) { Text("設定を開く") } },
            dismissButton = { TextButton(onClick = { locationProblem = null }) { Text("あとで") } }
        )
    }

}

@Composable
private fun WalkResultDialog(
    route: SavedRoute,
    previousRoutes: List<SavedRoute>,
    onClose: () -> Unit
) {
    val previousLongest = previousRoutes.maxOfOrNull { it.distanceMeters } ?: 0.0
    val isDistanceBest = route.distanceMeters > previousLongest && previousLongest > 0.0
    val comparison = remember(route.id, previousRoutes.size) {
        WalkInsights.compareWithPreviousSimilar(route, previousRoutes)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (isDistanceBest) "自己ベスト！" else "散歩を記録しました") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutePhoto(route, Modifier.fillMaxWidth().height(150.dp))
                Text(route.name, style = MaterialTheme.typography.titleMedium)
                Text("${formatKm(route.distanceMeters)} ・ ${formatDuration(route.durationMs)}")
                Text("平均ペース ${paceText(route.distanceMeters, route.durationMs)}")
                comparison?.let {
                    HorizontalDivider()
                    Text("前回の似たコースと比較", style = MaterialTheme.typography.labelLarge)
                    Text(comparisonText(it))
                }
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
fun WalkHistoryScreen(routes: List<SavedRoute>, onStartWalk: () -> Unit, onSelect: (SavedRoute) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(HistorySort.NEWEST) }
    val visibleRoutes = remember(routes, query, sort) { filterWalkHistory(routes, query, sort) }
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
                Text("${visibleRoutes.size}件", style = MaterialTheme.typography.labelMedium)
            }
            if (visibleRoutes.isEmpty()) item {
                Text("一致する記録がありません。別の名前やメモで検索してみてください。", Modifier.padding(vertical = 24.dp))
            }
            items(visibleRoutes, key = { it.id }) { route ->
                val comparison = remember(route, routes) { WalkInsights.compareWithPreviousSimilar(route, routes) }
                Card(onClick = { onSelect(route) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(SimpleDateFormat("yyyy年M月d日（E） HH:mm", Locale.JAPAN).format(Date(route.startedAt)), style = MaterialTheme.typography.labelMedium)
                        Text(route.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        RoutePhoto(route, Modifier.fillMaxWidth().height(140.dp))
                        Text("${formatKm(route.distanceMeters)} ・ ${formatDuration(route.durationMs)} ・ ${paceText(route.distanceMeters, route.durationMs)}")
                        comparison?.let { Text("前回比 ${comparisonCompactText(it)}", style = MaterialTheme.typography.bodySmall) }
                        if (route.note.isNotBlank()) Text(route.note, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("地図で見る →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutePhoto(route: SavedRoute, modifier: Modifier = Modifier) {
    val local = remember(route.photoPath) { PhotoStore.localFile(route.photoPath) }
    var remoteUrl by remember(route.photoObjectPath) { mutableStateOf<String?>(null) }

    LaunchedEffect(route.photoObjectPath, local) {
        if (local == null && route.photoObjectPath != null) {
            remoteUrl = SupabaseRouteSync.signedPhotoUrl(route.photoObjectPath)
        }
    }

    val model: Any? = local ?: remoteUrl
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = "散歩の写真",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

private fun comparisonText(comparison: RouteComparison): String {
    val distance = signedKm(comparison.distanceDeltaMeters)
    val duration = signedDuration(comparison.durationDeltaMs)
    val pace = comparison.paceDeltaSecondsPerKm?.let { delta ->
        when {
            abs(delta) < 2.0 -> "ペースはほぼ同じ"
            delta < 0 -> "ペース ${formatPaceDelta(-delta)}速い"
            else -> "ペース ${formatPaceDelta(delta)}ゆっくり"
        }
    } ?: "ペース比較なし"
    return "距離 $distance ・ 時間 $duration ・ $pace"
}

private fun comparisonCompactText(comparison: RouteComparison): String {
    val pace = comparison.paceDeltaSecondsPerKm
    return when {
        pace == null -> signedDuration(comparison.durationDeltaMs)
        abs(pace) < 2.0 -> "ほぼ同じペース"
        pace < 0 -> "${formatPaceDelta(-pace)}速い"
        else -> "${formatPaceDelta(pace)}ゆっくり"
    }
}

private fun signedKm(meters: Double): String {
    val sign = if (meters >= 0) "+" else "-"
    return "$sign${String.format(Locale.JAPAN, "%.2f km", abs(meters) / 1000.0)}"
}

private fun signedDuration(ms: Long): String {
    val sign = if (ms >= 0) "+" else "-"
    val seconds = abs(ms) / 1000L
    return "$sign${seconds / 60}:${String.format(Locale.JAPAN, "%02d", seconds % 60)}"
}

private fun formatPaceDelta(seconds: Double): String {
    val sec = seconds.roundToInt().coerceAtLeast(0)
    return "${sec / 60}:${String.format(Locale.JAPAN, "%02d", sec % 60)}/km "
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

