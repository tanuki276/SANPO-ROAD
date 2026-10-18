package com.example.sanporoad

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class RoadWorldSnapshot(
    val roadCells: Int,
    val junctions: Int,
    val plazas: Int,
    val arterials: Int,
    val frontierCells: Int,
    val facilities: List<String>,
    val clears: Int,
    val identity: String,
    val description: String,
    val nextStructure: String
)

data class RoadWorldChange(
    val title: String,
    val detail: String,
    val nextStructure: String
)

data class RoadContract(
    val id: String,
    val name: String,
    val steps: List<String>,
    val reward: String
)

data class RoadRunState(
    val contract: RoadContract,
    val stage: Int,
    val score: Int,
    val cleared: Boolean,
    val status: String
) {
    val stageLabel: String
        get() = if (cleared) "CLEAR" else "${(stage + 1).coerceAtMost(contract.steps.size)}/${contract.steps.size}"
}

object RoadWorld {
    private const val CELL_SCALE = 4000.0
    private const val PLAZA_MIN_DISTANCE_M = 300.0
    private const val PLAZA_CLOSE_DISTANCE_M = 60.0

    private val contracts = listOf(
        RoadContract(
            id = "escape_return_close",
            name = "脱線帰環",
            steps = listOf("既存網から離脱", "過去の道へ接続", "始点へ帰還して輪を閉じる"),
            reward = "環状塔"
        ),
        RoadContract(
            id = "three_new_cells",
            name = "三叉開削",
            steps = listOf("新道を5区画開く", "90度以上の大きな曲がりを作る", "さらに新道を5区画開く"),
            reward = "分岐炉"
        ),
        RoadContract(
            id = "memory_weave",
            name = "記憶縫合",
            steps = listOf("過去道へ触れる", "新道へ離脱", "もう一度別の過去道へ触れる"),
            reward = "継ぎ目市場"
        ),
        RoadContract(
            id = "long_arc",
            name = "大弧建設",
            steps = listOf("始点から200m離れる", "400m以上の新道を刻む", "始点から120m以内へ戻る"),
            reward = "遠見門"
        )
    )

    fun contractFor(routes: List<SavedRoute>): RoadContract {
        val seed = routes.sumOf { it.id } + routes.size * 31L
        return contracts[(kotlin.math.abs(seed) % contracts.size).toInt()]
    }

    fun runState(routes: List<SavedRoute>, points: List<LatLngPoint>, distanceMeters: Double): RoadRunState {
        val contract = contractFor(routes)
        if (points.size < 2) return RoadRunState(contract, 0, 0, false, "最初の一歩を待っている")

        val oldCells = routes.flatMap { it.points.map(::cellOf) }.toSet()
        val runCells = points.map(::cellOf)
        val uniqueRun = runCells.distinct()
        val newCells = uniqueRun.count { it !in oldCells }
        val oldTouches = uniqueRun.count { it in oldCells }
        val startDistance = distanceMeters(points.first(), points.last())
        val maxFromStart = points.maxOfOrNull { distanceMeters(points.first(), it) } ?: 0.0
        val bigTurns = countBigTurns(points)
        val distinctOldClusters = oldContactClusters(uniqueRun, oldCells)

        fun completeStage(stage: Int): Boolean = when (contract.id) {
            "escape_return_close" -> when (stage) {
                0 -> newCells >= 5
                1 -> newCells >= 5 && oldTouches >= 1
                else -> distanceMeters >= PLAZA_MIN_DISTANCE_M && startDistance <= PLAZA_CLOSE_DISTANCE_M
            }
            "three_new_cells" -> when (stage) {
                0 -> newCells >= 5
                1 -> newCells >= 5 && bigTurns >= 1
                else -> newCells >= 10 && bigTurns >= 1
            }
            "memory_weave" -> when (stage) {
                0 -> oldTouches >= 1
                1 -> oldTouches >= 1 && newCells >= 5
                else -> distinctOldClusters >= 2 && newCells >= 5
            }
            "long_arc" -> when (stage) {
                0 -> maxFromStart >= 200.0
                1 -> maxFromStart >= 200.0 && newCells >= 12
                else -> distanceMeters >= 500.0 && startDistance <= 120.0
            }
            else -> false
        }

        var stage = 0
        while (stage < contract.steps.size && completeStage(stage)) stage++
        val cleared = stage >= contract.steps.size
        val score = (newCells * 12 + oldTouches * 6 + bigTurns * 40 + if (cleared) 500 else 0)
            .coerceAtLeast(0)

        val status = if (cleared) {
            "契約成立 — ${contract.reward}を建設可能"
        } else {
            "次: ${contract.steps[stage]}"
        }

        return RoadRunState(contract, stage, score, cleared, status)
    }

