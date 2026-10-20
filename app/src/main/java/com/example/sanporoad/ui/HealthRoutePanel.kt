package com.example.sanporoad

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable internal fun HealthRoutePanel(route: SavedRoute) {
    val context=LocalContext.current; val manager=remember { HealthConnectManager(context) }; val scope=rememberCoroutineScope()
    var message by remember(route.id) { mutableStateOf<String?>(null) }
    var summary by remember(route.id) { mutableStateOf<HealthSummary?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun refresh(write: Boolean) { scope.launch {
        busy=true
        try { if(write) message=manager.write(route); summary=manager.read(route) }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { message="Health Connectを利用できません。SANPOの記録は保存されています。権限や対応状況を確認し、再試行できます。" }
        finally { busy=false }
    } }
    LaunchedEffect(route.id) { if (manager.available() && route.durationMs > 0) refresh(false) }
    val permission=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh(!route.imported) }
    Column {
        Text("Health Connect")
        if (manager.available()) {
            Button(enabled=!busy,onClick={permission.launch(if(route.imported) HealthConnectManager.reads else HealthConnectManager.permissions)}) { Text("連携・権限を設定") }
            OutlinedButton(enabled=!busy,onClick={refresh(!route.imported)}) { Text("再試行・最新データを取得") }
            summary?.let { Text("歩数 ${it.steps?.toString() ?: "データなし"} / 平均心拍 ${it.heartRate?.let { bpm -> "$bpm bpm" } ?: "データなし"} / 消費 ${it.calories?.let { kcal -> "%.0f kcal".format(kcal) } ?: "データなし"}") }
            Text("心拍・カロリーは対応機器が記録したデータを表示します。")
        } else Text("この端末では利用できないか、Health Connectのインストール・更新が必要です。")
        message?.let { Text(it) }
        if (route.imported) Text("インポート記録は送信しません")
    }
}
