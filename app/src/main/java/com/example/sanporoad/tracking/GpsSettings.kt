package com.example.sanporoad

import android.content.Context
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

data class GpsFilterConfig(val maximumAccuracyM: Float = 25f, val maximumSpeedMps: Double = 5.5,
    val maximumReportedSpeedMps: Float = 6f, val minimumMovementM: Double = 2.0,
    val noiseRadiusFactor: Double = 0.65) {
    init {
        require(maximumAccuracyM.isFinite() && maximumAccuracyM > 0)
        require(maximumSpeedMps.isFinite() && maximumSpeedMps > 0)
        require(maximumReportedSpeedMps.isFinite() && maximumReportedSpeedMps > 0)
        require(minimumMovementM.isFinite() && minimumMovementM >= 0)
        require(noiseRadiusFactor.isFinite() && noiseRadiusFactor >= 0)
    }
}
enum class GpsMode(val intervalMs: Long, val batchMs: Long, val filter: GpsFilterConfig) {
    NORMAL(3000, 6000, GpsFilterConfig(minimumMovementM = 4.0)),
    HIGH_ACCURACY(1000, 0, GpsFilterConfig())
}
class GpsSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("sanpo_gps", Context.MODE_PRIVATE)
    var mode: GpsMode
        get() = if (prefs.getBoolean("highAccuracy", false)) GpsMode.HIGH_ACCURACY else GpsMode.NORMAL
        set(value) {
            check(prefs.edit().putBoolean("highAccuracy", value == GpsMode.HIGH_ACCURACY).commit()) { "GPS設定を保存できませんでした" }
            if (LocationTrackingService.isRecording(context)) context.startService(
                android.content.Intent(context, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_MODE))
        }
    fun request(): LocationRequest = mode.let {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, it.intervalMs)
            .setMinUpdateIntervalMillis(it.intervalMs).setMaxUpdateDelayMillis(it.batchMs)
            .setMaxUpdateAgeMillis(0).setWaitForAccurateLocation(true).build()
    }
}
fun isFreshFix(elapsedNowNanos: Long, measuredNanos: Long, maximumAgeMs: Long = 15_000): Boolean =
    measuredNanos > 0 && elapsedNowNanos >= measuredNanos && (elapsedNowNanos - measuredNanos) / 1_000_000 <= maximumAgeMs