    fun applyGameResult(routes: List<SavedRoute>, route: SavedRoute): SavedRoute {
        val state = runState(routes, route.points, route.distanceMeters)
        return route.copy(
            gameContract = state.contract.name,
            gameScore = state.score,
            gameCleared = state.cleared,
            facility = if (state.cleared) state.contract.reward else null
        )
    }

    fun snapshot(routes: List<SavedRoute>): RoadWorldSnapshot {
        if (routes.isEmpty()) {
            return RoadWorldSnapshot(
                0, 0, 0, 0, 0, emptyList(), 0,
                "まだ名のない裏街",
                "最初のランで、この街のルールが生まれる。",
                "最初の道路契約を攻略する"
            )
        }

        val routesSorted = routes.sortedBy { it.startedAt }
        val cellRoutes = linkedMapOf<Cell, MutableSet<Long>>()
        routesSorted.forEach { route ->
            route.points.asSequence().map(::cellOf).distinct().forEach { cell ->
                cellRoutes.getOrPut(cell) { linkedSetOf() }.add(route.id)
            }
        }

        val previousCells = routesSorted.dropLast(1).flatMap { it.points.map(::cellOf) }.toSet()
        val latestCells = routesSorted.last().points.map(::cellOf).toSet()
        val plazas = routesSorted.count(::isClosedWalk)
        val junctions = cellRoutes.values.count { it.size >= 2 }
        val arterials = cellRoutes.values.count { it.size >= 3 }
        val frontier = latestCells.count { it !in previousCells }
        val roadCells = cellRoutes.size
        val facilities = routesSorted.mapNotNull { it.facility }.distinct()
        val clears = routesSorted.count { it.gameCleared }

        val identity = when {
            facilities.size >= 4 && plazas >= 2 -> "自律路界都市"
            clears >= 5 -> "契約都市"
            plazas >= 3 && junctions >= 8 -> "環状連結都市"
            arterials >= 6 -> "記憶幹線都市"
            junctions >= 10 -> "継ぎ目都市"
            plazas >= 2 -> "輪郭都市"
            frontier >= 18 -> "伸長都市"
            else -> "生成途中の裏街"
        }

        val description = when (identity) {
            "自律路界都市" -> "契約で生まれた施設同士が道路網を変質させ、街が独自の法則を持ち始めた。"
            "契約都市" -> "成功したランが積み上がり、単なる散歩道ではなく攻略履歴になっている。"
            "環状連結都市" -> "輪と交差が互いを支え、歩くたびに街区が増殖している。"
            "記憶幹線都市" -> "何度も通った道が太くなり、あなた固有の幹線網が生まれている。"
            "継ぎ目都市" -> "過去の散歩どうしが接続され、別々だった記憶が一つの街になり始めた。"
            "輪郭都市" -> "閉じた散歩が広場を作り、街に内側と外側が生まれている。"
            "伸長都市" -> "いつもの道から外へ伸びる新道が、街の輪郭を押し広げている。"
            else -> "まだ街の法則は弱い。契約攻略で施設を増やすと性格が変わる。"
        }

        return RoadWorldSnapshot(
            roadCells, junctions, plazas, arterials, frontier, facilities, clears,
            identity, description, nextStructure(roadCells, junctions, plazas, arterials, frontier)
        )
    }

