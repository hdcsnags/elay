package dev.elay.data.local.mapping

import dev.elay.data.local.SyncStatus
import dev.elay.data.local.TaskEntity
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.MilestoneId
import dev.elay.domain.model.Priority
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val tagsJson = Json

internal fun encodeTags(tags: List<String>): String = tagsJson.encodeToString(tags)

internal fun decodeTags(json: String): List<String> = tagsJson.decodeFromString(json)

fun TaskEntity.toDomain(): Task =
    Task(
        id = TaskId(id),
        ownerId = UserId(ownerId),
        goalId = goalId?.let(::GoalId),
        milestoneId = milestoneId?.let(::MilestoneId),
        title = title,
        notes = notes,
        status = TaskStatus.entries.first { it.wire == status },
        priority = Priority(priority),
        effort = effort,
        estimateMinutes = estimateMinutes,
        dueStart = dueStartEpochMs?.let(Instant::fromEpochMilliseconds),
        dueEnd = dueEndEpochMs?.let(Instant::fromEpochMilliseconds),
        recurrenceRule = recurrenceRule,
        tags = decodeTags(tagsJson),
        version = version,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
        updatedAt = Instant.fromEpochMilliseconds(updatedAtEpochMs),
    )

fun Task.toEntity(
    syncStatus: SyncStatus,
    localUpdatedAtEpochMs: Long,
): TaskEntity =
    TaskEntity(
        id = id.value,
        ownerId = ownerId.value,
        goalId = goalId?.value,
        milestoneId = milestoneId?.value,
        title = title,
        notes = notes,
        status = status.wire,
        priority = priority.value,
        effort = effort,
        estimateMinutes = estimateMinutes,
        dueStartEpochMs = dueStart?.toEpochMilliseconds(),
        dueEndEpochMs = dueEnd?.toEpochMilliseconds(),
        recurrenceRule = recurrenceRule,
        tagsJson = encodeTags(tags),
        version = version,
        createdAtEpochMs = createdAt.toEpochMilliseconds(),
        updatedAtEpochMs = updatedAt.toEpochMilliseconds(),
        syncStatus = syncStatus.wire,
        localUpdatedAtEpochMs = localUpdatedAtEpochMs,
    )
