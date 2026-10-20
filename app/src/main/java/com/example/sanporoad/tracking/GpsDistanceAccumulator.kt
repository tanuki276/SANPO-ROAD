package com.example.sanporoad

import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class GpsQualityReport(
    val acceptedPoints: Int = 0,
    val excludedPoints: Int = 0,
    val medianAccuracyM: Float? = null,
    val lowAccuracyDurationMs: Long = 0,
    val observedDurationMs: Long = 0,
    val rawDistanceMeters: Double = 0.0,
    val filteredDistanceMeters: Double = 0.0
) {
    val confidence: String get() = when {
        acceptedPoints + excludedPoints < 10 || medianAccuracyM == null -> "不安定"
        medianAccuracyM > 15f || lowAccuracyDurationMs > observedDurationMs * .3 -> "不安定"
        medianAccuracyM > 7f || lowAccuracyDurationMs > observedDurationMs * .1 -> "普通"
        else -> "良好"
    }
}

/** Map fixes are independent of distance decisions. No ratio correction or forced target distance.
 * A short, accuracy-sized deadband coalesces jitter. Consistent small steps can pass earlier;
 * reversal inside the uncertainty radius is deliberately not treated as proven movement. */
@Serializable
internal class GpsDistanceAccumulator {
    private var anchor: LatLngPoint? = null
    private var previous: LatLngPoint? = null
    private val window = mutableListOf<LatLngPoint>()
    private var observationTimeMs: Long = 0
    private var observationSegment: Int = -1
    private val accuracies = mutableListOf<Float>()
    private var accepted = 0
    private var excluded = 0
    private var lowMs = 0L
    private var observedMs = 0L
    var rawMeters = 0.0; private set
    var distanceMeters = 0.0; private set

    fun add(point: LatLngPoint, speedMps: Float? = null, config: GpsFilterConfig = GpsFilterConfig()): Boolean {
        val last = previous
        val observed = observationTimeMs
        if (observed > 0 && point.timeMs <= observed) return false
        val observedSegment = observationSegment
        observationTimeMs = point.timeMs
        observationSegment = point.segment
        val same = last != null && point.segment == last.segment
        val dt = if (observed > 0 && point.segment == observedSegment) (point.timeMs-observed).coerceIn(0, 15_000) else 0L
        observedMs += dt
        val accuracy = point.accuracyM
        if (accuracy != null && accuracy.isFinite() && accuracy > 0) accuracies += accuracy
        if (accuracy == null || !accuracy.isFinite() || accuracy <= 0f || accuracy > 10f) lowMs += dt
        val valid = GpsTrackFilter.segmentMeters(null, point, speedMps, config) != null
        val plausible = !same || (point.timeMs-last!!.timeMs > 15_000) ||
            RouteMatching.distance(last,point) / ((point.timeMs-last.timeMs)/1000.0) <= config.maximumSpeedMps
        if (!valid || !plausible) { excluded++; return false }
        if (same && point.timeMs-last!!.timeMs <= 15_000) rawMeters += RouteMatching.distance(last,point)
        previous = point
        val start = anchor
        if (start == null || start.segment != point.segment ||
            same && point.timeMs-last!!.timeMs > 15_000) {
            anchor = point; window.clear(); excluded++; return true
        }
        window.add(point)
        while (window.size > 5) window.removeAt(0)
        val displacement = RouteMatching.distance(start,point)
        val uncertainty = max(start.accuracyM ?: accuracy!!, accuracy!!).toDouble()
        val threshold = max(config.minimumMovementM, uncertainty * 1.25)
        // Three consecutive fixes progressing in roughly one direction support genuine slow movement.
        val path = listOf(start) + window.toList()
        val length = path.zipWithNext().sumOf { RouteMatching.distance(it.first,it.second) }
        val coherent = window.size >= 3 && point.timeMs-start.timeMs >= 2_000 &&
            length > 0 && displacement / length >= .9 && displacement >= max(2.0, uncertainty * .75)
        if (displacement >= threshold || coherent) {
            distanceMeters += displacement; accepted++; anchor = point; window.clear()
        } else excluded++
        return true
    }
    fun report(): GpsQualityReport {
        val a = accuracies.sorted()
        val median = if (a.isEmpty()) null else if (a.size % 2 == 1) a[a.size/2] else (a[a.size/2-1]+a[a.size/2])/2
        return GpsQualityReport(accepted,excluded,median,lowMs,observedMs,rawMeters,distanceMeters)
    }
}
