package com.example.sanporoad

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SANPO-ROAD's core game system.
 *
 * The real route is not a path to content placed by the game. The route itself becomes content:
 * repeated cells become roads, route overlaps become junctions, closed walks become plazas, and
 * roads revisited across several walks become arterials. This keeps the game personal and makes
 * every player's generated "back city" structurally different.
 */
data class RoadWorldSnapshot(
    val roadCells: Int,
    val junctions: Int,
    val plazas: Int,
    val arterials: Int,
    val frontierCells: Int,
    val identity: String,
    val description: String,
    val nextStructure: String
)

data class RoadWorldChange(
    val title: String,
    val detail: String,
    val nextStructure: String
)

object RoadWorld {
    private const val CELL_SCALE = 4000.0 // roughly 20-30m per cell in Japan
    private const val PLAZA_MIN_DISTANCE_M = 300.0
    private const val PLAZA_CLOSE_DISTANCE_M = 60.0

    fun snapshot(routes: List<SavedRoute>): RoadWorldSnapshot {
        if (routes.isEmpty()) {
            return RoadWorldSnapshot(
                roadCells = 0,
                junctions = 0,
                plazas = 0,
                arterials = 0,
                frontierCells = 0,
                identity = "まだ名のない裏街",
                description = "最初の散歩が、この街の最初の道路になる。",
                nextStructure = "一本の道を刻む"
            )
        }

        val routesSorted = routes.sortedBy { it.startedAt }
        val cellRoutes = linkedMapOf<Cell, MutableSet<Long>>()

        routesSorted.forEach { route ->
            route.points.asSequence()
                .map(::cellOf)
                .distinct()
                .forEach { cell -> cellRoutes.getOrPut(cell) { linkedSetOf() }.add(route.id) }
        }

        val previousCells = routesSorted.dropLast(1)
            .flatMap { route -> route.points.map(::cellOf) }
            .toSet()
        val latestCells = routesSorted.last().points.map(::cellOf).toSet()

        val plazas = routesSorted.count(::isClosedWalk)
        val junctions = cellRoutes.values.count { it.size >= 2 }
        val arterials = cellRoutes.values.count { it.size >= 3 }
        val frontier = latestCells.count { it !in previousCells }
        val roadCells = cellRoutes.size

        val identity = when {
            plazas >= 3 && junctions >= 8 -> "環状連結都市"
            arterials >= 6 -> "記憶幹線都市"
            junctions >= 10 -> "継ぎ目都市"
            plazas >= 2 -> "輪郭都市"
            frontier >= 18 -> "伸長都市"
            roadCells >= 60 -> "路地都市"
            else -> "生成途中の裏街"
        }

        val description = when (identity) {
            "環状連結都市" -> "輪と交差が互いを支え、歩くたびに街区が増殖している。"
            "記憶幹線都市" -> "何度も通った道が太くなり、あなた固有の幹線網が生まれている。"
            "継ぎ目都市" -> "過去の散歩どうしが接続され、別々だった記憶が一つの街になり始めた。"
            "輪郭都市" -> "閉じた散歩が広場を作り、街に内側と外側が生まれている。"
            "伸長都市" -> "いつもの道から外へ伸びる新道が、街の輪郭を押し広げている。"
            "路地都市" -> "短い道の積み重ねが密度を作り、細かな街の表情が育っている。"
            else -> "まだ規則は固まっていない。次の歩き方で街の性格が変わる。"
        }

        return RoadWorldSnapshot(
            roadCells = roadCells,
            junctions = junctions,
            plazas = plazas,
            arterials = arterials,
            frontierCells = frontier,
            identity = identity,
            description = description,
            nextStructure = nextStructure(roadCells, junctions, plazas, arterials, frontier)
        )
    }

    fun change(before: RoadWorldSnapshot, after: RoadWorldSnapshot): RoadWorldChange {
        val newRoad = (after.roadCells - before.roadCells).coerceAtLeast(0)
        val newJunctions = (after.junctions - before.junctions).coerceAtLeast(0)
        val newPlazas = (after.plazas - before.plazas).coerceAtLeast(0)
        val newArterials = (after.arterials - before.arterials).coerceAtLeast(0)

        val events = buildList {
            if (newRoad > 0) add("新道 +${newRoad}区画")
            if (newJunctions > 0) add("継ぎ目 +$newJunctions")
            if (newPlazas > 0) add("広場 +$newPlazas")
            if (newArterials > 0) add("幹線 +$newArterials")
        }

        val title = when {
            before.identity != after.identity -> "裏街が『${after.identity}』へ変質した"
            newPlazas > 0 -> "輪が閉じ、広場が生まれた"
            newJunctions > 0 -> "過去の道と接続した"
            newArterials > 0 -> "道が記憶され、幹線になった"
            newRoad > 0 -> "裏街の輪郭が伸びた"
            else -> "同じ道に記憶が重なった"
        }

        return RoadWorldChange(
            title = title,
            detail = if (events.isEmpty()) after.description else events.joinToString(" ・ ") + "\n" + after.description,
            nextStructure = after.nextStructure
        )
    }

    /** A live hint based only on the current trace; it does not require staring at the screen. */
    fun walkHint(points: List<LatLngPoint>): String {
        if (points.size < 2) return "路紋を読み取り中…"
        val first = points.first()
        val last = points.last()
        val close = distanceMeters(first, last)
        return when {
            points.size >= 8 && close <= PLAZA_CLOSE_DISTANCE_M -> "◎ 輪が閉じている — 広場化候補"
            close <= 180.0 -> "始点まで約${close.roundToInt()}m — 閉じれば輪になる"
            else -> "新しい線を刻んでいる — 終了後に裏街へ反映"
        }
    }

    private fun nextStructure(
        roadCells: Int,
        junctions: Int,
        plazas: Int,
        arterials: Int,
        frontier: Int
    ): String = when {
        roadCells < 15 -> "まず15区画ぶん、途切れない一本の道を作る"
        plazas == 0 -> "300m以上歩き、始点から60m以内へ戻って最初の『広場』を作る"
        junctions < 3 -> "過去の道を横切り、別々の散歩を3つの『継ぎ目』で接続する"
        arterials < 2 -> "別の日に同じ継ぎ目を通り、道を『幹線』へ育てる"
        frontier < 5 -> "いつもの道路網から外へ伸び、未知の5区画を接続する"
        else -> "二つの広場を、新しい一本の道で結ぶ"
    }

    private fun isClosedWalk(route: SavedRoute): Boolean {
        if (route.distanceMeters < PLAZA_MIN_DISTANCE_M || route.points.size < 8) return false
        return distanceMeters(route.points.first(), route.points.last()) <= PLAZA_CLOSE_DISTANCE_M
    }

    private data class Cell(val lat: Int, val lng: Int)

    private fun cellOf(point: LatLngPoint): Cell = Cell(
        lat = (point.lat * CELL_SCALE).roundToInt(),
        lng = (point.lng * CELL_SCALE).roundToInt()
    )

    private fun distanceMeters(a: LatLngPoint, b: LatLngPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lng - a.lng)
        val x = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(x), sqrt(1 - x))
    }
}
