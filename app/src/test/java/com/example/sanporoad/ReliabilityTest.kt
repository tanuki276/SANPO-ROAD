package com.example.sanporoad
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class ReliabilityTest {
    private fun point(n: Double,t: Long=1000) = LatLngPoint(n/111194.9266,0.0,t,2f)
    private fun route(id: Long=1) = SavedRoute(id,"walk",1000,100000,100.0,listOf(point(0.0),point(100.0,101000)))
    @Test fun oldJsonRemainsReadable() {
        val old="""{"id":1,"name":"old","startedAt":1,"durationMs":1000,"distanceMeters":3.0,"points":[]}"""
        val route=Json.decodeFromString<SavedRoute>(old)
        assertEquals("PENDING",route.syncState);assertNull(route.sourceRouteId);assertEquals(0L,route.restDurationMs)
    }
    @Test fun corruptJsonIsNotAnEmptyHistory() {
        assertThrows(Exception::class.java) { Json.decodeFromString<List<SavedRoute>>("[{broken]") }
    }
    @Test fun modesHaveDifferentMovementThresholds() {
        assertNull(GpsTrackFilter.segmentMeters(point(0.0),point(2.5,4000),config=GpsMode.NORMAL.filter))
        assertNotNull(GpsTrackFilter.segmentMeters(point(0.0),point(2.5,4000),config=GpsMode.HIGH_ACCURACY.filter))
        assertTrue(GpsMode.HIGH_ACCURACY.intervalMs < GpsMode.NORMAL.intervalMs)
        assertEquals(0L,GpsMode.HIGH_ACCURACY.batchMs)
    }
    @Test fun changingModeUsesExistingMeasurementTime() {
        val a=point(0.0);val b=point(5.0,5000);val c=point(7.5,8000)
        assertNotNull(GpsTrackFilter.segmentMeters(a,b,config=GpsMode.NORMAL.filter))
        assertNotNull(GpsTrackFilter.segmentMeters(b,c,config=GpsMode.HIGH_ACCURACY.filter))
        assertNull(GpsTrackFilter.segmentMeters(b,b,config=GpsMode.HIGH_ACCURACY.filter))
    }
    @Test fun pauseCreatesNewSegmentWithoutCountingTravelWhilePaused() {
        assertEquals(0.0,GpsTrackFilter.segmentMeters(point(0.0),point(500.0,100000).copy(segment=1))!!,0.0)
    }
    @Test fun freshFixExpiresAndRecoveryIsFresh() {
        assertTrue(isFreshFix(10_000_000_000,9_000_000_000))
        assertFalse(isFreshFix(30_000_000_000,9_000_000_000))
        assertFalse(isFreshFix(10,20))
        assertTrue(isFreshFix(30_000_000_000,29_000_000_000))
    }
    @Test fun rerunDoesNotMutateSourceAndComparisonIncludesRest() {
        val original=route();val rerun=original.copy(id=2,sourceRouteId=original.id,durationMs=90000,restDurationMs=10000)
        assertEquals(100000L,original.durationMs);assertNull(original.sourceRouteId)
        val metrics=compareRoutes(rerun,original)
        assertEquals(0.0,metrics[1].delta!!,0.0);assertEquals(-10.0,metrics[3].delta!!,0.0)
        assertEquals("改善",metrics[2].assessment)
    }
    @Test fun sameCourseChecksGeometryNotOnlyEndpoints() {
        val a=route();assertTrue(RouteMatching.sameCourse(a,a.copy(id=2)))
        val detour=a.copy(points=listOf(point(0.0),point(50.0).copy(lng=0.01),point(100.0)))
        assertFalse(RouteMatching.sameCourse(a,detour))
        assertFalse(RouteMatching.sameCourse(a,a.copy(points=a.points.reversed())))
        assertEquals(0.0,RouteMatching.distanceToRoute(point(50.0),a.points),0.01)
    }
    @Test fun newerPhotoDoesNotReuploadStaleLocalPhoto() {
        val local=route().copy(updatedAt=10,photoHash="old",syncedPhotoHash="before",photoPath="local")
        val remote=route().copy(updatedAt=20,photoHash="new",syncedPhotoHash="new",syncState="SYNCED")
        val merged=mergeRoute(local,remote)
        assertNull(merged.photoPath);assertEquals("new",merged.photoHash);assertEquals("new",merged.syncedPhotoHash)
    }
}