    fun change(before: RoadWorldSnapshot, after: RoadWorldSnapshot): RoadWorldChange {
        val newRoad = (after.roadCells - before.roadCells).coerceAtLeast(0)
        val newJunctions = (after.junctions - before.junctions).coerceAtLeast(0)
        val newPlazas = (after.plazas - before.plazas).coerceAtLeast(0)
        val newFacilities = after.facilities.filterNot { it in before.facilities }

        val events = buildList {
            if (newRoad > 0) add("新道 +${newRoad}区画")
            if (newJunctions > 0) add("継ぎ目 +$newJunctions")
            if (newPlazas > 0) add("広場 +$newPlazas")
            if (newFacilities.isNotEmpty()) add("施設 ${newFacilities.joinToString()}")
        }

        val title = when {
            newFacilities.isNotEmpty() -> "契約成立：${newFacilities.first()}が出現"
            before.identity != after.identity -> "裏街が『${after.identity}』へ変質した"
            newPlazas > 0 -> "輪が閉じ、広場が生まれた"
            newJunctions > 0 -> "過去の道と接続した"
            else -> "道路網が更新された"
        }

        return RoadWorldChange(
            title,
            if (events.isEmpty()) after.description else events.joinToString(" ・ ") + "\n" + after.description,
            after.nextStructure
        )
    }

    fun walkHint(points: List<LatLngPoint>): String {
        if (points.size < 2) return "路紋を読み取り中…"
        val close = distanceMeters(points.first(), points.last())
        return when {
            points.size >= 8 && close <= PLAZA_CLOSE_DISTANCE_M -> "◎ 始点へ帰還 — 輪が成立圏内"
            close <= 180.0 -> "始点まで約${close.roundToInt()}m"
            else -> "ルート入力中"
        }
    }

    private fun nextStructure(roadCells: Int, junctions: Int, plazas: Int, arterials: Int, frontier: Int): String = when {
        roadCells < 15 -> "15区画ぶんの道路網を作る"
        plazas == 0 -> "最初の輪を閉じる"
        junctions < 3 -> "過去道との継ぎ目を3つ作る"
        arterials < 2 -> "同じ継ぎ目を別日に再攻略して幹線化する"
        frontier < 5 -> "未知の5区画へ伸ばす"
        else -> "契約CLEAR数を増やし、施設ネットワークを育てる"
    }

    private fun isClosedWalk(route: SavedRoute): Boolean =
        route.distanceMeters >= PLAZA_MIN_DISTANCE_M && route.points.size >= 8 &&
            distanceMeters(route.points.first(), route.points.last()) <= PLAZA_CLOSE_DISTANCE_M

    private data class Cell(val lat: Int, val lng: Int)

    private fun cellOf(point: LatLngPoint): Cell = Cell(
        (point.lat * CELL_SCALE).roundToInt(),
        (point.lng * CELL_SCALE).roundToInt()
    )

    private fun oldContactClusters(runCells: List<Cell>, oldCells: Set<Cell>): Int {
        var clusters = 0
        var inside = false
        runCells.forEach { cell ->
            val now = cell in oldCells
            if (now && !inside) clusters++
            inside = now
        }
        return clusters
    }

    private fun countBigTurns(points: List<LatLngPoint>): Int {
        if (points.size < 5) return 0
        var turns = 0
        for (i in 2 until points.size - 2 step 2) {
            val a = bearing(points[i - 2], points[i])
            val b = bearing(points[i], points[i + 2])
            var diff = kotlin.math.abs(a - b)
            if (diff > 180) diff = 360 - diff
            if (diff >= 70) turns++
        }
        return turns
    }

    private fun bearing(a: LatLngPoint, b: LatLngPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lng - a.lng)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun distanceMeters(a: LatLngPoint, b: LatLngPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lng - a.lng)
        val x = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(x), sqrt(1 - x))
    }
}
