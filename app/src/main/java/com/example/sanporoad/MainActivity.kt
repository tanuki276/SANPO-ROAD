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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid must be initialised with an Android context before a MapView is created.
        // Without this, tile cache/network configuration can be incomplete on modern Android.
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "SanpoRoad/1.0 (https://github.com/tanuki276/SANPO-ROAD)"
        }

        setContent { SanpoRoadApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SanpoRoadApp() {
    val context = LocalContext.current
    val store = remember { RouteStore(context) }
    var loggedIn by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var selectedRoute by remember { mutableStateOf<SavedRoute?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("サンポロード") },
                    actions = {
                        TextButton(onClick = { showLogin = true }) {
                            Text(if (loggedIn) "ログアウト" else "ログイン")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (showSaved) {
                    SavedRoutesScreen(store.getAll()) { route ->
                        selectedRoute = route
                        showSaved = false
                    }
                } else {
                    MapRecordScreen(
                        restoredRoute = selectedRoute,
                        onSave = { route -> store.save(route); selectedRoute = route },
                        onShowSaved = { showSaved = true }
                    )
                }
            }
        }
    }

    if (showLogin) AlertDialog(
        onDismissRequest = { showLogin = false },
        title = { Text("アカウント") },
        text = { Text("ゲスト利用できます。ログイン機能はローカルMVPとして実装しています。") },
        confirmButton = {
            TextButton(onClick = { loggedIn = true; showLogin = false }) { Text("ログイン") }
        },
        dismissButton = {
            TextButton(onClick = { loggedIn = false; showLogin = false }) { Text("閉じる") }
        }
    )
}

@Composable
fun MapRecordScreen(restoredRoute: SavedRoute?, onSave: (SavedRoute) -> Unit, onShowSaved: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    val fallbackCenter = remember { GeoPoint(36.2048, 138.2529) }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Avoid the osmdroid default of (0, 0), which is open ocean and can look like a blank map.
            controller.setZoom(6.0)
            controller.setCenter(fallbackCenter)
        }
    }
    var recording by remember { mutableStateOf(LocationTrackingService.isRecording(context)) }
    var points by remember { mutableStateOf(LocationTrackingService.getPoints(context)) }
    var startedAt by remember { mutableLongStateOf(LocationTrackingService.getStartedAt(context)) }
    var distance by remember { mutableDoubleStateOf(LocationTrackingService.getDistanceMeters(context)) }
    var showName by remember { mutableStateOf(LocationTrackingService.hasPendingRecording(context)) }
    var routeName by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<GeoPoint?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true || perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            LocationTrackingService.start(context)
            recording = true
            startedAt = LocationTrackingService.getStartedAt(context)
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        // Compose can install this observer after the Activity is already RESUMED, in which
        // case no ON_RESUME event will be delivered to this observer. Sync the initial state.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }

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

        // If the user has already granted location permission, start the map at their last
        // known location instead of leaving it at the fallback center.
        if (hasFine || hasCoarse) {
            try {
                fused.lastLocation.addOnSuccessListener { location ->
                    location?.let {
                        val p = GeoPoint(it.latitude, it.longitude)
                        current = p
                        mapView.controller.setZoom(16.0)
                        mapView.controller.setCenter(p)
                        mapView.invalidate()
                    }
                }
            } catch (_: SecurityException) {
                // Permission may have been revoked between the check and the request.
            }
        }

        while (true) {
            val stateRecording = LocationTrackingService.isRecording(context)
            val statePoints = LocationTrackingService.getPoints(context)
            recording = stateRecording
            points = statePoints
            startedAt = LocationTrackingService.getStartedAt(context)
            distance = LocationTrackingService.getDistanceMeters(context)
            statePoints.lastOrNull()?.let {
                val p = GeoPoint(it.lat, it.lng)
                current = p
                if (stateRecording) {
                    mapView.controller.animateTo(p)
                }
            }
            if (!stateRecording && LocationTrackingService.hasPendingRecording(context) && !showName) {
                showName = true
            }
            kotlinx.coroutines.delay(1000L)
        }
    }

    val poly = if (recording || restoredRoute == null) points.map { GeoPoint(it.lat, it.lng) }
    else restoredRoute.points.map { GeoPoint(it.lat, it.lng) }

    DisposableEffect(points, restoredRoute, recording, current) {
        mapView.overlays.clear()
        if (poly.size >= 2) mapView.overlays.add(Polyline().apply { setPoints(poly); width = 10f })
        current?.let { p ->
            mapView.overlays.add(Marker(mapView).apply {
                position = p
                title = "現在地"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        mapView.invalidate()
        onDispose { }
    }

    LaunchedEffect(restoredRoute) {
        if (restoredRoute != null && restoredRoute.points.isNotEmpty() && !recording) {
            val last = GeoPoint(restoredRoute.points.last().lat, restoredRoute.points.last().lng)
            current = last
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(last)
        }
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.weight(1f).fillMaxWidth())
        if (poly.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatBox(
                "距離",
                String.format(Locale.JAPAN, "%.2f km", (if (recording) distance else restoredRoute?.distanceMeters ?: 0.0) / 1000.0),
                Modifier.weight(1f)
            )
            StatBox("地点", poly.size.toString(), Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (recording) {
                        LocationTrackingService.stop(context)
                        recording = false
                        showName = true
                        routeName = ""
                    } else {
                        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) {
                            LocationTrackingService.start(context)
                            recording = true
                            restoredRoute?.let { }
                        } else {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (recording) "停止" else "記録開始") }
            OutlinedButton(onClick = onShowSaved, modifier = Modifier.weight(1f)) { Text("保存コース") }
        }
    }

    if (showName) AlertDialog(
        onDismissRequest = { showName = false },
        title = { Text("コースを保存") },
        text = {
            OutlinedTextField(
                value = routeName,
                onValueChange = { routeName = it },
                label = { Text("コース名") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val savedPoints = LocationTrackingService.getPoints(context)
                val savedStartedAt = LocationTrackingService.getStartedAt(context).takeIf { it > 0 } ?: startedAt
                val savedDistance = LocationTrackingService.getDistanceMeters(context)
                if (savedPoints.isNotEmpty()) {
                    val safeName = routeName.ifBlank {
                        "${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(savedStartedAt))}の散歩"
                    }
                    onSave(SavedRoute(System.currentTimeMillis(), safeName, savedStartedAt, System.currentTimeMillis() - savedStartedAt, savedDistance, savedPoints))
                }
                LocationTrackingService.clearPending(context)
                showName = false
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = {
                LocationTrackingService.clearPending(context)
                showName = false
            }) { Text("破棄") }
        }
    )
}

@Composable
fun SavedRoutesScreen(routes: List<SavedRoute>, onSelect: (SavedRoute) -> Unit) {
    if (routes.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("保存したコースはありません")
    } else LazyColumn(Modifier.fillMaxSize()) {
        items(routes) { route ->
            ListItem(
                headlineContent = { Text(route.name) },
                supportingContent = { Text(String.format(Locale.JAPAN, "%.2f km / %d地点", route.distanceMeters / 1000.0, route.points.size)) },
                leadingContent = { Text("🚶") },
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
