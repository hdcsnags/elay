package dev.elay.data.remote.dto

import dev.elay.domain.model.GoalId
import dev.elay.domain.model.MilestoneId
import dev.elay.domain.model.Priority
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.UserId
import dev.elay.domain.model.Visibility
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of a `tasks` row (contracts/phase1-planner.md §1, §4).
 * Field order mirrors `contracts/fixtures/task.json` exactly.
 */
@Serializable
data class TaskDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("household_id") val householdId: String? = null,
    val visibility: String,
    @SerialName("goal_id") val goalId: String? = null,
    @SerialName("milestone_id") val milestoneId: String? = null,
    val title: String,
    val notes: String? = null,
    val status: String,
    val priority: Int,
    val effort: Int? = null,
    @SerialName("estimate_min") val estimateMin: Int? = null,
    @SerialName("due_start_utc") val dueStartUtc: String? = null,
    @SerialName("due_end_utc") val dueEndUtc: String? = null,
    @SerialName("recurrence_rule") val recurrenceRule: String? = null,
    val tags: List<String> = emptyList(),
    val version: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Domain [Task] has no household/visibility columns (Phase 1 guard forces null/'private'). */
fun TaskDto.toDomain(): Task =
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
        estimateMinutes = estimateMin,
        dueStart = dueStartUtc?.let(Instant::parse),
        dueEnd = dueEndUtc?.let(Instant::parse),
        recurrenceRule = recurrenceRule,
        tags = tags,
        version = version,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
    )

/** Phase 1 guard (contracts/phase1-planner.md §1): household_id null, visibility 'private'. */
fun Task.toDto(): TaskDto =
    TaskDto(
        id = id.value,
        ownerId = ownerId.value,
        householdId = null,
        visibility = Visibility.Private.wire,
        goalId = goalId?.value,
        milestoneId = milestoneId?.value,
        title = title,
        notes = notes,
        status = status.wire,
        priority = priority.value,
        effort = effort,
        estimateMin = estimateMinutes,
        dueStartUtc = dueStart?.toString(),
        dueEndUtc = dueEnd?.toString(),
        recurrenceRule = recurrenceRule,
        tags = tags,
        version = version,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
