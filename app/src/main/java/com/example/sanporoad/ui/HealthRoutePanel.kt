package com.example.sanporoad

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Same metric cards as distance/time; permission management lives in Settings. */
@Composable internal fun HealthRoutePanel(route: SavedRoute, live: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember(context) { HealthConnectManager(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRoute by rememberUpdatedState(route)
    var summary by remember(route.id, route.startedAt) { mutableStateOf<HealthSummary?>(null) }
    var message by remember(route.id, route.startedAt) { mutableStateOf<String?>(null) }
    var connected by remember(route.id, route.startedAt) { mutableStateOf(false) }
    var refresh by remember(route.id) { mutableIntStateOf(0) }
    LaunchedEffect(route.id, route.startedAt, live, refresh, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                try {
                    connected = manager.grantedPermissions().any { it in HealthConnectManager.reads }
                    summary = if (connected && currentRoute.durationMs + currentRoute.restDurationMs > 0) manager.read(currentRoute) else null
                    message = null
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { summary = null; message = "健康データを取得できませんでした。設定の連携状態をご確認ください。" }
                if (live) delay(30_000)
            } while (live)
        }
    }
    if (connected || message != null) Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatBox("歩数", summary?.steps?.let { "$it 歩" } ?: "—", Modifier.weight(1f))
            StatBox("平均心拍数", summary?.heartRate?.let { "$it bpm" } ?: "—", Modifier.weight(1f))
            StatBox("カロリー", summary?.calories?.let { "%.0f kcal".format(it) } ?: "—", Modifier.weight(1f))
        }
        Text("ヘルスコネクトのデータ（— は未取得・未許可）", style = MaterialTheme.typography.labelSmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!live) TextButton(onClick = { refresh++ }) { Text("健康データを更新") }
    }
}

@Composable internal fun HealthWriteRetry(route: SavedRoute) {
    val context = LocalContext.current
    val manager = remember(context) { HealthConnectManager(context) }
    var request by remember(route.id) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(route.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(route.id, request) {
        if (request > 0) {
            busy = true
            try { message = manager.writeAfterSave(route) } finally { busy = false }
        }
    }
    if (!route.imported && manager.available()) {
        TextButton(enabled = !busy, onClick = { request++ }) { Text("ヘルスコネクトへの送信を再試行") }
        message?.let { Text(it) }
    }
}
