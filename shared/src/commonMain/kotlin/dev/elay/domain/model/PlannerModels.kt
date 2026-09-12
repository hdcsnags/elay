package dev.elay.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.jvm.JvmInline

/**
 * Frozen Phase 1 domain models (contracts/phase1-planner.md §5).
 * Wire enum values are the SQL strings — see each enum's `wire` field.
 */

@JvmInline value class GoalId(
    val value: String,
)

@JvmInline value class MilestoneId(
    val value: String,
)

@JvmInline value class TaskId(
    val value: String,
)

@JvmInline value class CaptureId(
    val value: String,
)

@JvmInline value class TimeBlockId(
    val value: String,
)

@JvmInline value class UserId(
    val value: String,
)

enum class Visibility(
    val wire: String,
) {
    Private("private"),
    BusyOnly("busy_only"),
    TitleOnly("title_only"),
    Full("full"),
}

enum class GoalStatus(
    val wire: String,
) {
    Active("active"),
    Paused("paused"),
    Completed("completed"),
    Archived("archived"),
}

enum class MilestoneStatus(
    val wire: String,
) {
    Pending("pending"),
    Active("active"),
    Completed("completed"),
    Skipped("skipped"),
}

enum class TaskStatus(
    val wire: String,
) {
    Todo("todo"),
    InProgress("in_progress"),
    Completed("completed"),
    Cancelled("cancelled"),
}

enum class CaptureSource(
    val wire: String,
) {
    Quick("quick"),
    Voice("voice"),
    Share("share"),
    Manual("manual"),
}

enum class ParseStatus(
    val wire: String,
) {
    Unparsed("unparsed"),
    Parsed("parsed"),
    Failed("failed"),
    Dismissed("dismissed"),
}

enum class BlockType(
    val wire: String,
) {
    Personal("personal"),
    Focus("focus"),
    Routine("routine"),

    /** Stage 2 lead amendment 1 (contracts/stage2-timelock.md): one private, owner-only row per
     * accepted-proposal participant (council/stage2-timelock-sol.md §A.1 "Block model:
     * per-member rows"). Renders like any other block in Plan/Today; C3 adds the dual-time
     * visual marker for this type per Gemini's §B. */
    SharedLock("shared_lock"),
}

enum class BlockStatus(
    val wire: String,
) {
    Scheduled("scheduled"),
    Completed("completed"),
    Cancelled("cancelled"),
}

/** Priority is 0 (lowest) .. 3 (urgent) — smallint on the wire. */
@JvmInline
value class Priority(
    val value: Int,
) {
    init {
        require(value in 0..3) { "priority 0..3" }
    }

    companion object {
        val Low = Priority(0)
        val Normal = Priority(1)
        val High = Priority(2)
        val Urgent = Priority(3)
    }
}

data class Goal(
    val id: GoalId,
    val ownerId: UserId,
    val title: String,
    val notes: String?,
    val targetDate: LocalDate?,
    val status: GoalStatus,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Milestone(
    val id: MilestoneId,
    val goalId: GoalId,
    val title: String,
    val targetDate: LocalDate?,
    val sortOrder: Int,
    val status: MilestoneStatus,
    val version: Long,
)

data class Task(
    val id: TaskId,
    val ownerId: UserId,
    val goalId: GoalId?,
    val milestoneId: MilestoneId?,
    val title: String,
    val notes: String?,
    val status: TaskStatus,
    val priority: Priority,
    val effort: Int?,
    val estimateMinutes: Int?,
    val dueStart: Instant?,
    val dueEnd: Instant?,
    val recurrenceRule: String?,
    val tags: List<String>,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Capture(
    val id: CaptureId,
    val ownerId: UserId,
    val body: String,
    val source: CaptureSource,
    val parseStatus: ParseStatus,
    val capturedAt: Instant,
    val clarifiedTaskId: TaskId?,
    val version: Long,
)

/** Time truth per ADR-006: UTC instants + IANA origin zone; viewer zone is presentation. */
data class TimeBlock(
    val id: TimeBlockId,
    val ownerId: UserId,
    val taskId: TaskId?,
    val title: String?,
    val startsAt: Instant,
    val endsAt: Instant,
    val originTz: TimeZone,
    val type: BlockType,
    val status: BlockStatus,
    val recurrenceRule: String?,
    val allDay: Boolean,
    val version: Long,
)
