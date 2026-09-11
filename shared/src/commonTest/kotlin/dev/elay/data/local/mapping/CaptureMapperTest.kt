package dev.elay.data.local.mapping

import dev.elay.data.local.SyncStatus
import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.CaptureSource
import dev.elay.domain.model.ParseStatus
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureMapperTest {
    private fun baseCapture(
        source: CaptureSource,
        parseStatus: ParseStatus,
    ) = Capture(
        id = CaptureId("c-1"),
        ownerId = UserId("u-1"),
        body = "buy milk",
        source = source,
        parseStatus = parseStatus,
        capturedAt = Instant.fromEpochMilliseconds(1_234),
        clarifiedTaskId = TaskId("t-1"),
        version = 1,
    )

    @Test
    fun roundTripsEverySource() {
        for (source in CaptureSource.entries) {
            val capture = baseCapture(source, ParseStatus.Unparsed)
            val entity = capture.toEntity(SyncStatus.Pending, localUpdatedAtEpochMs = 0)
            assertEquals(source.wire, entity.source)
            assertEquals(capture, entity.toDomain())
        }
    }

    @Test
    fun roundTripsEveryParseStatus() {
        for (parseStatus in ParseStatus.entries) {
            val capture = baseCapture(CaptureSource.Quick, parseStatus)
            val entity = capture.toEntity(SyncStatus.Pending, localUpdatedAtEpochMs = 0)
            assertEquals(parseStatus.wire, entity.parseStatus)
            assertEquals(capture, entity.toDomain())
        }
    }

    @Test
    fun roundTripsNullClarifiedTaskId() {
        val capture = baseCapture(CaptureSource.Voice, ParseStatus.Unparsed).copy(clarifiedTaskId = null)
        val entity = capture.toEntity(SyncStatus.Synced, localUpdatedAtEpochMs = 0)
        assertEquals(capture, entity.toDomain())
    }
}
