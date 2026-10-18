package com.example.sanporoad

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class DailyMission(
    val title: String,
    val current: Double,
    val target: Double,
    val unit: String
) {
    val complete: Boolean get() = current >= target
    val fraction: Float get() = (current / target).coerceIn(0.0, 1.0).toFloat()
}

data class GameProgress(
    val totalXp: Int,
    val level: Int,
    val levelXp: Int,
    val levelTargetXp: Int,
    val streakDays: Int,
    val badges: List<String>,
    val missions: List<DailyMission>
) {
    val levelFraction: Float get() = (levelXp.toFloat() / levelTargetXp.coerceAtLeast(1)).coerceIn(0f, 1f)
}

object WalkingGame {
    private const val XP_PER_LEVEL = 250

    fun progress(routes: List<SavedRoute>): GameProgress {
        val totalDistance = routes.sumOf { it.distanceMeters }
        val totalMinutes = routes.sumOf { it.durationMs } / 60_000.0
        val totalXp = (totalDistance / 20.0).roundToInt() + (totalMinutes * 2.0).roundToInt() + routes.size * 25
        val level = totalXp / XP_PER_LEVEL + 1
        val levelXp = totalXp % XP_PER_LEVEL

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayRoutes = routes.filter { routeDate(it, zone) == today }
        val todayDistance = todayRoutes.sumOf { it.distanceMeters }
        val todayMinutes = todayRoutes.sumOf { it.durationMs } / 60_000.0

        val missions = listOf(
            DailyMission("1km歩こう", todayDistance, 1_000.0, "m"),
            DailyMission("20分歩こう", todayMinutes, 20.0, "分"),
            DailyMission("散歩を1回完了", todayRoutes.size.toDouble(), 1.0, "回")
        )

        val badges = buildList {
            if (routes.isNotEmpty()) add("はじめの一歩")
            if (totalDistance >= 5_000) add("5kmウォーカー")
            if (totalDistance >= 20_000) add("街の探検家")
            if (routes.any { it.distanceMeters >= 5_000 }) add("ロングウォーク")
            if (calculateStreak(routes, zone) >= 3) add("3日連続")
            if (calculateStreak(routes, zone) >= 7) add("1週間ストリーク")
        }

        return GameProgress(
            totalXp = totalXp,
            level = level,
            levelXp = levelXp,
            levelTargetXp = XP_PER_LEVEL,
            streakDays = calculateStreak(routes, zone),
            badges = badges,
            missions = missions
        )
    }

    fun pendingXp(distanceMeters: Double, durationMs: Long): Int {
        return (distanceMeters / 20.0).roundToInt() + ((durationMs / 60_000.0) * 2.0).roundToInt() + 25
    }

    private fun routeDate(route: SavedRoute, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(route.startedAt).atZone(zone).toLocalDate()

    private fun calculateStreak(routes: List<SavedRoute>, zone: ZoneId): Int {
        val days = routes.map { routeDate(it, zone) }.distinct().sortedDescending()
        if (days.isEmpty()) return 0

        val today = LocalDate.now(zone)
        var expected = when (days.first()) {
            today -> today
            today.minusDays(1) -> today.minusDays(1)
            else -> return 0
        }

        var streak = 0
        for (day in days) {
            if (day != expected) break
            streak++
            expected = expected.minusDays(1)
        }
        return streak
    }
}
