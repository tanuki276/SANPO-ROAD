package com.example.sanporoad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.util.GeoPoint
import kotlin.math.*

class LocationTrackingService : Service() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                // Reject obviously stale fixes and very low-quality readings.
                val now = System.currentTimeMillis()
                if (now - loc.time <= MAX_LOCATION_AGE_MS && (loc.hasAccuracy() && loc.accuracy <= MAX_ACCEPTED_ACCURACY_M || !loc.hasAccuracy())) {
                    addPoint(LatLngPoint(loc.latitude, loc.longitude, now))
                    updateNotification()
                }
            }
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
            // START_STICKY may recreate the service without an Intent. Restore only an
            // actually active recording; never create a new route after a manual stop.
            null -> if (isRecording(this)) startTracking() else stopSelf()
        }
        return if (isRecording(this)) START_STICKY else START_NOT_STICKY
    }

    private fun startTracking() {
        if (!isRecording(this)) {
            put(this, KEY_RECORDING, true)
            put(this, KEY_PENDING, false)
            put(this, KEY_STARTED_AT, System.currentTimeMillis())
            put(this, KEY_POINTS, "[]")
            put(this, KEY_DISTANCE, 0.0)
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
            .setMinUpdateIntervalMillis(750L)
            .setMaxUpdateDelayMillis(3000L)
            .setWaitForAccurateLocation(true)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, mainLooper)
        } catch (_: SecurityException) {
            put(this, KEY_RECORDING, false)
            stopSelf()
        }
    }

    private fun stopTracking() {
        fused.removeLocationUpdates(callback)
        put(this, KEY_RECORDING, false)
        put(this, KEY_PENDING, true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun addPoint(point: LatLngPoint) {
        val points = getPoints(this).toMutableList()
        val previous = points.lastOrNull()
        var distance = getDistanceMeters(this)
        if (previous != null) {
            val segment = haversine(GeoPoint(previous.lat, previous.lng), GeoPoint(point.lat, point.lng))
            // Ignore impossible jumps caused by a bad GPS fix.
            if (segment > MAX_SEGMENT_METERS) return
            distance += segment
        }
        points += point
        put(this, KEY_POINTS, kotlinx.serialization.json.Json.encodeToString(points))
        put(this, KEY_DISTANCE, distance)
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("サンポロード")
        .setContentText("散歩を記録中…")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

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
        private const val KEY_POINTS = "points"
        private const val KEY_DISTANCE = "distance"
        private const val MAX_LOCATION_AGE_MS = 10_000L
        private const val MAX_ACCEPTED_ACCURACY_M = 35f
        private const val MAX_SEGMENT_METERS = 100f
        const val ACTION_START = "com.example.sanporoad.START"
        const val ACTION_STOP = "com.example.sanporoad.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP))
        }

        fun isRecording(context: Context) = prefs(context).getBoolean(KEY_RECORDING, false)
        fun hasPendingRecording(context: Context) = prefs(context).getBoolean(KEY_PENDING, false)
        fun getStartedAt(context: Context) = prefs(context).getLong(KEY_STARTED_AT, 0L)
        fun getDistanceMeters(context: Context): Double = runCatching {
            prefs(context).getString(KEY_DISTANCE, null)?.toDouble()
                ?: prefs(context).getFloat(KEY_DISTANCE, 0f).toDouble()
        }.getOrDefault(0.0)
        fun getPoints(context: Context): List<LatLngPoint> = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<List<LatLngPoint>>(prefs(context).getString(KEY_POINTS, "[]") ?: "[]")
        }.getOrDefault(emptyList())

        fun clearPending(context: Context) {
            prefs(context).edit().putBoolean(KEY_PENDING, false).putBoolean(KEY_RECORDING, false).remove(KEY_POINTS).remove(KEY_DISTANCE).remove(KEY_STARTED_AT).apply()
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
}
