package com.example.sanporoad

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

object RouteExport {
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun gpx(routes: List<SavedRoute>): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<gpx version=\"1.1\" creator=\"SANPO-ROAD\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        routes.filter { it.deletedAt == null }.forEach { route ->
            appendLine("<trk><name>${xml(route.name)}</name><desc>${xml(route.note)}</desc>")
            var segment: Int? = null
            route.points.filter { it.lat.isFinite() && it.lng.isFinite() && it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }.forEach { point ->
                if (segment != point.segment) {
                    if (segment != null) appendLine("</trkseg>")
                    appendLine("<trkseg>"); segment = point.segment
                }
                append("<trkpt lat=\"${point.lat}\" lon=\"${point.lng}\">")
                if (point.timeMs > 0) append("<time>${Instant.ofEpochMilli(point.timeMs)}</time>")
                appendLine("</trkpt>")
            }
            if (segment != null) appendLine("</trkseg>")
            appendLine("</trk>")
        }
        appendLine("</gpx>")
    }
    private fun csvCell(text: String): String {
        val safe = if (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$text" else text
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun csv(routes: List<SavedRoute>): String = buildString {
        append("\uFEFFid,name,started_at,duration_ms,distance_meters,note,favorite\r\n")
        routes.filter { it.deletedAt == null }.forEach {
            append(listOf(it.id.toString(), csvCell(it.name), Instant.ofEpochMilli(it.startedAt).toString(),
                it.durationMs.toString(), it.distanceMeters.toString(), csvCell(it.note), it.favorite.toString()).joinToString(","))
            append("\r\n")
        }
    }
    fun json(routes: List<SavedRoute>): String = Json { prettyPrint = true; encodeDefaults = true }
        .encodeToString(ListSerializer(SavedRoute.serializer()), routes.filter { it.deletedAt == null }
            .map { it.copy(ownerId = null, photoPath = null, photoObjectPath = null) })
}

