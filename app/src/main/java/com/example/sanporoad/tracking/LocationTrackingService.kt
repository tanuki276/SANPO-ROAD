package com.example.sanporoad

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.*
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.serialization.builtins.ListSerializer
import org.osmdroid.util.GeoPoint
import kotlin.math.*

class LocationTrackingService : Service() {
    private var distanceAccumulator: GpsDistanceAccumulator? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            worker.launch {
                if (!isRecording(this@LocationTrackingService) || isPaused(this@LocationTrackingService)) return@launch
                try {
                    val accepted = mutableListOf<LatLngPoint>()
                    val accumulator = distanceAccumulator ?: LiveTrackStore.accumulator(this@LocationTrackingService) ?: GpsDistanceAccumulator().also { restored ->
                        getPoints(this@LocationTrackingService).forEach { restored.add(it) }
                        distanceAccumulator = restored
                    }
                    val now = SystemClock.elapsedRealtimeNanos()
                    for (loc in result.locations.sortedBy { it.elapsedRealtimeNanos }) {
                        if (!isFreshFix(now, loc.elapsedRealtimeNanos) || loc.time < getStartedAt(this@LocationTrackingService)) continue
                        if (loc.time < prefs(this@LocationTrackingService).getLong(KEY_RESUMED_AT, 0)) continue
                        val accuracy = if (loc.hasAccuracy()) loc.accuracy else Float.NaN
                        if (accuracy.isFinite() && accuracy > 0) {
                            put(this@LocationTrackingService, KEY_LAST_ACCURACY, accuracy.toDouble())
                            put(this@LocationTrackingService, KEY_FIX_ELAPSED, loc.elapsedRealtimeNanos)
                        }
                        val candidate = LatLngPoint(loc.latitude,loc.longitude,loc.time,accuracy,
                            prefs(this@LocationTrackingService).getLong(KEY_SEGMENT,0).toInt())
                        // Store every valid map fix, including fixes inside the distance deadband.
                        if (accumulator.add(candidate, if(loc.hasSpeed()) loc.speed else null,
                                GpsSettings(this@LocationTrackingService).mode.filter)) accepted += candidate
                    }
                    LiveTrackStore.append(this@LocationTrackingService, accepted, accumulator)
                    put(this@LocationTrackingService,KEY_DISTANCE,accumulator.distanceMeters)
                    put(this@LocationTrackingService, "gpsQuality", kotlinx.serialization.json.Json.encodeToString(GpsQualityReport.serializer(), accumulator.report()))
                    updateNotification()
                } catch (_: Exception) { pauseTracking(); updateNotification() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter foreground immediately; disk work is serialized away from the UI thread.
        if (intent?.action == ACTION_START || isRecording(this)) startForeground(NOTIFICATION_ID, buildNotification())
        worker.launch {
            try {
                when (intent?.action) {
                    ACTION_STOP -> stopTracking()
                    ACTION_PAUSE -> pauseTracking()
                    ACTION_RESUME -> resumeTracking()
                    ACTION_MODE -> if (isRecording(this@LocationTrackingService)) startTracking()
                    ACTION_START -> startTracking()
                    null -> if (isRecording(this@LocationTrackingService)) startTracking() else stopSelf()
                }
            } catch (_: Exception) { pauseTracking(); updateNotification() }
        }
        return if (intent?.action == ACTION_START || isRecording(this)) START_STICKY else START_NOT_STICKY
    }

    private fun startTracking() {
        // A repeated start must never overwrite a walk awaiting save/discard.
        if (hasPendingRecording(this)) {
            startForeground(NOTIFICATION_ID, buildNotification())
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (!isRecording(this)) {
            distanceAccumulator = GpsDistanceAccumulator()
            put(this, "gpsQuality", "")
            put(this, KEY_RECORDING, true)
            put(this, KEY_PENDING, false)
            put(this, KEY_STARTED_AT, System.currentTimeMillis())
            put(this, KEY_SESSION_ID, java.util.UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE))
            put(this, KEY_PAUSED_AT, 0L)
            put(this, "restIntervals", "[]")
            put(this, KEY_PAUSED_TOTAL, 0L)
            put(this, KEY_SEGMENT, 0L)
            put(this, KEY_STOPPED_AT, 0L)
            put(this, KEY_POINTS, "[]")
            put(this, KEY_DISTANCE, 0.0)
            put(this, KEY_LAST_ACCURACY, -1.0)
            put(this, KEY_RESUMED_AT, 0L)
            put(this, KEY_FIX_ELAPSED, 0L)
        }

        LiveTrackStore.load(this)
        if (distanceAccumulator == null) distanceAccumulator = LiveTrackStore.accumulator(this) ?: GpsDistanceAccumulator().also { accumulator ->
            getPoints(this).forEach { accumulator.add(it) }
        }
        put(this, KEY_DISTANCE, distanceAccumulator!!.distanceMeters)
        put(this, "gpsQuality", kotlinx.serialization.json.Json.encodeToString(GpsQualityReport.serializer(), distanceAccumulator!!.report()))
        val request = GpsSettings(this).request()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            fused.removeLocationUpdates(callback).addOnCompleteListener {
                if (isRecording(this) && !isPaused(this)) fused.requestLocationUpdates(request, callback, mainLooper)
                    .addOnFailureListener { worker.launch { pauseTracking(); updateNotification() } }
            }
        } catch (_: SecurityException) {
            put(this, KEY_STOPPED_AT, System.currentTimeMillis())
            put(this, KEY_RECORDING, false)
            put(this, KEY_PENDING, true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun pauseTracking() {
        if (!isRecording(this) || isPaused(this)) return
        put(this, KEY_PAUSED_AT, System.currentTimeMillis())
        fused.removeLocationUpdates(callback)
        updateNotification()
    }

    private fun resumeTracking() {
        if (!isRecording(this) || !isPaused(this)) return
        finishPause()
        put(this, KEY_RESUMED_AT, System.currentTimeMillis())
        put(this, KEY_SEGMENT, prefs(this).getLong(KEY_SEGMENT, 0L) + 1L)
        put(this, KEY_LAST_ACCURACY, -1.0)
        startTracking()
    }

    private fun finishPause() {
        val pausedAt = prefs(this).getLong(KEY_PAUSED_AT, 0L)
        if (pausedAt > 0L) {
            val intervals = getRestIntervals(this) + RestInterval(pausedAt, System.currentTimeMillis().coerceAtLeast(pausedAt))
            put(this, "restIntervals", kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(RestInterval.serializer()), intervals))
            put(this, KEY_PAUSED_TOTAL, prefs(this).getLong(KEY_PAUSED_TOTAL, 0L) + (System.currentTimeMillis() - pausedAt).coerceAtLeast(0))
            put(this, KEY_PAUSED_AT, 0L)
        }
    }

    private fun stopTracking() {
        val wasRecording = isRecording(this)
        fused.removeLocationUpdates(callback)

        if (wasRecording) {
            finishPause()
            put(this, KEY_STOPPED_AT, System.currentTimeMillis())
            put(this, KEY_RECORDING, false)
            put(this, KEY_PENDING, true)
        } else {
            put(this, KEY_RECORDING, false)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        worker.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val accuracy = getLastAccuracyMeters(this)
        val suffix = if (accuracy > 0) " / GPS ±${accuracy.roundToInt()}m" else " / 現在地取得中"
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopWalk = PendingIntent.getService(
            this, 1, Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, LocationTrackingService::class.java).setAction(if (isPaused(this)) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val distance = String.format(java.util.Locale.JAPAN, "%.2f km", getDistanceMeters(this) / 1000.0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("サンポロード")
            .setContentText(LiveTrackStore.error ?: if (isPaused(this)) "$distance ・ 一時停止中" else "$distance ・ 散歩を記録中$suffix")
            .setContentIntent(openApp)
            .setWhen(System.currentTimeMillis() - getDurationMs(this))
            .setUsesChronometer(!isPaused(this))
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_play, if (isPaused(this)) "再開" else "一時停止", toggle)
            .addAction(android.R.drawable.ic_menu_save, "散歩を終了", stopWalk)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "散歩の位置情報", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "sanpo_location"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "sanpo_tracking"
        private const val KEY_RECORDING = "recording"
        private const val KEY_SESSION_ID = "sessionId"
        private const val KEY_PAUSED_AT = "pausedAt"
        private const val KEY_PAUSED_TOTAL = "pausedTotal"
        private const val KEY_SEGMENT = "segment"
        private const val KEY_PENDING = "pending"
        private const val KEY_STARTED_AT = "startedAt"
        private const val KEY_STOPPED_AT = "stoppedAt"
        private const val KEY_POINTS = "points"
        private const val KEY_DISTANCE = "distance"
        private const val KEY_RESUMED_AT = "resumedAt"
        private const val KEY_FIX_ELAPSED = "fixElapsed"
        private const val KEY_SOURCE = "sourceRouteId"
        private const val KEY_LAST_ACCURACY = "lastAccuracy"

        private const val MAX_LOCATION_AGE_MS = 8_000L
        private const val MAX_ACCEPTED_ACCURACY_M = 25f
        private const val FIRST_FIX_MAX_ACCURACY_M = 18f
        private const val MIN_MOVEMENT_METERS = 4.0
        private const val NOISE_RADIUS_FACTOR = 0.65
        private const val MAX_CALCULATED_SPEED_MPS = 5.5 // 19.8 km/h: fast running, above normal walking
        private const val MAX_REPORTED_SPEED_MPS = 6.0f
        private const val MAX_NORMAL_SEGMENT_METERS = 45.0
        private const val JUMP_ACCURACY_FACTOR = 1.8

        const val ACTION_MODE = "com.example.sanporoad.MODE"
        const val ACTION_START = "com.example.sanporoad.START"
        const val ACTION_STOP = "com.example.sanporoad.STOP"
        const val ACTION_PAUSE = "com.example.sanporoad.PAUSE"
        const val ACTION_RESUME = "com.example.sanporoad.RESUME"
        fun isPaused(context: Context) = prefs(context).getLong(KEY_PAUSED_AT, 0L) > 0L
        fun getSessionId(context: Context) = prefs(context).getLong(KEY_SESSION_ID, getStartedAt(context))
        fun togglePause(context: Context) {
            context.startService(Intent(context, LocationTrackingService::class.java).setAction(if (isPaused(context)) ACTION_RESUME else ACTION_PAUSE))
        }

        fun start(context: Context, sourceRouteId: Long? = null) {
            if (isRecording(context) || hasPendingRecording(context)) return
            put(context, KEY_SOURCE, sourceRouteId ?: 0L)
            val intent = Intent(context, LocationTrackingService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP))
        }

        fun isRecording(context: Context) = prefs(context).getBoolean(KEY_RECORDING, false)
        fun hasPendingRecording(context: Context) = prefs(context).getBoolean(KEY_PENDING, false)
        fun getStartedAt(context: Context) = prefs(context).getLong(KEY_STARTED_AT, 0L)
        fun getStoppedAt(context: Context) = prefs(context).getLong(KEY_STOPPED_AT, 0L)

        fun getDurationMs(context: Context): Long {
            val startedAt = getStartedAt(context)
            if (startedAt <= 0L) return 0L
            val end = if (isRecording(context)) {
                System.currentTimeMillis()
            } else {
                getStoppedAt(context).takeIf { it >= startedAt } ?: startedAt
            }
            return activeDuration(startedAt, end, prefs(context).getLong(KEY_PAUSED_TOTAL, 0L), prefs(context).getLong(KEY_PAUSED_AT, 0L))
        }

        fun getDistanceMeters(context: Context): Double = runCatching {
            prefs(context).getString(KEY_DISTANCE, null)?.toDouble()
                ?: prefs(context).getFloat(KEY_DISTANCE, 0f).toDouble()
        }.getOrDefault(0.0)

        fun getSourceRouteId(context: Context) = prefs(context).getLong(KEY_SOURCE, 0).takeIf { it > 0 }
        fun getRestDurationMs(context: Context): Long = prefs(context).getLong(KEY_PAUSED_TOTAL, 0) +
            prefs(context).getLong(KEY_PAUSED_AT, 0).takeIf { it > 0 }?.let { (System.currentTimeMillis()-it).coerceAtLeast(0) }.orEmptyDuration()
        private fun Long?.orEmptyDuration() = this ?: 0L
        fun getRestIntervals(context: Context): List<RestInterval> = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<List<RestInterval>>(prefs(context).getString("restIntervals", "[]").orEmpty())
        }.getOrDefault(emptyList())
        fun getGpsQuality(context: Context): GpsQualityReport? = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<GpsQualityReport>(prefs(context).getString("gpsQuality", "").orEmpty())
        }.getOrNull()
        fun getLastAccuracyMeters(context: Context): Double = runCatching {
            if (!isFreshFix(SystemClock.elapsedRealtimeNanos(), prefs(context).getLong(KEY_FIX_ELAPSED,0))) return -1.0
            prefs(context).getString(KEY_LAST_ACCURACY, null)?.toDouble() ?: -1.0
        }.getOrDefault(-1.0)

        fun getPoints(context: Context): List<LatLngPoint> = LiveTrackStore.points()

        fun clearPending(context: Context) {
            LiveTrackStore.clear(context)
            prefs(context).edit()
                .putBoolean(KEY_PENDING, false)
                .putBoolean(KEY_RECORDING, false)
                .remove(KEY_SESSION_ID)
                .remove(KEY_SOURCE)
                .remove(KEY_FIX_ELAPSED)
                .remove(KEY_RESUMED_AT)
                .remove(KEY_PAUSED_AT)
                .remove(KEY_PAUSED_TOTAL)
                .remove(KEY_SEGMENT)
                .remove(KEY_POINTS)
                .remove(KEY_DISTANCE)
                .remove(KEY_STARTED_AT)
                .remove(KEY_STOPPED_AT)
                .remove(KEY_LAST_ACCURACY)
                .apply()
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun put(context: Context, key: String, value: Any) {
            val edit = prefs(context).edit()
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Double -> edit.putString(key, value.toString())
                is String -> edit.putString(key, value)
            }
            edit.apply()
        }

        private fun haversine(a: GeoPoint, b: GeoPoint): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val x = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
            return r * 2 * atan2(sqrt(x), sqrt(1 - x))
        }
    }

    private object JsonConfig {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        fun <T> encodeToString(serializer: kotlinx.serialization.KSerializer<T>, value: T): String = json.encodeToString(serializer, value)
        inline fun <reified T> decodeFromString(value: String): T = json.decodeFromString(value)
    }
}



