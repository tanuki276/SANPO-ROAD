package com.example.sanporoad

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
fun RouteEditorDialog(route: SavedRoute, onClose: () -> Unit, onSave: suspend (SavedRoute) -> Unit, onDelete: suspend () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(route.id) { mutableStateOf(route.name) }
    var note by rememberSaveable(route.id) { mutableStateOf(route.note) }
    var favorite by rememberSaveable(route.id) { mutableStateOf(route.favorite) }
    var photo by rememberSaveable(route.id) { mutableStateOf<String?>(null) }
    var removePhoto by rememberSaveable(route.id) { mutableStateOf(route.photoRemoved) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { photo = uri.toString(); removePhoto = false } }
    fun action(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "保存できませんでした。空き容量と通信を確認して再試行してください。" }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("散歩を編集") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("名前") }, enabled = !busy)
            OutlinedTextField(note, { note = it.take(1000) }, label = { Text("メモ") }, enabled = !busy)
            Row { Checkbox(favorite, { favorite = it }, enabled = !busy); Text("お気に入り") }
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) { Text(if (photo == null) "写真を変更" else "写真を選択済み") }
            TextButton(onClick = { removePhoto = true; photo = null }, enabled = !busy) { Text(if (removePhoto) "保存時に写真を削除します" else "写真を外す") }
            Text("写真の変更・削除は次回同期でクラウドに反映します。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("この記録を削除", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { action {
        val photoPath = withContext(Dispatchers.IO) {
            if (removePhoto) null else photo?.let { PhotoStore.copyIntoAppStorage(context, android.net.Uri.parse(it), java.util.UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE))
                ?: error("photo") } ?: route.photoPath
        }
        onSave(route.copy(name = name.trim(), note = note.trim(), favorite = favorite, photoPath = photoPath,
            photoRemoved = removePhoto, photoObjectPath = route.photoObjectPath))
        if (removePhoto || (photoPath != route.photoPath && photo != null)) PhotoStore.localFile(route.photoPath)?.delete()
    } }) { Text(if (busy) "処理中…" else "保存") } }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("戻る") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("記録を削除しますか？") },
        text = { Text("端末の記録と写真を削除します。クラウド側の記録・写真・共有リンクも削除対象です。オフラインの場合は次回同期で削除します。取り消せません。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; action { onDelete() } }) { Text("削除する") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } })
}
