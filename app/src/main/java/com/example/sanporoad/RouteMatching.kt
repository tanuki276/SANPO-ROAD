package com.example.sanporoad
import kotlin.math.*

object RouteMatching {
    fun distance(a: LatLngPoint,b: LatLngPoint): Double {
        val x = sin(Math.toRadians(b.lat-a.lat)/2).pow(2) + cos(Math.toRadians(a.lat))*cos(Math.toRadians(b.lat))*sin(Math.toRadians(b.lng-a.lng)/2).pow(2)
        return 12_742_000 * asin(sqrt(x.coerceIn(0.0,1.0)))
    }
    fun distanceToRoute(point: LatLngPoint, route: List<LatLngPoint>): Double {
        if (route.isEmpty()) return Double.POSITIVE_INFINITY
        var best = route.minOf { distance(point,it) }
        route.zipWithNext().filter { it.first.segment == it.second.segment }.forEach { (a,b) ->
            val cosLat = cos(Math.toRadians(point.lat))
            fun xy(p: LatLngPoint) = Pair(Math.toRadians(p.lng-point.lng)*6_371_000*cosLat,Math.toRadians(p.lat-point.lat)*6_371_000)
            val (ax,ay)=xy(a);val (bx,by)=xy(b);val dx=bx-ax;val dy=by-ay
            val t = if (dx*dx+dy*dy == 0.0) 0.0 else ((-ax*dx-ay*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
            best = min(best,hypot(ax+t*dx,ay+t*dy))
        }
        return best
    }
    private fun samples(points: List<LatLngPoint>): List<LatLngPoint> {
        if (points.size <= 128) return points
        return (0..127).map { points[it*(points.size-1)/127] }
    }
    fun sameCourse(a: SavedRoute,b: SavedRoute): Boolean {
        if(a.points.size < 2 || b.points.size < 2 || a.distanceMeters <= 0 || b.distanceMeters <= 0) return false
        if(distance(a.points.first(),b.points.first()) > 80 || distance(a.points.last(),b.points.last()) > 80) return false
        if(abs(a.distanceMeters-b.distanceMeters)/max(a.distanceMeters,b.distanceMeters) > 0.15) return false
        fun coverage(x: SavedRoute,y: SavedRoute): Double = samples(x.points).let { points -> points.count { distanceToRoute(it,y.points) <= 50 }.toDouble()/points.size }
        return coverage(a,b) >= 0.85 && coverage(b,a) >= 0.85
    }
}
data class ComparisonMetric(val label: String, val current: Double?, val previous: Double?, val unit: String, val lowerIsBetter: Boolean? = null) {
    val delta: Double? get() = if(current != null && previous != null) current-previous else null
    val assessment: String get() = delta?.let { if(abs(it)<0.01) "同じ" else if(lowerIsBetter == null) "差分" else if((it<0)==lowerIsBetter) "改善" else "増加・低下" } ?: "比較データなし"
}
fun compareRoutes(current: SavedRoute,previous: SavedRoute): List<ComparisonMetric> {
    fun pace(r: SavedRoute) = if(r.distanceMeters > 0) r.durationMs/r.distanceMeters else null // seconds/km
    fun accuracy(r: SavedRoute): Double? = r.points.mapNotNull { it.accuracyM?.takeIf { v -> v.isFinite() && v > 0 } }.takeIf { it.isNotEmpty() }?.average()
    return listOf(
        ComparisonMetric("距離",current.distanceMeters,previous.distanceMeters,"m"),
        ComparisonMetric("所要時間",(current.durationMs+current.restDurationMs)/1000.0,(previous.durationMs+previous.restDurationMs)/1000.0,"秒"),
        ComparisonMetric("平均ペース",pace(current),pace(previous),"秒/km",true),
        ComparisonMetric("移動時間（休憩を除く記録時間）",current.durationMs/1000.0,previous.durationMs/1000.0,"秒"),
        ComparisonMetric("休憩時間",current.restDurationMs/1000.0,previous.restDurationMs/1000.0,"秒"),
        ComparisonMetric("平均GPS推定誤差",accuracy(current),accuracy(previous),"m",true))
}
