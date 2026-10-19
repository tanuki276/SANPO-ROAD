package com.example.sanporoad

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.roundToInt

class LocationTrackingService : Service() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!isRecording(this@LocationTrackingService)) return
            val points = getPoints(this@LocationTrackingService).toMutableList()
            var distance = getDistanceMeters(this@LocationTrackingService)
            var changed = false
            val elapsedNow = SystemClock.elapsedRealtimeNanos()
            for (loc in result.locations.sortedBy { it.elapsedRealtimeNanos }) {
                // Use the monotonic clock: changing the device clock must not make a stale fix fresh.
                val ageNanos = elapsedNow - loc.elapsedRealtimeNanos
                if (ageNanos < 0 || ageNanos > MAX_LOCATION_AGE_MS * 1_000_000L) continue
                if (loc.time < getStartedAt(this@LocationTrackingService)) continue
                val accuracy = if (loc.hasAccuracy()) loc.accuracy else Float.NaN
                if (accuracy.isFinite() && accuracy > 0f) {
                    put(this@LocationTrackingService, KEY_LAST_ACCURACY, accuracy.toDouble())
                }
                val candidate = LatLngPoint(loc.latitude, loc.longitude, loc.time, accuracy)
                val segment = GpsTrackFilter.segmentMeters(
                    points.lastOrNull(), candidate,
                    if (loc.hasSpeed()) loc.speed else null
                ) ?: continue
                points += candidate
                distance += segment
                changed = true
            }
            // Persist a batch together instead of serializing the entire route for every fix.
            if (changed) persistPoints(points, distance)
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            ACTION_START -> startTracking()
            null -> if (isRecording(this)) startTracking() else stopSelf()
        }
        return if (isRecording(this)) START_STICKY else START_NOT_STICKY
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
            put(this, KEY_RECORDING, true)
            put(this, KEY_PENDING, false)
            put(this, KEY_STARTED_AT, System.currentTimeMillis())
            put(this, KEY_STOPPED_AT, 0L)
            put(this, KEY_POINTS, "[]")
            put(this, KEY_DISTANCE, 0.0)
            put(this, KEY_LAST_ACCURACY, -1.0)
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMaxUpdateDelayMillis(0L)
            .setMaxUpdateAgeMillis(0L)
            .setWaitForAccurateLocation(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            fused.requestLocationUpdates(request, callback, mainLooper)
        } catch (_: SecurityException) {
            put(this, KEY_STOPPED_AT, System.currentTimeMillis())
            put(this, KEY_RECORDING, false)
            put(this, KEY_PENDING, true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopTracking() {
        val wasRecording = isRecording(this)
        fused.removeLocationUpdates(callback)

        if (wasRecording) {
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
        super.onDestroy()
    }

    private fun persistPoints(points: List<LatLngPoint>, distance: Double) {
        prefs(this).edit()
            .putString(KEY_POINTS, JsonConfig.encodeToString(ListSerializer(LatLngPoint.serializer()), points))
            .putString(KEY_DISTANCE, distance.toString())
            .apply()
    }

    private fun buildNotification(): Notification {
        val accuracy = getLastAccuracyMeters(this)
        val suffix = if (accuracy > 0) " / GPS ±${accuracy.roundToInt()}m" else ""
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopWalk = PendingIntent.getService(
            this, 1, Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val distance = String.format(java.util.Locale.JAPAN, "%.2f km", getDistanceMeters(this) / 1000.0)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("サンポロード")
            .setContentText("$distance ・ 散歩を記録中$suffix")
            .setContentIntent(openApp)
            .setWhen(getStartedAt(this))
            .setUsesChronometer(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "散歩を終了", stopWalk)
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
        private const val KEY_PENDING = "pending"
        private const val KEY_STARTED_AT = "startedAt"
        private const val KEY_STOPPED_AT = "stoppedAt"
        private const val KEY_POINTS = "points"
        private const val KEY_DISTANCE = "distance"
        private const val KEY_LAST_ACCURACY = "lastAccuracy"

        private const val MAX_LOCATION_AGE_MS = 15_000L

        const val ACTION_START = "com.example.sanporoad.START"
        const val ACTION_STOP = "com.example.sanporoad.STOP"

        fun start(context: Context) {
            if (isRecording(context) || hasPendingRecording(context)) return
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
            return (end - startedAt).coerceAtLeast(0L)
        }

        fun getDistanceMeters(context: Context): Double = runCatching {
            prefs(context).getString(KEY_DISTANCE, null)?.toDouble()
                ?: prefs(context).getFloat(KEY_DISTANCE, 0f).toDouble()
        }.getOrDefault(0.0)

        fun getLastAccuracyMeters(context: Context): Double = runCatching {
            prefs(context).getString(KEY_LAST_ACCURACY, null)?.toDouble() ?: -1.0
        }.getOrDefault(-1.0)

        fun getPoints(context: Context): List<LatLngPoint> = runCatching {
            JsonConfig.decodeFromString<List<LatLngPoint>>(prefs(context).getString(KEY_POINTS, "[]") ?: "[]")
        }.getOrDefault(emptyList())

        fun clearPending(context: Context) {
            prefs(context).edit()
                .putBoolean(KEY_PENDING, false)
                .putBoolean(KEY_RECORDING, false)
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

    }

    private object JsonConfig {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        fun <T> encodeToString(serializer: kotlinx.serialization.KSerializer<T>, value: T): String = json.encodeToString(serializer, value)
        inline fun <reified T> decodeFromString(value: String): T = json.decodeFromString(value)
    }
}


