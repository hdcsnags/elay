package dev.elay.data.local.mapping

import dev.elay.data.local.SyncStatus
import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeBlockMapperTest {
    private fun baseBlock(
        type: BlockType,
        status: BlockStatus,
    ) = TimeBlock(
        id = TimeBlockId("b-1"),
        ownerId = UserId("u-1"),
        taskId = TaskId("t-1"),
        title = "Focus block",
        startsAt = Instant.fromEpochMilliseconds(1_000),
        endsAt = Instant.fromEpochMilliseconds(4_600_000),
        originTz = TimeZone.of("America/Toronto"),
        type = type,
        status = status,
        recurrenceRule = "FREQ=WEEKLY",
        allDay = false,
        version = 1,
    )

    @Test
    fun roundTripsEveryType() {
        for (type in BlockType.entries) {
            val block = baseBlock(type, BlockStatus.Scheduled)
            val entity = block.toEntity(SyncStatus.Pending, localUpdatedAtEpochMs = 0)
            assertEquals(type.wire, entity.type)
            assertEquals(block, entity.toDomain())
        }
    }

    /** Stage 2 lead amendment 1 (contracts/stage2-timelock.md): [BlockType.SharedLock] is a new
     * enum case added to the frozen [BlockType] wire mapping — already covered structurally by
     * [roundTripsEveryType]'s loop over [BlockType.entries], but this test names it explicitly so
     * Room's `time_blocks.type` column is proven to tolerate `"shared_lock"` end-to-end
     * (entity <-> domain, not just the enum's own `wire` field) the same way every other block
     * type already is. */
    @Test
    fun sharedLockTypeRoundTripsWireStringAndDomain() {
        val block = baseBlock(BlockType.SharedLock, BlockStatus.Scheduled)
        val entity = block.toEntity(SyncStatus.Pending, localUpdatedAtEpochMs = 0)

        assertEquals("shared_lock", entity.type)
        assertEquals(block, entity.toDomain())
        assertEquals(BlockType.SharedLock, entity.toDomain().type)
    }

    @Test
    fun roundTripsEveryStatus() {
        for (status in BlockStatus.entries) {
            val block = baseBlock(BlockType.Personal, status)
            val entity = block.toEntity(SyncStatus.Pending, localUpdatedAtEpochMs = 0)
            assertEquals(status.wire, entity.status)
            assertEquals(block, entity.toDomain())
        }
    }

    @Test
    fun roundTripsNullTaskAndTitleAndAllDay() {
        val block = baseBlock(BlockType.Routine, BlockStatus.Cancelled).copy(taskId = null, title = null, allDay = true)
        val entity = block.toEntity(SyncStatus.Conflict, localUpdatedAtEpochMs = 0)
        assertEquals(block, entity.toDomain())
    }
}
