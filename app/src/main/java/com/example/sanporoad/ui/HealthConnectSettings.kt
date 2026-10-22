package com.example.sanporoad

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException

@Composable internal fun HealthConnectSettings() {
    val context = LocalContext.current
    val manager = remember(context) { HealthConnectManager(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableIntStateOf(HealthConnectClient.SDK_UNAVAILABLE) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        granted = it; refresh++
    }
    LaunchedEffect(lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                status = manager.status()
                granted = manager.grantedPermissions()
                message = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { granted = emptySet(); message = "連携状態を取得できませんでした。再試行してください。" }
        }
    }
    Text(when {
        status != HealthConnectClient.SDK_AVAILABLE -> "未接続"
        granted.containsAll(HealthConnectManager.permissions) -> "連携済み"
        granted.isNotEmpty() -> "一部の項目を連携中"
        else -> "未連携"
    }, style = MaterialTheme.typography.titleMedium)
    Text("散歩を保存すると運動・距離を書き込みます。歩数・平均心拍数・消費カロリーは、散歩の時間帯に対応するデータを読み込みます。許可する項目は選べます。")
    if (status == HealthConnectClient.SDK_AVAILABLE) {
        Text("読み取り：${HealthConnectManager.reads.count { it in granted }}/${HealthConnectManager.reads.size}項目 ・ 書き込み：${HealthConnectManager.writes.count { it in granted }}/${HealthConnectManager.writes.size}項目")
        Button(onClick = {
            try { permission.launch(HealthConnectManager.permissions) }
            catch (_: Exception) { message = "権限画面を開けませんでした。端末の設定からヘルスコネクトを開いてください。" }
        }) { Text(if (granted.isEmpty()) "ヘルスコネクトに連携" else "連携する項目を変更") }
        OutlinedButton(onClick = {
            try { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
            catch (_: Exception) { message = "端末の設定からヘルスコネクトを開き、アプリの権限を変更してください。" }
        }) { Text("ヘルスコネクトを開く・連携解除") }
    } else if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
        Text("ヘルスコネクトのインストールまたは更新が必要です。")
        Button(onClick = {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))) }
            catch (_: Exception) { message = "Google Playでヘルスコネクトを検索してください。" }
        }) { Text("インストール・更新") }
    } else Text("この端末ではヘルスコネクトを利用できません。")
    Text("心拍数・カロリーは対応機器やアプリの記録が必要です。インポート記録は送信しません。")
    message?.let { Text(it) }
}
