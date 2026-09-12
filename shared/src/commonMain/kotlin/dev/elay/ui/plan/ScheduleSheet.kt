package dev.elay.ui.plan

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.minutes

/** The add-block sheet's own time-of-day step (brief §1's "simple pickers" option). */
const val SCHEDULE_TIME_STEP_MINUTES = 15

/** Duration bounds enforced when building the block (brief §1/§6): 1 minute .. 24 hours. */
const val MIN_BLOCK_DURATION_MINUTES = 1
const val MAX_BLOCK_DURATION_MINUTES = 24 * 60

private const val DEFAULT_DURATION_MINUTES = 30
private val DEFAULT_START_TIME = LocalTime(hour = 9, minute = 0)
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

/**
 * Plan's "Add block" sheet state (brief §1): an optional link to an unscheduled task, a title
 * (prefilled from the linked task, editable), the Plan day it lands on — always the day the sheet
 * was opened from, never separately editable — and a start time + duration in [zone]. Opening the
 * sheet from the unscheduled rail's own "Schedule" button and opening it fresh from the header
 * button produce the exact same state shape, just with [linkedTaskId]/[title] pre-filled or not.
 */
data class ScheduleSheetState(
    val date: LocalDate,
    val zone: TimeZone,
    val linkedTaskId: TaskId? = null,
    val title: String = "",
    val startTime: LocalTime = DEFAULT_START_TIME,
    val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
)

/**
 * Builds the [TimeBlock] the sheet's Save action enqueues. All time math goes through
 * kotlinx-datetime (ADR-006): [ScheduleSheetState.startTime] on [ScheduleSheetState.date] is
 * resolved to an instant in [ScheduleSheetState.zone] — the same zone becomes the block's
 * `originTz`, so a block always remembers the wall-clock time its owner actually picked. The
 * duration is clamped to [MIN_BLOCK_DURATION_MINUTES]..[MAX_BLOCK_DURATION_MINUTES] rather than
 * rejected outright — a fat-fingered 0 or a multi-day entry becomes the nearest valid block
 * instead of a dead end (spec §2's calm-copy stance, extended to this sheet). A blank/whitespace
 * title is stored as `null` — the domain model already allows it, and a linked task's own title
 * carries the block on Today/Plan's cards regardless.
 */
fun buildScheduledBlock(
    id: TimeBlockId,
    ownerId: UserId,
    state: ScheduleSheetState,
): TimeBlock {
    val duration = state.durationMinutes.coerceIn(MIN_BLOCK_DURATION_MINUTES, MAX_BLOCK_DURATION_MINUTES)
    val startsAt = state.date.atTime(state.startTime).toInstant(state.zone)
    return TimeBlock(
        id = id,
        ownerId = ownerId,
        taskId = state.linkedTaskId,
        title = state.title.trim().ifBlank { null },
        startsAt = startsAt,
        endsAt = startsAt + duration.minutes,
        originTz = state.zone,
        type = BlockType.Personal,
        status = BlockStatus.Scheduled,
        recurrenceRule = null,
        allDay = false,
        version = 0,
    )
}

/**
 * Steps this time by [deltaMinutes], wrapping across midnight in either direction (the sheet's
 * start-time stepper never needs to reach into the next/previous calendar day — the Plan day
 * itself is fixed, only the wall-clock time within it moves).
 */
fun LocalTime.stepBy(deltaMinutes: Int): LocalTime {
    val totalMinutes = (hour * MINUTES_PER_HOUR + minute + deltaMinutes).mod(MINUTES_PER_DAY)
    return LocalTime(hour = totalMinutes / MINUTES_PER_HOUR, minute = totalMinutes % MINUTES_PER_HOUR)
}
