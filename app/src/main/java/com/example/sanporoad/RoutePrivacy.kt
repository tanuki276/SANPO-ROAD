package com.example.sanporoad

/** Remove points near BOTH endpoints, even on later revisits. Segment IDs prevent connecting
 * across a hidden area. Never send hidden coordinates or exact timestamps to the share API. */
object RoutePrivacy {
    fun mask(points: List<LatLngPoint>, radiusMeters: Double = 150.0): List<LatLngPoint> {
        require(radiusMeters.isFinite() && radiusMeters >= 100)
        if (points.isEmpty()) return emptyList()
        val start=points.first(); val end=points.last()
        var segment=0; var hidden=true; var original: Int?=null
        return points.mapNotNull { p ->
            if (RouteMatching.distance(start,p) < radiusMeters || RouteMatching.distance(end,p) < radiusMeters) {
                hidden=true; null
            } else {
                if (hidden || original != p.segment) segment++
                hidden=false; original=p.segment
                p.copy(timeMs=0, accuracyM=null, segment=segment)
            }
        }
    }
}
