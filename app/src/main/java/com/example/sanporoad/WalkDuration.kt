package com.example.sanporoad

fun activeDuration(startedAt: Long, end: Long, pausedTotal: Long, pausedAt: Long): Long {
    val currentPause = if (pausedAt > 0L) (end - pausedAt).coerceAtLeast(0) else 0L
    return (end - startedAt - pausedTotal - currentPause).coerceAtLeast(0L)
}
