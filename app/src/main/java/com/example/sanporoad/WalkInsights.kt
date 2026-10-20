package com.example.sanporoad

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class WeeklyDistance(
    val weekStart: LocalDate,
    val distanceMeters: Double,
    val walks: Int
)

data class CalendarDaySummary(
    val date: LocalDate,
    val distanceMeters: Double,
    val walks: Int
)

data class RouteComparison(
    val previous: SavedRoute,
    val distanceDeltaMeters: Double,
    val durationDeltaMs: Long,
    val paceDeltaSecondsPerKm: Double?
)

object WalkInsights {
    fun weeklyDistances(
        routes: List<SavedRoute>,
        weeks: Int = 8,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<WeeklyDistance> {
        val count = weeks.coerceIn(1, 16)
        val today = LocalDate.now(zone)
        val currentWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (count - 1 downTo 0).map { offset ->
            val start = currentWeek.minusWeeks(offset.toLong())
            val end = start.plusDays(6)
            val matched = routes.filter {
                val date = routeDate(it, zone)
                !date.isBefore(start) && !date.isAfter(end)
            }
            WeeklyDistance(start, matched.sumOf { it.distanceMeters }, matched.size)
        }
    }

    fun month(
        routes: List<SavedRoute>,
        month: YearMonth = YearMonth.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<CalendarDaySummary> {
        val grouped = routes.groupBy { routeDate(it, zone) }
        return (1..month.lengthOfMonth()).map { day ->
            val date = month.atDay(day)
            val dayRoutes = grouped[date].orEmpty()
            CalendarDaySummary(date, dayRoutes.sumOf { it.distanceMeters }, dayRoutes.size)
        }
    }

    fun compareWithPreviousSimilar(route: SavedRoute, routes: List<SavedRoute>): RouteComparison? {
        val similar = routes.find { it.id == route.sourceRouteId && it.ownerId == route.ownerId } ?: routes.asSequence()
            .filter { it.id != route.id && it.ownerId == route.ownerId && it.startedAt < route.startedAt }
            .filter { RouteMatching.sameCourse(route, it) }
            .maxByOrNull { it.startedAt }
            ?: return null

        val newPace = secondsPerKm(route)
        val oldPace = secondsPerKm(similar)
        return RouteComparison(
            previous = similar,
            distanceDeltaMeters = route.distanceMeters - similar.distanceMeters,
            durationDeltaMs = route.durationMs - similar.durationMs,
            paceDeltaSecondsPerKm = if (newPace != null && oldPace != null) newPace - oldPace else null
        )
    }

    private fun isSimilarRoute(a: SavedRoute, b: SavedRoute): Boolean {
        if (a.points.size < 2 || b.points.size < 2) return false
        val aStart = a.points.first()
        val aEnd = a.points.last()
        val bStart = b.points.first()
        val bEnd = b.points.last()
        val sameDirection = distanceMeters(aStart, bStart) <= 120.0 && distanceMeters(aEnd, bEnd) <= 180.0
        val reverseDirection = distanceMeters(aStart, bEnd) <= 120.0 && distanceMeters(aEnd, bStart) <= 180.0
        val distanceRatio = if (b.distanceMeters > 0.0) a.distanceMeters / b.distanceMeters else 0.0
        return (sameDirection || reverseDirection) && distanceRatio in 0.65..1.35
    }

    private fun secondsPerKm(route: SavedRoute): Double? {
        if (route.distanceMeters < 100.0 || route.durationMs <= 0L) return null
        return route.durationMs / 1000.0 / (route.distanceMeters / 1000.0)
    }

    private fun routeDate(route: SavedRoute, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(route.startedAt).atZone(zone).toLocalDate()

    private fun distanceMeters(a: LatLngPoint, b: LatLngPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lng - a.lng)
        val x = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(x), sqrt(1 - x))
    }
}

