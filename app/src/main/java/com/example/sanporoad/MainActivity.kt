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
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SanpoRoadApp() } }
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
        Scaffold(topBar = { TopAppBar(title = { Text("サンポロード") }, actions = { TextButton(onClick = { showLogin = true }) { Text(if (loggedIn) "ログアウト" else "ログイン") } }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (showSaved) SavedRoutesScreen(store.getAll()) { selectedRoute = it; showSaved = false }
                else MapRecordScreen(selectedRoute, { route -> store.save(route); selectedRoute = route }, { showSaved = true })
            }
        }
    }
    if (showLogin) AlertDialog(onDismissRequest = { showLogin = false }, title = { Text("アカウント") }, text = { Text("ゲスト利用できます。ログイン機能はローカルMVPとして実装しています。") }, confirmButton = { TextButton(onClick = { loggedIn = true; showLogin = false }) { Text("ログイン") } }, dismissButton = { TextButton(onClick = { loggedIn = false; showLogin = false }) { Text("閉じる") } })
}

@Composable
fun MapRecordScreen(restoredRoute: SavedRoute?, onSave: (SavedRoute) -> Unit, onShowSaved: () -> Unit) {
    val context = LocalContext.current
    val fused = remember { LocationServices.getFusedLocationProviderClient(context) }
    var recording by remember { mutableStateOf(false) }
    var points by remember { mutableStateOf(listOf<LatLngPoint>()) }
    var startedAt by remember { mutableStateOf(0L) }
    var lastPoint by remember { mutableStateOf<LatLng?>(null) }
    var current by remember { mutableStateOf<LatLng?>(null) }
    var distance by remember { mutableDoubleStateOf(0.0) }
    var showName by remember { mutableStateOf(false) }
    var routeName by remember { mutableStateOf("") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms -> if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true || perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) { startedAt = System.currentTimeMillis(); recording = true } }
    val callback = rememberUpdatedState(object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val p = LatLng(loc.latitude, loc.longitude)
            current = p
            points = points + LatLngPoint(p.latitude, p.longitude, System.currentTimeMillis())
            lastPoint?.let { distance += haversine(it, p) }
            lastPoint = p
        }
    })
    DisposableEffect(recording) {
        if (recording) {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).setMinUpdateIntervalMillis(1000L).build()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) fused.requestLocationUpdates(req, callback.value, context.mainLooper)
        }
        onDispose { fused.removeLocationUpdates(callback.value) }
    }
    LaunchedEffect(restoredRoute) { if (restoredRoute != null && restoredRoute.points.isNotEmpty()) current = LatLng(restoredRoute.points.last().lat, restoredRoute.points.last().lng) }
    val poly = if (recording || restoredRoute == null) points.map { LatLng(it.lat, it.lng) } else restoredRoute.points.map { LatLng(it.lat, it.lng) }
    val camera = rememberCameraPositionState()
    val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = camera, properties = MapProperties(isMyLocationEnabled = hasLocationPermission), uiSettings = MapUiSettings(myLocationButtonEnabled = hasLocationPermission, zoomControlsEnabled = false)) {
                if (poly.size >= 2) Polyline(points = poly, width = 10f)
                current?.let { Marker(state = MarkerState(it), title = "現在地") }
            }
        }
        if (poly.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatBox("距離", String.format(Locale.JAPAN, "%.2f km", (if (recording) distance else restoredRoute?.distanceMeters ?: 0.0) / 1000.0), Modifier.weight(1f))
            StatBox("地点", poly.size.toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (recording) { recording = false; routeName = ""; showName = true }
                else {
                    points = emptyList(); lastPoint = null; distance = 0.0
                    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (fine || coarse) { startedAt = System.currentTimeMillis(); recording = true }
                    else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }, modifier = Modifier.weight(1f)) { Text(if (recording) "停止" else "記録開始") }
            OutlinedButton(onClick = onShowSaved, modifier = Modifier.weight(1f)) { Text("保存コース") }
        }
    }
    if (showName) AlertDialog(onDismissRequest = { showName = false }, title = { Text("コースを保存") }, text = { OutlinedTextField(value = routeName, onValueChange = { routeName = it }, label = { Text("コース名") }, singleLine = true) }, confirmButton = {
        TextButton(onClick = { val safeName = routeName.ifBlank { "${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(startedAt))}の散歩" }; onSave(SavedRoute(System.currentTimeMillis(), safeName, startedAt, System.currentTimeMillis() - startedAt, distance, points)); showName = false }) { Text("保存") }
    })
}

@Composable
fun SavedRoutesScreen(routes: List<SavedRoute>, onSelect: (SavedRoute) -> Unit) {
    if (routes.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("保存したコースはありません") }
    else LazyColumn(Modifier.fillMaxSize()) { items(routes) { route ->
        ListItem(headlineContent = { Text(route.name) }, supportingContent = { Text(String.format(Locale.JAPAN, "%.2f km / %d地点", route.distanceMeters / 1000.0, route.points.size)) }, leadingContent = { Text("🚶") }, trailingContent = { TextButton(onClick = { onSelect(route) }) { Text("地図") } }); HorizontalDivider()
    } }
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier) { Card(modifier) { Column(Modifier.padding(12.dp)) { Text(label, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.titleMedium) } } }

fun haversine(a: LatLng, b: LatLng): Double { val r = 6371000.0; val dLat = Math.toRadians(b.latitude - a.latitude); val dLon = Math.toRadians(b.longitude - a.longitude); val x = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2); return r * 2 * atan2(sqrt(x), sqrt(1 - x)) }
