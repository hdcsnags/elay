package dev.elay.data.remote.dto

import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.GoalStatus
import dev.elay.domain.model.UserId
import dev.elay.domain.model.Visibility
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of a `goals` row (contracts/phase1-planner.md §1, §4).
 * Field order and names mirror `contracts/fixtures/goal.json` exactly.
 * Instant/date columns are kept as the raw wire strings (no kotlinx-datetime
 * serializer dependency) and converted explicitly in the mappers below.
 */
@Serializable
data class GoalDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("household_id") val householdId: String? = null,
    val visibility: String,
    val title: String,
    val notes: String? = null,
    @SerialName("target_date") val targetDate: String? = null,
    val status: String,
    val version: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * Domain [Goal] has no household/visibility columns yet — Phase 1's guard
 * (contracts/phase1-planner.md §1) forces `household_id null, visibility
 * 'private'` at the schema level, so dropping them here loses nothing.
 */
fun GoalDto.toDomain(): Goal =
    Goal(
        id = GoalId(id),
        ownerId = UserId(ownerId),
        title = title,
        notes = notes,
        targetDate = targetDate?.let(LocalDate::parse),
        status = GoalStatus.entries.first { it.wire == status },
        version = version,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
    )

/** Phase 1 guard (contracts/phase1-planner.md §1): household_id null, visibility 'private'. */
fun Goal.toDto(): GoalDto =
    GoalDto(
        id = id.value,
        ownerId = ownerId.value,
        householdId = null,
        visibility = Visibility.Private.wire,
        title = title,
        notes = notes,
        targetDate = targetDate?.toString(),
        status = status.wire,
        version = version,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
