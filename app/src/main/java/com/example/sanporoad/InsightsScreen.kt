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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

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
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("積み上がり", style = MaterialTheme.typography.titleMedium)
                    Text("累計 ${fmtKm(stats.totalDistanceMeters)} ・ ${stats.totalWalks}回")
                    Text("最長 ${fmtKm(stats.longestDistanceMeters)} ・ 最長時間 ${fmtDuration(stats.longestDurationMs)}")
                    Text("平均 ${fmtKm(stats.averageDistanceMeters)} / 回")
                    if (stats.currentStreakDays > 0) Text("現在 ${stats.currentStreakDays}日連続で散歩")
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
    var sharing by remember(route.id) { mutableStateOf(false) }
    var error by remember(route.id) { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("最新の散歩を共有", style = MaterialTheme.typography.titleMedium)
                Text(route.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${fmtKm(route.distanceMeters)} ・ ${fmtDuration(route.durationMs)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { confirm = true }, enabled = !sharing) {
                Text(if (sharing) "生成中…" else "リンク生成")
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("散歩結果を共有") },
            text = {
                Text(
                    "共有リンクには散歩ルート・距離・時間・メモが含まれます。" +
                        "写真は共有されません。リンクを知っている人は閲覧できます。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    sharing = true
                    scope.launch {
                        SupabaseRouteSync.createShareLink(route)
                            .onSuccess { url ->
                                val text = buildString {
                                    appendLine(route.name)
                                    appendLine("${fmtKm(route.distanceMeters)} / ${fmtDuration(route.durationMs)}")
                                    append(url)
                                }
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(intent, "散歩結果を共有"))
                            }
                            .onFailure {
                                error = "共有リンクを作成できませんでした。通信状態を確認してもう一度お試しください。"
                            }
                        sharing = false
                    }
                }) { Text("リンクを作って共有") }
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
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("今週の目安", style = MaterialTheme.typography.labelMedium)
                    Text("${fmtKm(currentMeters)} / ${fmtKm(goalMeters)}", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = onEdit) { Text("変更") }
            }
            LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
            val remaining = (goalMeters - currentMeters).coerceAtLeast(0.0)
            Text(
                if (remaining <= 0.0) "目安を達成。今週もよく歩けています。"
                else "あと ${fmtKm(remaining)}。無理のない日に少しずつ。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun WeeklyChart(weeks: List<WeeklyDistance>) {
    val max = (weeks.maxOfOrNull { it.distanceMeters } ?: 0.0).coerceAtLeast(1000.0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("直近8週間", style = MaterialTheme.typography.titleMedium)
            weeks.forEach { week ->
                val fraction = (week.distanceMeters / max).coerceIn(0.0, 1.0).toFloat()
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(week.weekStart.format(DateTimeFormatter.ofPattern("M/d")), style = MaterialTheme.typography.labelSmall)
                        Text("${fmtKm(week.distanceMeters)} ・ ${week.walks}回", style = MaterialTheme.typography.labelSmall)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp))
                                .background(MaterialTheme.colorScheme.primary)
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
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPrevious) { Text("‹") }
                Text(month.format(DateTimeFormatter.ofPattern("yyyy年 M月", Locale.JAPAN)), style = MaterialTheme.typography.titleMedium)
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
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (walked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(5.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium)
                if (walked) {
                    Text(
                        String.format(Locale.JAPAN, "%.1f", day.distanceMeters / 1000.0),
                        style = MaterialTheme.typography.labelSmall
                    )
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
