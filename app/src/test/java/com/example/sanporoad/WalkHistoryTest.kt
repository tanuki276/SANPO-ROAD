package com.example.sanporoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkHistoryTest {
    private val park = SavedRoute(1, "朝の公園", 1000, 900000, 1200.0, emptyList(), "桜とCafe")
    private val river = SavedRoute(2, "川沿い", 3000, 600000, 1800.0, emptyList(), "夕方の散歩")
    private val hill = SavedRoute(3, "丘の公園", 2000, 1200000, 1800.0, emptyList(), "坂道")
    private val routes = listOf(park, river, hill)

    @Test fun blankQueryShowsAllNewestFirst() {
        assertEquals(listOf(river, hill, park), filterWalkHistory(routes, "　 ", HistorySort.NEWEST))
    }

    @Test fun searchesNameAndNoteWithCaseInsensitiveTerms() {
        assertEquals(listOf(park), filterWalkHistory(routes, "公園　CAFE", HistorySort.NEWEST))
        assertEquals(listOf(river), filterWalkHistory(routes, "夕方", HistorySort.NEWEST))
    }

    @Test fun sortsDistanceAndBreaksTiesByDate() {
        assertEquals(listOf(river, hill, park), filterWalkHistory(routes, "", HistorySort.LONGEST))
    }

    @Test fun sortsDurationAndFiltersBeforeSorting() {
        assertEquals(listOf(hill, park), filterWalkHistory(routes, "公園", HistorySort.DURATION))
    }

    @Test fun noMatchAndEmptyHistoryAreEmpty() {
        assertTrue(filterWalkHistory(routes, "海", HistorySort.NEWEST).isEmpty())
        assertTrue(filterWalkHistory(emptyList(), "", HistorySort.DURATION).isEmpty())
    }

    @Test fun neverMutatesSourceRecords() {
        filterWalkHistory(routes, "", HistorySort.DURATION)
        assertEquals(listOf(park, river, hill), routes)
    }
}
