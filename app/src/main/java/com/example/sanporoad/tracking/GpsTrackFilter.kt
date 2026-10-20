package com.example.sanporoad

import kotlin.math.*

/** Pure filtering logic shared by the live recorder and regression tests. */
internal object GpsTrackFilter {
    /** Null means rejected; zero is an accepted first fix. Times are measurement times. */
    fun segmentMeters(previous: LatLngPoint?, candidate: LatLngPoint, speedMps: Float? = null, config: GpsFilterConfig = GpsFilterConfig()): Double? {
        val accuracy = candidate.accuracyM ?: return null
        if (!candidate.lat.isFinite() || candidate.lat !in -90.0..90.0 ||
            !candidate.lng.isFinite() || candidate.lng !in -180.0..180.0 ||
            !accuracy.isFinite() || accuracy <= 0f || accuracy > config.maximumAccuracyM) return null
        if (speedMps != null && (!speedMps.isFinite() || speedMps < 0f || speedMps > config.maximumReportedSpeedMps)) return null
        // Apply the same accuracy requirement at startup as during the walk.
        if (previous == null) return 0.0
        if (candidate.timeMs <= previous.timeMs) return null
        if (previous.segment != candidate.segment) return 0.0

        val seconds = (candidate.timeMs - previous.timeMs) / 1000.0
        val segment = distanceMeters(previous, candidate)
        val previousAccuracy = previous.accuracyM?.takeIf { it.isFinite() && it > 0f } ?: accuracy
        val noise = max(config.minimumMovementM, max(previousAccuracy, accuracy).toDouble() * config.noiseRadiusFactor)
        if (segment < noise) return null
        if (segment / seconds > config.maximumSpeedMps) return null
        // No fixed 45 m ceiling: a valid fix after an outage may be farther away.
        // The speed bound still rejects implausible jumps. Missing paths cannot be reconstructed;
        // the segment across an outage is a straight-line estimate, not the actual walked path.
        return segment
    }

    private fun distanceMeters(a: LatLngPoint, b: LatLngPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lng - a.lng)
        val h = (sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) *
            cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)).coerceIn(0.0, 1.0)
        return 6_371_000.0 * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
}

