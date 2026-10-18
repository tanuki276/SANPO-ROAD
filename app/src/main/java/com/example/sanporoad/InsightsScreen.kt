package com.example.sanporoad

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun WalkInsightsScreen(
    routes: List<SavedRoute>,
    weeklyGoalMeters: Double,
    onWeeklyGoalChange: (Double) -> Unit
) {
    val stats = remember(routes) { WalkStats.calculate(routes) }
    val weeks = remember(routes) { WalkInsights.weeklyDistances(routes, 8) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val days = remember(routes, month) { WalkInsights.month(routes, month) }
    var goalDialog by remember { mutableStateOf(false) }

    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "あなたの散歩",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "歩いた記録を、ちょっと気持ちよく振り返る場所。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            GoalCard(
                currentMeters = stats.thisWeekDistanceMeters,
                goalMeters = weeklyGoalMeters,
                onEdit = { goalDialog = true }
            )
        }
        routes.firstOrNull()?.let { latest ->
            item { ShareLatestWalkCard(latest) }
        }
        item { WeeklyChart(weeks) }
        item {
            MonthCalendar(
                month = month,
                days = days,
                onPrevious = { month = month.minusMonths(1) },
                onNext = { month = month.plusMonths(1) }
            )
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("これまでの積み上がり", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniStat("累計", fmtKm(stats.totalDistanceMeters), Modifier.weight(1f))
                        MiniStat("散歩", "${stats.totalWalks}回", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniStat("最長", fmtKm(stats.longestDistanceMeters), Modifier.weight(1f))
                        MiniStat("平均", fmtKm(stats.averageDistanceMeters), Modifier.weight(1f))
                    }
                    Text(
                        if (stats.currentStreakDays > 0) "現在 ${stats.currentStreakDays}日連続で散歩中"
                        else "次の一歩から、また記録が始まります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }

    if (goalDialog) {
        GoalDialog(
            currentMeters = weeklyGoalMeters,
            onDismiss = { goalDialog = false },
            onSave = {
                onWeeklyGoalChange(it)
                goalDialog = false
            }
        )
    }
}

@Composable
private fun ShareLatestWalkCard(route: SavedRoute) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember(route.id) { mutableStateOf(false) }
    var shareMap by remember(route.id) { mutableStateOf(false) }
    var sharing by remember(route.id) { mutableStateOf(false) }
    var error by remember(route.id) { mutableStateOf<String?>(null) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "最新の散歩をシェア",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(route.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${fmtKm(route.distanceMeters)}  ·  ${fmtDuration(route.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text("地図OFFが標準") },
                    enabled = false
                )
            }
            Text(
                "ネッ友やSNSに送るときも、地図を出さずに距離・時間・メモだけ共有できます。",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { confirm = true },
                enabled = !sharing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (sharing) "リンクを作成中…" else "共有リンクを作る")
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("共有する内容を選ぶ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "距離・時間・メモは共有されます。写真は共有されません。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (shareMap) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "地図・散歩ルートを共有",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (shareMap)
                                        "ON：スタート・ゴールを含むルート座標がリンク先に表示されます。"
                                    else
                                        "OFF：ルート座標は共有データに保存されません。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(checked = shareMap, onCheckedChange = { shareMap = it })
                        }
                    }
                    if (shareMap) {
                        Text(
                            "自宅付近から歩き始めた記録は、生活圏が推測される場合があります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            "プライバシー優先：地図なしで共有します。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "リンクを知っている人は閲覧できます。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    sharing = true
                    scope.launch {
                        SupabaseRouteSync.createShareLink(route, shareMap)
                            .onSuccess { url ->
                                val text = buildString {
                                    appendLine(route.name)
                                    appendLine("${fmtKm(route.distanceMeters)} / ${fmtDuration(route.durationMs)}")
                                    if (!shareMap) appendLine("地図なしで共有")
                                    append(url)
                                }
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(intent, "散歩結果を共有"))
                            }
                            .onFailure {
                                error = "共有リンクを作成できませんでした。少し待ってからもう一度お試しください。"
                            }
                        sharing = false
                    }
                }) { Text(if (shareMap) "地図つきで共有" else "地図なしで共有") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("共有できませんでした") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun GoalCard(currentMeters: Double, goalMeters: Double, onEdit: () -> Unit) {
    val fraction = if (goalMeters <= 0.0) 0f else (currentMeters / goalMeters).coerceIn(0.0, 1.0).toFloat()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("今週", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${fmtKm(currentMeters)} / ${fmtKm(goalMeters)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onEdit) { Text("目安を変更") }
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)))
            val remaining = (goalMeters - currentMeters).coerceAtLeast(0.0)
            Text(
                if (remaining <= 0.0) "今週の目安を達成しました。いい感じ。"
                else "あと ${fmtKm(remaining)}。気が向いた日に少しずつ。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WeeklyChart(weeks: List<WeeklyDistance>) {
    val max = (weeks.maxOfOrNull { it.distanceMeters } ?: 0.0).coerceAtLeast(1000.0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("直近8週間", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("歩いた量の波をざっくり確認", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            weeks.forEach { week ->
                val fraction = (week.distanceMeters / max).coerceIn(0.0, 1.0).toFloat()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(week.weekStart.format(DateTimeFormatter.ofPattern("M/d")), style = MaterialTheme.typography.labelSmall)
                        Text("${fmtKm(week.distanceMeters)} · ${week.walks}回", style = MaterialTheme.typography.labelSmall)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    days: List<CalendarDaySummary>,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val first = month.atDay(1)
    val leading = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val cells = List(leading) { null } + days.map { it }
    val rows = cells.chunked(7)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPrevious) { Text("‹") }
                Text(month.format(DateTimeFormatter.ofPattern("yyyy年 M月", Locale.JAPAN)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onNext) { Text("›") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("月", "火", "水", "木", "金", "土", "日").forEach {
                    Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                }
            }
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    (0 until 7).forEach { index ->
                        val day = row.getOrNull(index)
                        CalendarCell(day, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarCell(day: CalendarDaySummary?, modifier: Modifier = Modifier) {
    Box(modifier.height(52.dp).padding(2.dp)) {
        if (day != null) {
            val walked = day.walks > 0
            Column(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                    .background(if (walked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(5.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium)
                if (walked) {
                    Text(String.format(Locale.JAPAN, "%.1f", day.distanceMeters / 1000.0), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun GoalDialog(currentMeters: Double, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var kmText by remember { mutableStateOf(String.format(Locale.JAPAN, "%.1f", currentMeters / 1000.0)) }
    val parsed = kmText.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("今週の目安") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ノルマではなく、今週どれくらい歩けたら嬉しいかの目安です。")
                OutlinedTextField(
                    value = kmText,
                    onValueChange = { kmText = it.take(6) },
                    label = { Text("距離 (km)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && parsed in 0.5..100.0,
                onClick = { parsed?.let { onSave(it * 1000.0) } }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

private fun fmtKm(meters: Double): String = String.format(Locale.JAPAN, "%.2f km", meters.coerceAtLeast(0.0) / 1000.0)

private fun fmtDuration(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    return if (totalMinutes >= 60) "${totalMinutes / 60}時間${totalMinutes % 60}分" else "${totalMinutes}分"
}

