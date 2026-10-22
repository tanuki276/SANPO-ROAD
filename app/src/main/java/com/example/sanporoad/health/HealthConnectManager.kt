package com.example.sanporoad

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal data class HealthSummary(val steps: Long?, val heartRate: Long?, val calories: Double?)
internal class HealthConnectManager(private val context: Context) {
    companion object {
        val writes = setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class), HealthPermission.getWritePermission(DistanceRecord::class))
        val reads = setOf(HealthPermission.getReadPermission(StepsRecord::class), HealthPermission.getReadPermission(HeartRateRecord::class), HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class))
        val permissions = writes + reads
    }
    fun available() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    private fun client(): HealthConnectClient { check(available()) { "Health Connectをインストール・更新してください" }; return HealthConnectClient.getOrCreate(context) }
    suspend fun write(route: SavedRoute): String {
        if (route.imported) return "インポート記録はHealth Connectへ送信しません"
        if (route.durationMs <= 0) return "移動時間がないため送信しません"
        val client = client()
        check(client.permissionController.getGrantedPermissions().containsAll(writes)) { "Health Connectの書き込み権限が必要です" }
        val start = Instant.ofEpochMilli(route.startedAt)
        val end = start.plusMillis(Math.addExact(route.durationMs,route.restDurationMs))
        fun metadata(suffix: String) = Metadata.activelyRecorded(
            device=Device(type=Device.TYPE_PHONE), clientRecordId="sanpo:${route.id}:${route.startedAt}:$suffix",
            clientRecordVersion=route.updatedAt.coerceAtLeast(1))
        val pauses = route.restIntervals.filter { it.startMs >= route.startedAt && it.endMs > it.startMs && it.endMs <= end.toEpochMilli() }
            .map { ExerciseSegment(Instant.ofEpochMilli(it.startMs), Instant.ofEpochMilli(it.endMs), ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE) }
        client.insertRecords(listOf(
            ExerciseSessionRecord(startTime=start,startZoneOffset=java.time.ZoneId.systemDefault().rules.getOffset(start),
                endTime=end,endZoneOffset=java.time.ZoneId.systemDefault().rules.getOffset(end),exerciseType=ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                title=route.name,notes="SANPO 移動 ${route.durationMs}ms / 休憩 ${route.restDurationMs}ms",segments=pauses,metadata=metadata("walk")),
            DistanceRecord(startTime=start,startZoneOffset=java.time.ZoneId.systemDefault().rules.getOffset(start),
                endTime=end,endZoneOffset=java.time.ZoneId.systemDefault().rules.getOffset(end),distance=Length.meters(route.distanceMeters),metadata=metadata("distance"))
        ))
        return "Health Connectに送信済み"
    }
    suspend fun writeAfterSave(route: SavedRoute): String = try { write(route) }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { "SANPO保存済み・Health Connect未送信（設定画面で権限を確認できます）" }
    suspend fun read(route: SavedRoute): HealthSummary {
        val client=client(); val granted=client.permissionController.getGrantedPermissions()
        val range=TimeRangeFilter.between(Instant.ofEpochMilli(route.startedAt), Instant.ofEpochMilli(route.startedAt).plusMillis(route.durationMs+route.restDurationMs))
        val metrics=buildSet {
            if (HealthPermission.getReadPermission(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
            if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) add(HeartRateRecord.BPM_AVG)
            if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in granted) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        if (metrics.isEmpty()) return HealthSummary(null,null,null)
        val result=client.aggregate(AggregateRequest(metrics,range))
        return HealthSummary(result[StepsRecord.COUNT_TOTAL],result[HeartRateRecord.BPM_AVG],result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories)
    }
}
