package com.example.sanporoad

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class Cr11Test {
    private fun p(x: Double, time: Long, accuracy: Float=3.2f, segment: Int=0) = LatLngPoint(x/111194.9266,0.0,time,accuracy,segment)
    private fun route() = SavedRoute(1,"犬散歩",1000,60000,90.0,listOf(p(0.0,1000),p(90.0,61000)),tags=listOf("犬の散歩"))
    @Test fun stationary28MeterOscillationIsMappedWithoutInflatingDistance() {
        val a=GpsDistanceAccumulator()
        (0..600).forEach { assertTrue(a.add(p(if(it%2==0) 0.0 else 2.8,1000L+it*1000))) }
        assertEquals(0.0,a.distanceMeters,.01)
        assertTrue(a.rawMeters>1600)
        assertEquals(601,a.report().acceptedPoints+a.report().excludedPoints)
    }
    @Test fun continuousSmallRealStepsAndDogDetourSurvive() {
        val a=GpsDistanceAccumulator()
        (0..100).forEach { a.add(p(it.toDouble(),1000L+it*1000)) }
        assertEquals(99.0,a.distanceMeters,1.1)
        val b=GpsDistanceAccumulator()
        listOf(0.0,5.0,10.0,5.0,0.0).forEachIndexed { i,x -> b.add(p(x,1000L+i*2000,2f)) }
        assertEquals(20.0,b.distanceMeters,.01)
    }
    @Test fun accuracyChangesNoiseThreshold() {
        val good=GpsDistanceAccumulator();val poor=GpsDistanceAccumulator()
        listOf(0.0,5.0,0.0,5.0,0.0).forEachIndexed { i,x ->
            good.add(p(x,1000L+i*2000,2f));poor.add(p(x,1000L+i*2000,10f))
        }
        assertEquals(20.0,good.distanceMeters,.01);assertEquals(0.0,poor.distanceMeters,.01)
    }
    @Test fun replayCheckpointPreservesPendingAnchorAndQuality() {
        val a=GpsDistanceAccumulator(); (0..17).forEach { a.add(p(it.toDouble(),1000L+it*1000)) }
        val restored=Json.decodeFromString<GpsDistanceAccumulator>(Json.encodeToString(GpsDistanceAccumulator.serializer(),a))
        (18..40).forEach { a.add(p(it.toDouble(),1000L+it*1000)); restored.add(p(it.toDouble(),1000L+it*1000)) }
        assertEquals(a.report(),restored.report())
    }
    @Test fun gapsPausesAndJumpsNeverAddBridgingDistance() {
        val a=GpsDistanceAccumulator();a.add(p(0.0,1000));a.add(p(100.0,2000));a.add(p(5.0,4000))
        assertEquals(5.0,a.distanceMeters,.01)
        a.add(p(200.0,30000));a.add(p(400.0,31000,segment=1))
        assertEquals(5.0,a.distanceMeters,.01)
    }
    @Test fun maskedRevisitsAreRemovedAndGapsAreSplit() {
        val points=listOf(0.0,200.0,400.0,0.0,400.0,600.0).mapIndexed { i,x -> p(x,1000L+i*1000) }
        val masked=RoutePrivacy.mask(points)
        assertEquals(3,masked.size)
        assertTrue(masked[1].segment != masked[2].segment)
        assertTrue(masked.all { it.timeMs==0L && it.accuracyM==null })
        assertTrue(RoutePrivacy.mask(listOf(p(0.0,1),p(100.0,2))).isEmpty())
    }
    @Test fun importCannotClaimPersonalRecordEvenWhenFileSaysNative() {
        val original=route()
        val backup=RouteBackup.decode(RouteExport.json(listOf(original)))
        val imported=RouteBackup.restored(backup.routes.single().copy(imported=false,ownerId="forged",photoPath="/forged"),2)
        assertTrue(imported.imported);assertNull(imported.photoPath);assertNull(imported.ownerId)
        assertEquals(RouteBackup.key(original),imported.importKey)
        assertEquals(0,WalkStats.calculate(listOf(imported)).totalWalks)
        assertNull(WalkInsights.compareWithPreviousSimilar(imported,listOf(original)))
        assertTrue(mergeRoute(imported.copy(ownerId=null),imported.copy(imported=false,updatedAt=999)).imported)
    }
    @Test fun backupMetadataRoundTripsAndInvalidCoordinatesFail() {
        val original=route().copy(restDurationMs=12000,restIntervals=listOf(RestInterval(2000,14000)),gpsQuality=GpsQualityReport(2,3,3.2f))
        assertEquals(original,RouteBackup.decode(RouteExport.json(listOf(original))).routes.single())
        try { RouteBackup.decode(RouteExport.json(listOf(original.copy(points=listOf(p(0.0,1).copy(lat=91.0)))))); fail() }
        catch(_:IllegalArgumentException) {}
    }
    @Test fun invalidAccuracyDoesNotPoisonDurableState() {
        val accumulator=GpsDistanceAccumulator()
        accumulator.add(p(0.0,1000))
        assertFalse(accumulator.add(p(0.0,2000,Float.NaN)))
        val restored=Json.decodeFromString<GpsDistanceAccumulator>(Json.encodeToString(GpsDistanceAccumulator.serializer(),accumulator))
        assertEquals(accumulator.report(),restored.report())
    }
}
