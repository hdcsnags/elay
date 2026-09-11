package dev.elay.data.remote.dto

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import dev.elay.domain.model.Visibility
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of a `time_blocks` row (contracts/phase1-planner.md §1, §4).
 * Field order mirrors `contracts/fixtures/time_block.json` exactly.
 */
@Serializable
data class TimeBlockDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("household_id") val householdId: String? = null,
    val visibility: String,
    @SerialName("task_id") val taskId: String? = null,
    val title: String? = null,
    @SerialName("starts_at_utc") val startsAtUtc: String,
    @SerialName("ends_at_utc") val endsAtUtc: String,
    @SerialName("origin_tz") val originTz: String,
    val type: String,
    val status: String,
    @SerialName("recurrence_rule") val recurrenceRule: String? = null,
    @SerialName("all_day") val allDay: Boolean,
    val version: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Domain [TimeBlock] has no household/visibility columns (Phase 1 guard forces null/'private'). */
fun TimeBlockDto.toDomain(): TimeBlock =
    TimeBlock(
        id = TimeBlockId(id),
        ownerId = UserId(ownerId),
        taskId = taskId?.let(::TaskId),
        title = title,
        startsAt = Instant.parse(startsAtUtc),
        endsAt = Instant.parse(endsAtUtc),
        originTz = TimeZone.of(originTz),
        type = BlockType.entries.first { it.wire == type },
        status = BlockStatus.entries.first { it.wire == status },
        recurrenceRule = recurrenceRule,
        allDay = allDay,
        version = version,
    )

/**
 * Phase 1 guard (contracts/phase1-planner.md §1): household_id null, visibility 'private'.
 * Domain [TimeBlock] has no `created_at`/`updated_at` (frozen model) — callers supply them.
 */
fun TimeBlock.toDto(
    createdAt: Instant,
    updatedAt: Instant,
): TimeBlockDto =
    TimeBlockDto(
        id = id.value,
        ownerId = ownerId.value,
        householdId = null,
        visibility = Visibility.Private.wire,
        taskId = taskId?.value,
        title = title,
        startsAtUtc = startsAt.toString(),
        endsAtUtc = endsAt.toString(),
        originTz = originTz.id,
        type = type.wire,
        status = status.wire,
        recurrenceRule = recurrenceRule,
        allDay = allDay,
        version = version,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
