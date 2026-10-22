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

@Composable
fun MapRecordScreen(
    restoredRoute: SavedRoute?,
    guideRoute: SavedRoute?,
    onShare: (SavedRoute) -> Unit,
    onCompare: (SavedRoute) -> Unit,
    onRepeat: (SavedRoute) -> Unit,
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
    var paused by remember { mutableStateOf(LocationTrackingService.isPaused(context)) }
    var points by remember { mutableStateOf(LocationTrackingService.getPoints(context)) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { LiveTrackStore.load(context) } } }

    var startedAt by remember { mutableLongStateOf(LocationTrackingService.getStartedAt(context)) }
    var distance by remember { mutableDoubleStateOf(LocationTrackingService.getDistanceMeters(context)) }
    var restMs by remember { mutableLongStateOf(0L) }
    var tags by rememberSaveable { mutableStateOf("") }
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
    var showDetails by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var locationProblem by remember { mutableStateOf<String?>(null) }
    var followLocation by rememberSaveable { mutableStateOf(true) }
    var hasCenteredOnCurrentLocation by remember { mutableStateOf(false) }
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
            LocationTrackingService.start(context, guideRoute?.id)
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

    var liveFix by remember { mutableStateOf<android.location.Location?>(null) }
    var gpsMode by remember { mutableStateOf(GpsSettings(context).mode) }
    var permissionGranted by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, permissionGranted, gpsMode) {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val now = SystemClock.elapsedRealtimeNanos()
                result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach { loc ->
                    if (isFreshFix(now,loc.elapsedRealtimeNanos) && loc.hasAccuracy() && loc.accuracy.isFinite() &&
                        loc.accuracy > 0 && loc.latitude.isFinite() && loc.longitude.isFinite() &&
                        loc.latitude in -90.0..90.0 && loc.longitude in -180.0..180.0 &&
                        loc.elapsedRealtimeNanos > (liveFix?.elapsedRealtimeNanos ?: 0)) liveFix = loc
                }
            }
        }
        fun update() {
            fused.removeLocationUpdates(callback)
            if (permissionGranted && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                try { fused.requestLocationUpdates(GpsSettings(context).request(), callback, android.os.Looper.getMainLooper()) }
                catch (_: SecurityException) { liveFix = null }
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycleOwner.lifecycle.addObserver(observer); update()
        onDispose { fused.removeLocationUpdates(callback); lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val stateRecording = LocationTrackingService.isRecording(context)
            val statePoints = LocationTrackingService.getPoints(context)
            recording = stateRecording
            paused = LocationTrackingService.isPaused(context)
            points = statePoints
            startedAt = LocationTrackingService.getStartedAt(context)
            distance = LocationTrackingService.getDistanceMeters(context)
            durationMs = LocationTrackingService.getDurationMs(context)
            restMs = LocationTrackingService.getRestDurationMs(context)
            permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            gpsMode = GpsSettings(context).mode
            val fix = liveFix?.takeIf { isFreshFix(SystemClock.elapsedRealtimeNanos(), it.elapsedRealtimeNanos) }
            gpsAccuracy = fix?.accuracy?.toDouble() ?: -1.0
            current = fix?.takeIf { it.accuracy <= GpsSettings(context).mode.filter.maximumAccuracyM }?.let { GeoPoint(it.latitude,it.longitude) }
            current?.let {
                if (followLocation && restoredRoute == null) {
                    if (!hasCenteredOnCurrentLocation) {
                        mapView.controller.setZoom(18.0)
                        hasCenteredOnCurrentLocation = true
                    }
                    mapView.controller.animateTo(it)
                }
            }
            LiveTrackStore.error?.let { saveError = it }
            if (!stateRecording && LocationTrackingService.hasPendingRecording(context)) showSaveDialog = true
            kotlinx.coroutines.delay(500L)
        }
    }

    val pending = LocationTrackingService.hasPendingRecording(context)
    val showingRecordedSession = recording || pending || restoredRoute == null
    val poly = if (showingRecordedSession) points.map { GeoPoint(it.lat, it.lng) }
    else restoredRoute?.points?.map { GeoPoint(it.lat, it.lng) }.orEmpty()

    DisposableEffect(points, restoredRoute, guideRoute, recording, pending, current, copyrightOverlay) {
        mapView.overlays.clear()
        guideRoute?.points?.groupBy { it.segment }?.values?.forEach { segment ->
            mapView.overlays.add(Polyline().apply { setPoints(segment.map { GeoPoint(it.lat,it.lng) }); width = 6f; color = android.graphics.Color.argb(85,70,100,150) })
        }
        val shownPoints = if (showingRecordedSession) points else restoredRoute?.points.orEmpty()
        shownPoints.groupBy { it.segment }.values.forEach { segment ->
            if (segment.size >= 2) mapView.overlays.add(Polyline().apply {
                setPoints(segment.map { GeoPoint(it.lat, it.lng) })
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
        guideRoute?.let { guide ->
            Text("ガイド：${guide.name}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
            current?.let { position ->
                if (RouteMatching.distanceToRoute(LatLngPoint(position.latitude,position.longitude,0), guide.points) > 60.0)
                    Text("ガイドルートから60m以上離れています", Modifier.padding(horizontal = 16.dp))
            }
        }
        if (restoredRoute != null && !recording && !pending) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保存した散歩", style = MaterialTheme.typography.labelSmall)
                    Text(restoredRoute.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onShare(restoredRoute) }) { Icon(Icons.Default.Share, "共有") }
                IconButton(onClick = { onCompare(restoredRoute) }) { Icon(Icons.Default.CompareArrows, "比較") }
                IconButton(onClick = { onRepeat(restoredRoute) }) { Icon(Icons.Default.DirectionsWalk, "このルートをもう一度歩く") }
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
                }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp)); Text("現在地") }
            }
        }

        run {
            val gpsText = when {
                paused -> "一時停止中・時間と距離の計測を止めています"
                gpsAccuracy <= 0 -> "GPS探索中"
                gpsAccuracy <= 10 -> "GPS良好 ±${gpsAccuracy.roundToInt()}m"
                gpsAccuracy <= 20 -> "GPS普通 ±${gpsAccuracy.roundToInt()}m"
                else -> "GPS不安定 ±${gpsAccuracy.roundToInt()}m"
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                val pulse = rememberInfiniteTransition(label = "gps")
                val opacity by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "gpsOpacity")
                Icon(if (gpsAccuracy < 0) Icons.Default.GpsNotFixed else if (gpsAccuracy > 25) Icons.Default.WarningAmber else Icons.Default.GpsFixed,
                    contentDescription = null, modifier = Modifier.size(24.dp).alpha(if (gpsAccuracy < 0) opacity else 1f))
                Text(gpsText, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
                if (gpsMode == GpsMode.HIGH_ACCURACY) { Icon(Icons.Default.GpsFixed, null, Modifier.size(20.dp)); Text("高精度モード", style = MaterialTheme.typography.labelSmall) }
            }
            saveError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            /*Text(
                gpsText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium
            )*/
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
                StatBox("移動", formatDuration(shownDuration), Modifier.weight(1f))
                StatBox("ペース", pace, Modifier.weight(1f))
            }
        }

        if (showingRecordedSession && startedAt > 0) {
            HealthRoutePanel(SavedRoute(startedAt, "散歩中", startedAt, durationMs, distance, emptyList(), restDurationMs = restMs),
                live = recording, modifier = Modifier.padding(horizontal = 16.dp))
        } else restoredRoute?.let { HealthRoutePanel(it, modifier = Modifier.padding(horizontal = 16.dp)) }

        Text(timeSummary(if (showingRecordedSession) durationMs else restoredRoute?.durationMs ?: 0,
            if (showingRecordedSession) restMs else restoredRoute?.restDurationMs ?: 0), Modifier.padding(horizontal=16.dp))
        if (!showingRecordedSession) restoredRoute?.let { saved ->
            TextButton(onClick={showDetails=true}) { Text("GPS品質・Health Connect") }
            if(showDetails) AlertDialog(onDismissRequest={showDetails=false},title={Text(saved.name)},
                text={Column(Modifier.verticalScroll(rememberScrollState())) { GpsQualityView(saved.gpsQuality); HealthWriteRetry(saved) }},
                confirmButton={TextButton(onClick={showDetails=false}) { Text("閉じる") }})
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
                onClick = { if (recording) LocationTrackingService.togglePause(context) else onShowSaved() },
                modifier = Modifier.weight(1f),
                enabled = !pending
            ) { Text(if (recording) { if (paused) "再開" else "一時停止" } else "記録を見る") }
        }
    }

    if (showSaveDialog && pending) AlertDialog(
        onDismissRequest = { },
        title = { Text("散歩を保存") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (points.isEmpty()) Text("位置情報を取得できなかったため保存できません。屋外など空の開けた場所でお試しください。")
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("${formatKm(distance)} / ${timeSummary(durationMs, restMs)}")
                TagEditor(tags, { tags = it }, !saving)
                GpsQualityView(LocationTrackingService.getGpsQuality(context))
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
                        val routeId = LocationTrackingService.getSessionId(context).takeIf { it > 0 } ?: savedStartedAt
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
                            photoPath = photoPath,
                            sourceRouteId = LocationTrackingService.getSourceRouteId(context),
                            restDurationMs = LocationTrackingService.getRestDurationMs(context),
                            tags = parseWalkTags(tags),
                            gpsQuality = LocationTrackingService.getGpsQuality(context),
                            restIntervals = LocationTrackingService.getRestIntervals(context)
                        ))
                        withContext(Dispatchers.IO) { LocationTrackingService.clearPending(context) }
                        points = emptyList()
                        distance = 0.0
                        durationMs = 0L
                        routeName = ""
                        tags = ""
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
        confirmButton = { TextButton(onClick = { scope.launch {
            withContext(Dispatchers.IO) { LocationTrackingService.clearPending(context) }
            points = emptyList()
            distance = 0.0
            durationMs = 0L
            gpsAccuracy = -1.0
            routeName = ""
            tags = ""
            note = ""
            photoUriText = null
            saveError = null
            showSaveDialog = false
            confirmDiscard = false
        } }) { Text("破棄する", color = MaterialTheme.colorScheme.error) } },
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


