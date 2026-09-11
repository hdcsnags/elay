package dev.elay.data.local.mapping

import dev.elay.data.local.CaptureEntity
import dev.elay.data.local.SyncStatus
import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.CaptureSource
import dev.elay.domain.model.ParseStatus
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant

fun CaptureEntity.toDomain(): Capture =
    Capture(
        id = CaptureId(id),
        ownerId = UserId(ownerId),
        body = body,
        source = CaptureSource.entries.first { it.wire == source },
        parseStatus = ParseStatus.entries.first { it.wire == parseStatus },
        capturedAt = Instant.fromEpochMilliseconds(capturedAtEpochMs),
        clarifiedTaskId = clarifiedTaskId?.let(::TaskId),
        version = version,
    )

fun Capture.toEntity(
    syncStatus: SyncStatus,
    localUpdatedAtEpochMs: Long,
): CaptureEntity =
    CaptureEntity(
        id = id.value,
        ownerId = ownerId.value,
        body = body,
        source = source.wire,
        parseStatus = parseStatus.wire,
        capturedAtEpochMs = capturedAt.toEpochMilliseconds(),
        clarifiedTaskId = clarifiedTaskId?.value,
        version = version,
        syncStatus = syncStatus.wire,
        localUpdatedAtEpochMs = localUpdatedAtEpochMs,
    )
