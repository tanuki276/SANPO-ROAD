package com.example.sanporoad

import android.content.Context

class WalkPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("sanpo_preferences", Context.MODE_PRIVATE)

    var weeklyGoalMeters: Double
        get() = prefs.getString(KEY_WEEKLY_GOAL, null)?.toDoubleOrNull() ?: DEFAULT_WEEKLY_GOAL_METERS
        set(value) {
            prefs.edit().putString(KEY_WEEKLY_GOAL, value.coerceIn(500.0, 100_000.0).toString()).apply()
        }

    companion object {
        const val DEFAULT_WEEKLY_GOAL_METERS = 10_000.0
        private const val KEY_WEEKLY_GOAL = "weeklyGoalMeters"
    }
}
