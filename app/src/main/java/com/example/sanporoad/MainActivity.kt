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

private enum class AppScreen { MAP, HISTORY, INSIGHTS, SETTINGS }

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
    val scope = rememberCoroutineScope()
    var privacyVersion by remember { mutableIntStateOf(0) }
    val cloudEnabled = remember(privacyVersion) { preferences.cloudSync }
    val photoEnabled = remember(privacyVersion) { preferences.photoSync }
    val accountId = remember(privacyVersion) { preferences.activeAccountId }
    var guideRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var sharingRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var comparingRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var editingRoute by remember { mutableStateOf<SavedRoute?>(null) }

    var screen by rememberSaveable { mutableStateOf(AppScreen.MAP) }
    var selectedRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var syncVersion by remember { mutableIntStateOf(0) }
    var syncStatus by remember { mutableStateOf("端末に保存") }
    var syncing by remember { mutableStateOf(false) }
    var syncRequest by remember { mutableIntStateOf(0) }
    var lastSavedRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var weeklyGoalMeters by remember { mutableDoubleStateOf(preferences.weeklyGoalMeters) }

    var routes by remember { mutableStateOf<List<SavedRoute>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(syncVersion, accountId) {
        try { routes = withContext(Dispatchers.IO) { store.getAll() }; loadError = null }
        catch (e: Exception) { loadError = e.message ?: "記録を読み込めませんでした" }
    }


    BackHandler(enabled = screen != AppScreen.MAP) { screen = AppScreen.MAP }

    LaunchedEffect(syncRequest, cloudEnabled, photoEnabled, accountId) {
        if (!cloudEnabled || accountId == null || syncRequest == 0) {
            syncing = false
            syncStatus = "端末のみ保存"
            return@LaunchedEffect
        }
        syncing = true
        syncStatus = "記録を同期中…"
        try {
            val result = withContext(Dispatchers.IO) { store.syncAll() }
            syncVersion++
            syncStatus = if (result.failedUploads == 0) {
                "記録は同期済み"
            } else {
                "同期失敗・端末に保存済み"
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
                        IconButton(onClick = { if (cloudEnabled) syncRequest++ else screen = AppScreen.SETTINGS }, enabled = !syncing) {
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
                        Triple(AppScreen.INSIGHTS, "振り返り", Icons.Default.DateRange),
                        Triple(AppScreen.SETTINGS, "設定", Icons.Default.Settings)
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
                loadError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }

                when (screen) {
                    AppScreen.HISTORY -> {
                        WalkHistoryScreen(routes, onStartWalk = { screen = AppScreen.MAP }, onEdit = { editingRoute = it },
                            onShare = { sharingRoute = it }, onCompare = { comparingRoute = it },
                            onRepeat = { route ->
                                if (LocationTrackingService.isRecording(context) || LocationTrackingService.hasPendingRecording(context)) syncStatus = "現在の散歩を保存・破棄してから選択してください"
                                else { guideRoute = route; selectedRoute = null; screen = AppScreen.MAP }
                            }, onFavorite = { route -> scope.launch {
                                try { withContext(Dispatchers.IO) { store.save(route.copy(favorite = !route.favorite)) }; syncVersion++ }
                                catch (_: Exception) { syncStatus = "お気に入りを保存できませんでした" }
                            } }) { route ->
                            selectedRoute = route
                            screen = AppScreen.MAP
                        }
                    }
                    AppScreen.SETTINGS -> PrivacySettingsScreen(
                        store, preferences, syncStatus,
                        onChanged = { privacyVersion++; syncVersion++; selectedRoute = null },
                        onSync = { syncRequest++ }
                    )
                    AppScreen.INSIGHTS -> {
                        WalkInsightsScreen(
                            routes = routes,
                            weeklyGoalMeters = weeklyGoalMeters,
                            onRecordChanged = { syncVersion++ },
                            onWeeklyGoalChange = { meters ->
                                preferences.weeklyGoalMeters = meters
                                weeklyGoalMeters = meters
                            }
                        )
                    }
                    AppScreen.MAP -> {
                        MapRecordScreen(
                            restoredRoute = selectedRoute,
                            guideRoute = guideRoute,
                            onShare = { sharingRoute = it },
                            onCompare = { comparingRoute = it },
                            onRepeat = { guideRoute = it; selectedRoute = null },
                            onClearRestoredRoute = { selectedRoute = null },
                            onSave = { route ->
                                withContext(Dispatchers.IO) { store.save(route) }
                                guideRoute = null
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

    sharingRoute?.let { ShareScreen(it, onClose = { sharingRoute = null }, onChanged = { syncVersion++ }) }
    comparingRoute?.let { RouteComparisonScreen(it, routes, onClose = { comparingRoute = null }) }
    editingRoute?.let { route ->
        RouteEditorDialog(route, onClose = { editingRoute = null }, onSave = { updated ->
            withContext(Dispatchers.IO) { store.save(updated) }
            syncVersion++; syncRequest++; editingRoute = null
        }, onDelete = {
            withContext(Dispatchers.IO) { store.delete(route) }
            syncVersion++; selectedRoute = null
            if (preferences.activeAccountId != null) {
                try { withContext(Dispatchers.IO) { store.flushDeletes() } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { syncStatus = "端末は削除済み・クラウド削除は同期待ち" }
            }
            editingRoute = null
        })
    }
    lastSavedRoute?.let { saved ->
        WalkResultDialog(
            route = saved,
            previousRoutes = routes.filterNot { it.id == saved.id },
            onClose = { lastSavedRoute = null }
        )
    }
}

