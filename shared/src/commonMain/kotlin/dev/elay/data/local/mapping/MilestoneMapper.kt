package dev.elay.data.local.mapping

import dev.elay.data.local.MilestoneEntity
import dev.elay.data.local.SyncStatus
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.MilestoneId
import dev.elay.domain.model.MilestoneStatus
import kotlinx.datetime.LocalDate

fun MilestoneEntity.toDomain(): Milestone =
    Milestone(
        id = MilestoneId(id),
        goalId = GoalId(goalId),
        title = title,
        targetDate = targetDate?.let(LocalDate::parse),
        sortOrder = sortOrder,
        status = MilestoneStatus.entries.first { it.wire == status },
        version = version,
    )

fun Milestone.toEntity(
    syncStatus: SyncStatus,
    localUpdatedAtEpochMs: Long,
): MilestoneEntity =
    MilestoneEntity(
        id = id.value,
        goalId = goalId.value,
        title = title,
        targetDate = targetDate?.toString(),
        sortOrder = sortOrder,
        status = status.wire,
        version = version,
        syncStatus = syncStatus.wire,
        localUpdatedAtEpochMs = localUpdatedAtEpochMs,
    )
