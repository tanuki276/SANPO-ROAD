package com.example.sanporoad

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PrivacySettingsScreen(store: RouteStore, preferences: WalkPreferences, syncStatus: String, onChanged: () -> Unit, onSync: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var sentTo by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var shares by remember { mutableStateOf<List<ManagedShare>>(emptyList()) }
    var sharesLoaded by remember { mutableStateOf(false) }
    var pendingRevoke by remember { mutableStateOf<ManagedShare?>(null) }
    var exportFormat by rememberSaveable { mutableStateOf("gpx") }
    var recording by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            recording = LocationTrackingService.isRecording(context) || LocationTrackingService.hasPendingRecording(context)
            kotlinx.coroutines.delay(500)
        }
    }
    fun action(block: suspend () -> Unit) {
        busy = true; message = null
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "操作できませんでした。通信状態を確認してください。" }
            finally { busy = false; refresh++; onChanged() }
        }
    }
    val account = remember(refresh) { preferences.activeAccountId }
    val cloud = remember(refresh) { preferences.cloudSync }
    val photo = remember(refresh) { preferences.photoSync }
    val location = remember(refresh) { preferences.shareLocation }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) action {
            withContext(Dispatchers.IO) {
                val routes = store.getAll()
                val text = when (exportFormat) { "gpx" -> RouteExport.gpx(routes); "csv" -> RouteExport.csv(routes); else -> RouteExport.json(routes) }
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) } ?: error("保存先を開けませんでした")
            }
            message = "エクスポートしました"
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("設定とプライバシー", style = MaterialTheme.typography.headlineSmall)
            Text("ログインしなくても散歩を記録できます。")
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            SettingsCard("保存と送信") {
                Text(syncStatus)
                SettingSwitch("クラウド同期", "ONにすると端末の記録をログイン中のアカウントへ保存・復元します。OFFでも既存のクラウドデータは残ります。", cloud, !busy && !recording) {
                    if (it) confirmation = "sync" else { preferences.cloudSync = false; refresh++; onChanged() }
                }
                SettingSwitch("写真も同期", "写真は初期状態では送信しません。OFFでも送信済みの写真は残ります。", photo, !busy) {
                    if (it) confirmation = "photos" else { preferences.photoSync = false; refresh++; onChanged() }
                }
                SettingSwitch("共有に地図を含める", "共有確認画面の初期値です。ONでも共有前に確認します。", location, !busy) {
                    preferences.shareLocation = it; refresh++; onChanged()
                }
                Button(onClick = onSync, enabled = cloud && account != null && !busy && !recording) { Text("同期してクラウドから復元") }
            }
        }
        item {
            SettingsCard("任意ログイン") {
                if (account == null) {
                    Text("同じメールでログインすると、同期した散歩を新しい端末で復元できます。ログインだけでは同期はONになりません。")
                    OutlinedTextField(email, { email = it.take(254) }, label = { Text("メールアドレス") }, singleLine = true, enabled = !busy && sentTo == null)
                    Button(enabled = !busy && !recording && email.contains('@') && sentTo == null, onClick = { action {
                        val address = email.trim(); SupabaseRouteSync.requestOtp(address)
                        sentTo = address; message = "メールの確認コードを入力してください。"
                    } }) { Text("確認コードを送る") }
                    if (sentTo != null) {
                        OutlinedTextField(otp, { otp = it.filter(Char::isDigit).take(10) }, label = { Text("確認コード") }, singleLine = true, enabled = !busy)
                        Button(enabled = !busy && !recording && otp.length >= 6, onClick = { action {
                            val address = sentTo!!
                            val id = SupabaseRouteSync.verifyOtp(address, otp)
                            preferences.cloudSync = false; preferences.activeAccountId = id; preferences.accountEmail = address
                            otp = ""; sentTo = null; message = "ログインしました。復元するにはクラウド同期をONにしてください。"
                        } }) { Text("ログイン") }
                        TextButton(enabled = !busy, onClick = { sentTo = null; otp = "" }) { Text("メールを変更・再送") }
                    }
                } else {
                    Text(preferences.accountEmail ?: "ログイン済み")
                    Text("ログアウトすると、このアカウントの端末内記録は非表示になります。再ログインすると表示されます。")
                    OutlinedButton(enabled = !busy && !recording, onClick = { confirmation = "logout" }) { Text("ログアウト") }
                    TextButton(enabled = !busy && !recording, onClick = { confirmation = "account" }) { Text("アカウントを削除", color = MaterialTheme.colorScheme.error) }
                }
                if (recording) Text("散歩の保存・破棄を終えてからアカウントを変更できます。")
            }
        }
        item {
            SettingsCard("共有リンク管理") {
                Text("リンクを知っている人は閲覧できます。停止したリンクは再利用できません。既に保存された画像や内容は回収できません。")
                OutlinedButton(enabled = account != null && !busy, onClick = { action { shares = SupabaseRouteSync.shares(); sharesLoaded = true } }) { Text("作成したリンクを読み込む") }
                if (account == null) Text("共有リンクの作成と管理にはログインが必要です。クラウド同期はOFFでも利用できます。")
                if (sharesLoaded && shares.isEmpty()) Text("共有中のリンクはありません")
            }
        }
        items(shares, key = { it.token }) { share ->
            SettingsCard(share.name) {
                Text(if (share.showMap) "地図あり・位置情報を公開中" else "地図なし")
                Text(share.createdAt.take(10), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, SupabaseRouteSync.shareUrl(share.token))
                    }, "共有リンク")) }) { Text("リンクを共有") }
                    TextButton(enabled = !busy, onClick = { pendingRevoke = share }) { Text("共有停止") }
                }
            }
        }
        item {
            SettingsCard("データの持ち出し・削除") {
                Text("GPXには道筋と地点の時刻、CSVには概要、JSONには道筋を含む記録を出力します。写真は含みません。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("gpx", "csv", "json").forEach { format ->
                        OutlinedButton(enabled = !busy, onClick = { exportFormat = format; confirmation = "export" }) { Text(format.uppercase()) }
                    }
                }
                TextButton(enabled = !busy && !recording, onClick = { confirmation = "data" }) { Text("表示中の全記録を削除", color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            SettingsCard("送信する情報") {
                Text("端末のみ保存：散歩の座標・日時・名前・メモ・写真はアプリ内に保存します。Androidの自動バックアップと端末間転送から除外します。")
                Text("地図：OpenStreetMapへの地図タイル要求にIPアドレスと表示地域が含まれます。GPS取得には端末の位置情報サービスを使います。")
                Text("ログイン：メールと確認コードをSupabase Authへ送信します。クラウド同期ON：座標・日時・距離・メモ等をSupabaseへ保存します。写真同期ON：写真を非公開ストレージへ保存します。")
                Text("共有：確認した名前・日時・距離・時間・メモをサーバーへ送り、リンク先で公開します。地図ありのときだけ座標も含みます。写真は公開しません。")
                Text("同期OFFは送信済みデータの削除ではありません。記録削除は共有リンクと写真も対象です。通信できない場合のクラウド削除は次回同期時に再試行します。")
            }
        }
    }
    pendingRevoke?.let { share ->
        AlertDialog(onDismissRequest = { pendingRevoke = null }, title = { Text("共有を停止しますか？") }, text = { Text("「${share.name}」のリンクを無効化します。") },
            confirmButton = { TextButton(onClick = { pendingRevoke = null; action {
                SupabaseRouteSync.revokeShare(share.token); shares = shares.filterNot { it.token == share.token }; message = "共有を停止しました"
            } }) { Text("停止する") } }, dismissButton = { TextButton(onClick = { pendingRevoke = null }) { Text("戻る") } })
    }
    confirmation?.let { kind ->
        val detail = when (kind) {
            "sync" -> "未所属の端末記録をログイン中のアカウントに紐付け、座標・日時・名前・メモを送信します。クラウドの記録も復元します。"
            "photos" -> "次回クラウド同期時に写真をSupabaseの非公開ストレージへ送信します。"
            "logout" -> "同期をOFFにしてログアウトします。クラウドと端末の記録は削除しません。"
            "account" -> "このアカウントの記録・写真・共有リンク・ログイン情報を削除します。取り消せません。必要な記録を先にエクスポートしてください。他サービスと共用のアカウントは安全のため削除を中止します。"
            "export" -> "${exportFormat.uppercase()}を選んだ保存先に書き出します。GPX/JSONには位置情報が含まれます。"
            else -> "表示中の全記録と写真を削除します。アカウントに紐付いた記録はクラウドと共有リンクも削除対象です。通信できない場合は次回同期で削除します。取り消せません。"
        }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("内容を確認してください") }, text = { Text(detail) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                when (kind) {
                    "sync" -> if (account == null) message = "先にメールでログインしてください。" else { preferences.cloudSync = true; refresh++; onChanged(); onSync() }
                    "photos" -> { preferences.photoSync = true; refresh++; onChanged() }
                    "export" -> export.launch("sanpo-${System.currentTimeMillis()}.$exportFormat")
                    "logout" -> action {
                        preferences.cloudSync = false; onChanged()
                        RouteStore.operations.lock()
                        try { SupabaseRouteSync.logout() } finally {
                            preferences.activeAccountId = null; preferences.accountEmail = null
                            shares = emptyList(); sharesLoaded = false; RouteStore.operations.unlock()
                        }
                        message = "ログアウトしました"
                    }
                    "account" -> action {
                        preferences.cloudSync = false; onChanged(); RouteStore.operations.lock()
                        try {
                            SupabaseRouteSync.deleteAccount()
                            withContext(Dispatchers.IO) { store.eraseAccountLocally(account!!) }
                            preferences.activeAccountId = null; preferences.accountEmail = null
                            shares = emptyList(); sharesLoaded = false; message = "アカウントを削除しました"
                        } finally { RouteStore.operations.unlock() }
                    }
                    else -> action {
                        withContext(Dispatchers.IO) { store.deleteVisible() }; message = "端末の記録を削除しました"
                        if (account != null) try { store.flushDeletes() } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = "端末の記録は削除済みです。クラウド削除は同期待ちです。" }
                        shares = emptyList(); sharesLoaded = false
                    }
                }
            }) { Text(if (kind in listOf("data", "account")) "削除する" else "続ける") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("キャンセル") } })
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); content()
        }
    }
}
@Composable
private fun SettingSwitch(label: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(label); Text(description, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
