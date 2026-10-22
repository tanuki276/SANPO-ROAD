package com.example.sanporoad

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun timeSummary(duration: Long, rest: Long) =
    "移動 ${formatDuration(duration)} / 休憩 ${formatDuration(rest)} / 経過 ${formatDuration(duration + rest)}"

@Composable internal fun GpsQualityView(report: GpsQualityReport?) {
    if (report == null) { Text("GPS品質：この記録にはレポートがありません"); return }
    Text("GPS信頼度：${report.confidence}")
    Text("採用 ${report.acceptedPoints}点 / 除外 ${report.excludedPoints}点")
    Text("精度中央値 ${report.medianAccuracyM?.let { "%.1fm".format(it) } ?: "不明"} / 低精度 ${formatDuration(report.lowAccuracyDurationMs)}")
    Text("採用・除外は距離積算の判定です。低精度は±10m超（取得間隔15秒まで集計）。")
    if (BuildConfig.DEBUG) Text("生距離 ${formatKm(report.rawDistanceMeters)} / フィルタ後 ${formatKm(report.filteredDistanceMeters)}")
}

val walkTags = listOf("犬の散歩", "普通の散歩", "買い物", "公園", "ハイキング")
fun parseWalkTags(text: String): List<String> = text.split(',', '、').map { it.trim().take(30) }.filter { it.isNotEmpty() }.distinct().take(20)
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TagEditor(text: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    val selectedTags = parseWalkTags(text)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("散歩タグ（複数可）", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            walkTags.forEach { tag ->
                val selected = tag in selectedTags
                FilterChip(selected = selected, enabled = enabled, onClick = {
                    onChange((if (selected) selectedTags - tag else selectedTags + tag).joinToString("、"))
                }, label = { Text(tag) })
            }
        }
        OutlinedTextField(text, { onChange(it.take(600)) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("タグを編集・追加（読点区切り）") })
    }
}
