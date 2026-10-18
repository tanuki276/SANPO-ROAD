package com.example.sanporoad

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class V1DataTest {
    private fun route() = SavedRoute(10, "朝 <散歩> & 川", 1000, 9000, 20.0,
        listOf(LatLngPoint(35.0, 139.0, 1000, segment = 0), LatLngPoint(35.1, 139.1, 5000, segment = 1)),
        note = "メモ", ownerId = "account-a", updatedAt = 100)

    @Test fun newerRemoteWinsAndLocalPhotoSurvives() {
        val local = route().copy(photoPath = "/private/photo.jpg")
        val remote = route().copy(name = "新しい名前", updatedAt = 200, photoObjectPath = "account-a/10.jpg")
        val merged = mergeRoute(local, remote)
        assertEquals("新しい名前", merged.name)
        assertEquals(local.photoPath, merged.photoPath)
        assertEquals(remote.photoObjectPath, merged.photoObjectPath)
    }
    @Test fun staleCloudCannotOverwriteLocalEdit() {
        assertEquals("編集済み", mergeRoute(route().copy(name = "編集済み", updatedAt = 300), route()).name)
    }
    @Test fun tombstoneAlwaysWinsAndContainsNoCoordinatesOrPhoto() {
        val deleted = route().copy(deletedAt = 200)
        val stale = route().copy(updatedAt = 9999)
        for (merged in listOf(mergeRoute(deleted, stale), mergeRoute(stale, deleted))) {
            assertEquals(200L, merged.deletedAt)
            assertTrue(merged.points.isEmpty()); assertEquals("", merged.note)
            assertNull(merged.photoPath); assertNull(merged.photoObjectPath)
        }
    }
    @Test(expected = IllegalArgumentException::class) fun neverMergeDifferentAccounts() {
        mergeRoute(route(), route().copy(ownerId = "account-b"))
    }
    @Test fun gpxIsValidXmlAndPauseCreatesTwoSegments() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(RouteExport.gpx(listOf(route())).toByteArray()))
        assertEquals("http://www.topografix.com/GPX/1/1", doc.documentElement.namespaceURI)
        assertEquals(2, doc.getElementsByTagName("trkseg").length)
        assertEquals("朝 <散歩> & 川", doc.getElementsByTagName("name").item(0).textContent)
        assertEquals("1970-01-01T00:00:01Z", doc.getElementsByTagName("time").item(0).textContent)
    }
    @Test fun exportsOmitDeletedRecordsAndPrivateIdentifiers() {
        val deleted = route().copy(deletedAt = 100)
        assertFalse(RouteExport.gpx(listOf(deleted)).contains("<trk>"))
        assertFalse(RouteExport.json(listOf(route().copy(photoPath = "/private/p.jpg"))).contains("account-a"))
        assertFalse(RouteExport.json(listOf(route().copy(photoPath = "/private/p.jpg"))).contains("/private/"))
    }
    @Test fun csvQuotesTextAndNeutralizesSpreadsheetFormula() {
        val csv = RouteExport.csv(listOf(route().copy(name = "=1+1", note = "a,\"b\"\nline")))
        assertTrue(csv.contains("\"'=1+1\""))
        assertTrue(csv.contains("\"a,\"\"b\"\"\nline\""))
    }
    @Test fun pauseDurationExcludesRestAndNeverGoesNegative() {
        assertEquals(6000L, activeDuration(1000, 10000, 1000, 8000))
        assertEquals(8000L, activeDuration(1000, 10000, 1000, 0))
        assertEquals(0L, activeDuration(10000, 1000, 0, 0))
    }
    @Test fun combinedHistoryFilters() {
        val match = route().copy(favorite = true, photoPath = "photo", distanceMeters = 5000.0)
        val filter = HistoryFilter(since = 500, minimumMeters = 3000.0, photosOnly = true, favoritesOnly = true)
        assertEquals(listOf(match), filterWalkHistory(listOf(route(), match), "川", HistorySort.NEWEST, filter))
    }
}
