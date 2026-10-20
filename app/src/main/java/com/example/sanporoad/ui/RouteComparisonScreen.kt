package com.example.sanporoad
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun RouteComparisonScreen(route: SavedRoute, routes: List<SavedRoute>, onClose: () -> Unit) {
    val candidates = remember(route,routes) { routes.filter { it.id != route.id && it.ownerId == route.ownerId } }
    var selected by remember(route.id) { mutableStateOf(candidates.find { it.id == route.sourceRouteId } ?: candidates.firstOrNull()) }
    AlertDialog(onDismissRequest=onClose,title={ Text("過去記録と比較") },text={
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item { Text(route.name); Text("ペースは速さの比較です。無理のない散歩を心がけてください。",style=MaterialTheme.typography.bodySmall) }
            selected?.let { previous ->
                item { Text("比較先：${previous.name}"); Text(if(RouteMatching.sameCourse(route,previous)) "同一コースの目安に一致" else "異なるコース・判定条件外") }
                items(compareRoutes(route,previous)) { metric ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                        Text(metric.label)
                        Text("今回 ${metric.current?.let { String.format(Locale.JAPAN,"%.1f",it) } ?: "--"} / 過去 ${metric.previous?.let { String.format(Locale.JAPAN,"%.1f",it) } ?: "--"} ${metric.unit}")
                        Text("${metric.assessment} ${metric.delta?.let { String.format(Locale.JAPAN,"%+.1f",it) } ?: "--"}")
                    } }
                }
            }
            item { Text("比較する記録を選択", style=MaterialTheme.typography.titleSmall) }
            items(candidates) { previous ->
                TextButton(onClick={selected=previous}) { Text(previous.name + if(RouteMatching.sameCourse(route,previous)) "（同じコース）" else "") }
            }
        }
    },confirmButton={TextButton(onClick=onClose){Text("閉じる")}})
}
