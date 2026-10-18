package com.example.sanporoad

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

data class WalkStatsSnapshot(
    val totalDistanceMeters: Double,
    val totalDurationMs: Long,
    val totalWalks: Int,
    val currentStreakDays: Int,
    val thisWeekDistanceMeters: Double,
    val thisWeekWalks: Int,
    val lastWeekDistanceMeters: Double,
    val longestDistanceMeters: Double,
    val longestDurationMs: Long,
    val averageDistanceMeters: Double,
    val averageDurationMs: Long
) {
    val weekDeltaMeters: Double get() = thisWeekDistanceMeters - lastWeekDistanceMeters
}

object WalkStats {
    fun calculate(routes: List<SavedRoute>, zone: ZoneId = ZoneId.systemDefault()): WalkStatsSnapshot {
        if (routes.isEmpty()) {
            return WalkStatsSnapshot(0.0, 0L, 0, 0, 0.0, 0, 0.0, 0.0, 0L, 0.0, 0L)
        }

        val today = LocalDate.now(zone)
        val thisWeekStart = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val lastWeekStart = thisWeekStart.minusWeeks(1)
        val lastWeekEnd = thisWeekStart.minusDays(1)

        val dated = routes.map { route -> route to routeDate(route, zone) }
        val thisWeek = dated.filter { (_, date) -> !date.isBefore(thisWeekStart) && !date.isAfter(today) }.map { it.first }
        val lastWeek = dated.filter { (_, date) -> !date.isBefore(lastWeekStart) && !date.isAfter(lastWeekEnd) }.map { it.first }

        val totalDistance = routes.sumOf { it.distanceMeters }
        val totalDuration = routes.sumOf { it.durationMs }

        return WalkStatsSnapshot(
            totalDistanceMeters = totalDistance,
            totalDurationMs = totalDuration,
            totalWalks = routes.size,
            currentStreakDays = calculateStreak(dated.map { it.second }, today),
            thisWeekDistanceMeters = thisWeek.sumOf { it.distanceMeters },
            thisWeekWalks = thisWeek.size,
            lastWeekDistanceMeters = lastWeek.sumOf { it.distanceMeters },
            longestDistanceMeters = routes.maxOfOrNull { it.distanceMeters } ?: 0.0,
            longestDurationMs = routes.maxOfOrNull { it.durationMs } ?: 0L,
            averageDistanceMeters = totalDistance / routes.size,
            averageDurationMs = totalDuration / routes.size
        )
    }

    private fun routeDate(route: SavedRoute, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(route.startedAt).atZone(zone).toLocalDate()

    private fun calculateStreak(daysRaw: List<LocalDate>, today: LocalDate): Int {
        val days = daysRaw.distinct().sortedDescending()
        if (days.isEmpty()) return 0

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
        return max(streak, 0)
    }
}
