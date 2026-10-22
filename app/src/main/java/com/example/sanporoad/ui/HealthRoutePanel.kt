package com.example.sanporoad

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable internal fun HealthRoutePanel(route: SavedRoute) {
    val context = LocalContext.current
    val manager = remember { HealthConnectManager(context) }
    var summary by remember(route) { mutableStateOf<HealthSummary?>(null) }
    LaunchedEffect(route) {
        if (manager.available() && route.durationMs + route.restDurationMs > 0) {
            try { summary = manager.read(route) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { summary = null }
        }
    }
    val data = summary ?: return
    if (data.steps == null && data.heartRate == null && data.calories == null) return
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.titleSmall)
            data.steps?.let { Text("歩数 $it 歩") }
            data.calories?.let { Text("消費カロリー %.0f kcal".format(it)) }
            data.heartRate?.let { Text("平均心拍数 $it bpm") }
        }
    }
}
