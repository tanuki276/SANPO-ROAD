package com.example.sanporoad

import java.util.Locale
import kotlin.math.roundToInt

internal fun parseDistanceGoal(text: String): Double? = text.trim().toDoubleOrNull()
    ?.takeIf { it.isFinite() && it > 0 && it <= 1000 }
    ?.times(1000)

internal fun distanceGoalSummary(goal: Double?, actual: Double): String? {
    if (goal == null || !goal.isFinite() || goal <= 0 || !actual.isFinite()) return null
    return String.format(Locale.JAPAN, "目標 %.1fkm / 実績 %.1fkm / %+.1fkm / %d%%",
        goal / 1000, actual / 1000, (actual - goal) / 1000, (actual / goal * 100).roundToInt())
}
