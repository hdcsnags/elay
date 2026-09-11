package dev.elay.data.remote.dto

import dev.elay.domain.model.GoalId
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.MilestoneId
import dev.elay.domain.model.MilestoneStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of a `milestones` row (contracts/phase1-planner.md §1, §4).
 * Field order mirrors `contracts/fixtures/milestone.json` exactly. No
 * owner/visibility columns — access is via the parent goal.
 */
@Serializable
data class MilestoneDto(
    val id: String,
    @SerialName("goal_id") val goalId: String,
    val title: String,
    @SerialName("target_date") val targetDate: String? = null,
    @SerialName("sort_order") val sortOrder: Int,
    val status: String,
    val version: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Domain [Milestone] carries no timestamps — dropped here (lean child model). */
fun MilestoneDto.toDomain(): Milestone =
    Milestone(
        id = MilestoneId(id),
        goalId = GoalId(goalId),
        title = title,
        targetDate = targetDate?.let(LocalDate::parse),
        sortOrder = sortOrder,
        status = MilestoneStatus.entries.first { it.wire == status },
        version = version,
    )

/**
 * Domain [Milestone] has no `created_at`/`updated_at` (frozen model, contracts/phase1-planner.md
 * §5) but the wire row requires both — callers supply them explicitly (e.g. from the cached
 * Room row being replayed) rather than this mapper inventing a timestamp.
 */
fun Milestone.toDto(
    createdAt: Instant,
    updatedAt: Instant,
): MilestoneDto =
    MilestoneDto(
        id = id.value,
        goalId = goalId.value,
        title = title,
        targetDate = targetDate?.toString(),
        sortOrder = sortOrder,
        status = status.wire,
        version = version,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
