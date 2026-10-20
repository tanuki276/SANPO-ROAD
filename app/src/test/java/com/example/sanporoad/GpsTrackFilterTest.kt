package com.example.sanporoad

import org.junit.Assert.*
import org.junit.Test

class GpsTrackFilterTest {
    private fun point(northMeters: Double, time: Long, accuracy: Float = 5f) =
        LatLngPoint(northMeters / 111_194.9266, 0.0, time, accuracy)

    @Test fun acceptsUsableFirstFixWithoutWaitingFor18Meters() {
        assertEquals(0.0, GpsTrackFilter.segmentMeters(null, point(0.0, 1000, 22f))!!, 0.0)
    }

    @Test fun resumesBeyondOld45MeterLimitAfterOutage() {
        val start = point(0.0, 1000)
        val recovered = point(100.0, 81000)
        assertEquals(100.0, GpsTrackFilter.segmentMeters(start, recovered)!!, 0.01)
        assertEquals(5.0, GpsTrackFilter.segmentMeters(recovered, point(105.0, 85000))!!, 0.01)
    }

    @Test fun rejectsSuddenJumpAndThenAcceptsNormalFix() {
        val start = point(0.0, 1000)
        assertNull(GpsTrackFilter.segmentMeters(start, point(100.0, 3000)))
        assertNotNull(GpsTrackFilter.segmentMeters(start, point(5.0, 5000)))
    }

    @Test fun rejectsDuplicateAndOutOfOrderMeasurements() {
        val start = point(0.0, 5000)
        assertNull(GpsTrackFilter.segmentMeters(start, point(5.0, 5000)))
        assertNull(GpsTrackFilter.segmentMeters(start, point(5.0, 4000)))
    }

    @Test fun suppressesStationaryJitterButAccumulatesSlowMovement() {
        val start = point(0.0, 1000)
        assertNull(GpsTrackFilter.segmentMeters(start, point(1.0, 3000)))
        assertNull(GpsTrackFilter.segmentMeters(start, point(2.0, 5000)))
        assertNotNull(GpsTrackFilter.segmentMeters(start, point(4.0, 9000)))
    }

    @Test fun keepsDetailedMovementWhenAccuracyIsHigh() {
        assertNotNull(GpsTrackFilter.segmentMeters(point(0.0, 1000, 2f), point(2.5, 3000, 2f)))
    }

    @Test fun rejectsInvalidCoordinatesAccuracyAndVehicleSpeed() {
        for (accuracy in listOf(0f, -1f, 26f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(GpsTrackFilter.segmentMeters(null, point(0.0, 1000, accuracy)))
        }
        assertNull(GpsTrackFilter.segmentMeters(null, point(0.0, 1000).copy(lat = Double.NaN)))
        assertNull(GpsTrackFilter.segmentMeters(null, point(0.0, 1000).copy(lng = 181.0)))
        assertNull(GpsTrackFilter.segmentMeters(null, point(0.0, 1000), 7f))
    }

    @Test fun batchedFixesUseMeasurementTimesAndKeepIntermediateCorner() {
        val batch = listOf(point(0.0, 1000), point(5.0, 5000),
            point(5.0, 9000).copy(lng = 5.0 / 111_194.9266))
        val accepted = mutableListOf<LatLngPoint>()
        var distance = 0.0
        for (candidate in batch) {
            val segment = GpsTrackFilter.segmentMeters(accepted.lastOrNull(), candidate) ?: continue
            accepted += candidate
            distance += segment
        }
        assertEquals(3, accepted.size)
        assertEquals(10.0, distance, 0.01)
    }
}

