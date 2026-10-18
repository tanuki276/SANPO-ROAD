package com.example.sanporoad

enum class HistorySort(val label: String) {
    NEWEST("新しい順"), LONGEST("距離順"), DURATION("時間順")
}

fun filterWalkHistory(routes: List<SavedRoute>, query: String, sort: HistorySort): List<SavedRoute> {
    val words = query.trim().split(Regex("[\\s\\u3000]+")).filter { it.isNotEmpty() }
    val matching = routes.filter { route ->
        words.all { word ->
            route.name.contains(word, ignoreCase = true) || route.note.contains(word, ignoreCase = true)
        }
    }
    val comparator = when (sort) {
        HistorySort.NEWEST -> compareByDescending<SavedRoute> { it.startedAt }
        HistorySort.LONGEST -> compareByDescending<SavedRoute> { it.distanceMeters }.thenByDescending { it.startedAt }
        HistorySort.DURATION -> compareByDescending<SavedRoute> { it.durationMs }.thenByDescending { it.startedAt }
    }.thenByDescending { it.id }
    return matching.sortedWith(comparator)
}
