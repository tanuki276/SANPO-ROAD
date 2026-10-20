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
internal fun WalkResultDialog(
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
internal fun RoutePhoto(route: SavedRoute, modifier: Modifier = Modifier) {
    val settings = WalkPreferences(LocalContext.current)
    val allowRemote = settings.cloudSync && settings.photoSync && settings.activeAccountId == route.ownerId
    val local = remember(route.photoPath) { PhotoStore.localFile(route.photoPath) }
    var remoteUrl by remember(route.photoObjectPath) { mutableStateOf<String?>(null) }

    LaunchedEffect(route.photoObjectPath, local, allowRemote) {
        if (allowRemote && local == null && route.photoObjectPath != null) {
            remoteUrl = SupabaseRouteSync.signedPhotoUrl(route.photoObjectPath)
        }
    }

    val model: Any? = local ?: remoteUrl?.takeIf { allowRemote }
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = "散歩の写真",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

internal fun comparisonText(comparison: RouteComparison): String {
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

internal fun comparisonCompactText(comparison: RouteComparison): String {
    val pace = comparison.paceDeltaSecondsPerKm
    return when {
        pace == null -> signedDuration(comparison.durationDeltaMs)
        abs(pace) < 2.0 -> "ほぼ同じペース"
        pace < 0 -> "${formatPaceDelta(-pace)}速い"
        else -> "${formatPaceDelta(pace)}ゆっくり"
    }
}

internal fun signedKm(meters: Double): String {
    val sign = if (meters >= 0) "+" else "-"
    return "$sign${String.format(Locale.JAPAN, "%.2f km", abs(meters) / 1000.0)}"
}

internal fun signedDuration(ms: Long): String {
    val sign = if (ms >= 0) "+" else "-"
    val seconds = abs(ms) / 1000L
    return "$sign${seconds / 60}:${String.format(Locale.JAPAN, "%02d", seconds % 60)}"
}

internal fun formatPaceDelta(seconds: Double): String {
    val sec = seconds.roundToInt().coerceAtLeast(0)
    return "${sec / 60}:${String.format(Locale.JAPAN, "%02d", sec % 60)}/km "
}

internal fun formatKm(distanceMeters: Double): String =
    String.format(Locale.JAPAN, "%.2f km", distanceMeters.coerceAtLeast(0.0) / 1000.0)

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) String.format(Locale.JAPAN, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.JAPAN, "%02d:%02d", minutes, seconds)
}

internal fun paceText(distanceMeters: Double, durationMs: Long): String {
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


