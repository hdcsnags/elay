package dev.elay.data.local.mapping

import dev.elay.data.local.GoalEntity
import dev.elay.data.local.SyncStatus
import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.GoalStatus
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

fun GoalEntity.toDomain(): Goal =
    Goal(
        id = GoalId(id),
        ownerId = UserId(ownerId),
        title = title,
        notes = notes,
        targetDate = targetDate?.let(LocalDate::parse),
        status = GoalStatus.entries.first { it.wire == status },
        version = version,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
        updatedAt = Instant.fromEpochMilliseconds(updatedAtEpochMs),
    )

fun Goal.toEntity(
    syncStatus: SyncStatus,
    localUpdatedAtEpochMs: Long,
): GoalEntity =
    GoalEntity(
        id = id.value,
        ownerId = ownerId.value,
        title = title,
        notes = notes,
        targetDate = targetDate?.toString(),
        status = status.wire,
        version = version,
        createdAtEpochMs = createdAt.toEpochMilliseconds(),
        updatedAtEpochMs = updatedAt.toEpochMilliseconds(),
        syncStatus = syncStatus.wire,
        localUpdatedAtEpochMs = localUpdatedAtEpochMs,
    )
