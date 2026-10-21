package com.example.sanporoad

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class WalkGoalTest {
    @Test fun validatesDistanceInput() {
        assertEquals(3000.0, parseDistanceGoal("3.0")!!, 0.0)
        listOf("", "0", "-1", "NaN", "Infinity", "1001", "abc").forEach { assertNull(parseDistanceGoal(it)) }
    }
    @Test fun comparesActualDistance() {
        assertEquals("目標 3.0km / 実績 3.4km / +0.4km / 113%", distanceGoalSummary(3000.0, 3400.0))
        assertEquals("目標 3.0km / 実績 2.0km / -1.0km / 67%", distanceGoalSummary(3000.0, 2000.0))
        assertNull(distanceGoalSummary(null, 3000.0))
    }
    @Test fun oldRecordsAndGoalRoundtrip() {
        val old = """{"id":1,"name":"walk","startedAt":1,"durationMs":1,"distanceMeters":1.0,"points":[]}"""
        val route = Json.decodeFromString<SavedRoute>(old)
        assertNull(route.distanceGoalMeters)
        val withGoal = route.copy(distanceGoalMeters = 3000.0)
        assertEquals(withGoal, Json.decodeFromString<SavedRoute>(Json.encodeToString(SavedRoute.serializer(), withGoal)))
    }
}
