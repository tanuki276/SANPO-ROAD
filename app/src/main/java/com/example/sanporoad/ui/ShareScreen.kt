package com.example.sanporoad
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun ShareScreen(route: SavedRoute,onClose: () -> Unit,onChanged: () -> Unit) {
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    var map by remember { mutableStateOf(false) };var note by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf<Int?>(7) };var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) };var shares by remember { mutableStateOf<List<ManagedShare>>(emptyList()) }
    var completed by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) { busy=true;scope.launch {
        try { block();onChanged() } catch(e: CancellationException){throw e} catch(e:Exception){message=e.message ?: "操作できませんでした"} finally {busy=false}
    } }
    AlertDialog(onDismissRequest={if(!busy)onClose()}, title={Text("散歩を共有")},text={
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item { Text(route.name);Text("リンクを知っている人が閲覧できます。写真は公開されません。短い散歩は地図が全て隠れる場合があります。再作成に成功すると、この散歩の旧リンクは無効になります。失敗した場合は旧リンクを維持します。") }
            item { Row { Checkbox(map,{map=it});Text("地図を含める（始終点の半径150mを隠す）") };Row {Checkbox(note,{note=it});Text("メモを含める") } }
            item { Text("有効期限");Row { listOf(1,7,30,null).forEach { value -> TextButton(onClick={days=value}) { Text((if(days==value) "選択：" else "")+(value?.let { "${it}日" } ?: "無期限")) } } } }
            item { Button(enabled=!busy,onClick={action {
                val url=ShareManager(context).create(route,map,note,days)
                (context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("散歩の共有リンク",url))
                completed=true;message="共有リンクをコピーしました";shares=SupabaseRouteSync.shares().filter { it.routeLocalId==route.id }
            }}){Text("リンクを作成／再作成・コピー")}
                AnimatedVisibility(completed,enter=fadeIn()){Icon(Icons.Default.CheckCircle,"共有完了")}
                message?.let{Text(it)}
                OutlinedButton(enabled=!busy,onClick={action {shares=SupabaseRouteSync.shares().filter {it.routeLocalId==route.id}; message=if(shares.isEmpty()) "共有リンクはありません" else "${shares.size}件の共有リンク" }}){Text("過去の共有リンクを確認")}
            }
            items(shares,key={it.token}) { share ->
                Text("${share.createdAt.take(10)} / ${share.expiresAt ?: "無期限"}")
                Row { TextButton(onClick={context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("共有リンク",SupabaseRouteSync.shareUrl(share.token)))}){Text("コピー")}
                    TextButton(enabled=!busy,onClick={action {SupabaseRouteSync.revokeShare(share.token);shares=shares.filterNot {it.token==share.token};message="共有を停止しました"}}){Text("共有停止")} }
            }
        }
    },confirmButton={TextButton(enabled=!busy,onClick=onClose){Text("閉じる")}})
}

