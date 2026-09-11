package dev.elay.data.local.mapping

import dev.elay.data.local.SyncStatus
import dev.elay.data.local.TimeBlockEntity
import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

fun TimeBlockEntity.toDomain(): TimeBlock =
    TimeBlock(
        id = TimeBlockId(id),
        ownerId = UserId(ownerId),
        taskId = taskId?.let(::TaskId),
        title = title,
        startsAt = Instant.fromEpochMilliseconds(startsAtEpochMs),
        endsAt = Instant.fromEpochMilliseconds(endsAtEpochMs),
        originTz = TimeZone.of(originTz),
        type = BlockType.entries.first { it.wire == type },
        status = BlockStatus.entries.first { it.wire == status },
        recurrenceRule = recurrenceRule,
        allDay = allDay,
        version = version,
    )

fun TimeBlock.toEntity(
    syncStatus: SyncStatus,
    localUpdatedAtEpochMs: Long,
): TimeBlockEntity =
    TimeBlockEntity(
        id = id.value,
        ownerId = ownerId.value,
        taskId = taskId?.value,
        title = title,
        startsAtEpochMs = startsAt.toEpochMilliseconds(),
        endsAtEpochMs = endsAt.toEpochMilliseconds(),
        originTz = originTz.id,
        type = type.wire,
        status = status.wire,
        recurrenceRule = recurrenceRule,
        allDay = allDay,
        version = version,
        syncStatus = syncStatus.wire,
        localUpdatedAtEpochMs = localUpdatedAtEpochMs,
    )
