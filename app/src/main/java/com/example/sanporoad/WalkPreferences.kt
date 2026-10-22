package com.example.sanporoad

import android.content.Context

class WalkPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("sanpo_preferences", Context.MODE_PRIVATE)

    var weeklyGoalMeters: Double
        get() = prefs.getString(KEY_WEEKLY_GOAL, null)?.toDoubleOrNull() ?: DEFAULT_WEEKLY_GOAL_METERS
        set(value) {
            prefs.edit().putString(KEY_WEEKLY_GOAL, value.coerceIn(500.0, 100_000.0).toString()).apply()
        }

    var useWalkGoal: Boolean
        get() = prefs.getBoolean("useWalkGoal", false)
        set(value) { prefs.edit().putBoolean("useWalkGoal", value).apply() }

    var cloudSync: Boolean
        get() = prefs.getBoolean("cloudSync", false)
        set(value) { prefs.edit().putBoolean("cloudSync", value).commit() }
    var photoSync: Boolean
        get() = prefs.getBoolean("photoSync", false)
        set(value) { prefs.edit().putBoolean("photoSync", value).commit() }
    var shareLocation: Boolean
        get() = prefs.getBoolean("shareLocation", false)
        set(value) { prefs.edit().putBoolean("shareLocation", value).commit() }
    var activeAccountId: String?
        get() = prefs.getString("activeAccountId", null)
        set(value) { prefs.edit().putString("activeAccountId", value).commit() }
    var accountEmail: String?
        get() = prefs.getString("accountEmail", null)
        set(value) { prefs.edit().putString("accountEmail", value).commit() }

    companion object {
        const val DEFAULT_WEEKLY_GOAL_METERS = 10_000.0
        private const val KEY_WEEKLY_GOAL = "weeklyGoalMeters"
    }
}

