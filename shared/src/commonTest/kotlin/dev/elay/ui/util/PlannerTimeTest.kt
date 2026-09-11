package dev.elay.ui.util

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.Priority
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/**
 * ADR-006: all day/window math goes through kotlinx-datetime with the owning
 * zone, never a fixed 24h offset. The DST case below is the regression guard
 * for that rule.
 */
class PlannerTimeTest {
    @Test
    fun dayWindowSpansLocalMidnightToNextLocalMidnightInUtc() {
        val zone = TimeZone.UTC
        val date = LocalDate(2026, 6, 15)

        val window = dayWindow(date, zone)

        assertEquals(LocalDateTime(2026, 6, 15, 0, 0).toInstant(zone), window.start)
        assertEquals(LocalDateTime(2026, 6, 16, 0, 0).toInstant(zone), window.endExclusive)
    }

    @Test
    fun dayWindowIsTwentyThreeHoursAcrossASpringForwardDstTransition() {
        // 2026-03-08 is the US spring-forward date (2:00am -> 3:00am), so the
        // calendar day itself is 23 wall-clock hours long in New York.
        val zone = TimeZone.of("America/New_York")
        val date = LocalDate(2026, 3, 8)

        val window = dayWindow(date, zone)

        assertEquals(23.hours, window.endExclusive - window.start)
    }

    @Test
    fun plusCalendarDaysPreservesLocalWallClockAcrossDst() {
        val zone = TimeZone.of("America/New_York")
        val nineAmBeforeDst = LocalDateTime(2026, 3, 7, 9, 0).toInstant(zone)

        val shifted = nineAmBeforeDst.plusCalendarDays(1, zone)

        assertEquals(LocalDateTime(2026, 3, 8, 9, 0).toInstant(zone), shifted)
    }

    @Test
    fun dayWindowEndInclusiveIsOneNanosecondBeforeEndExclusive() {
        val window = dayWindow(LocalDate(2026, 1, 1), TimeZone.UTC)

        assertEquals(window.endExclusive.toEpochMilliseconds(), window.endInclusive.toEpochMilliseconds() + 1)
    }

    @Test
    fun timeBlockShiftedByOneDayMovesBothEndsPreservingDuration() {
        val zone = TimeZone.UTC
        val block =
            TimeBlock(
                id = TimeBlockId("b-1"),
                ownerId = LOCAL_OWNER_ID,
                taskId = null,
                title = "Focus",
                startsAt = LocalDateTime(2026, 6, 15, 9, 0).toInstant(zone),
                endsAt = LocalDateTime(2026, 6, 15, 10, 0).toInstant(zone),
                originTz = zone,
                type = BlockType.Focus,
                status = BlockStatus.Scheduled,
                recurrenceRule = null,
                allDay = false,
                version = 1,
            )

        val shifted = block.shiftedByOneDay(zone)

        assertEquals(LocalDateTime(2026, 6, 16, 9, 0).toInstant(zone), shifted.startsAt)
        assertEquals(LocalDateTime(2026, 6, 16, 10, 0).toInstant(zone), shifted.endsAt)
        assertEquals(block.endsAt - block.startsAt, shifted.endsAt - shifted.startsAt)
    }

    @Test
    fun taskDueShiftedByOneDayLeavesUnscheduledTasksUntouched() {
        val zone = TimeZone.UTC
        val unscheduled = task(dueStart = null, dueEnd = null)

        val shifted = unscheduled.dueShiftedByOneDay(zone)

        assertEquals(null, shifted.dueStart)
        assertEquals(null, shifted.dueEnd)
    }

    private fun task(
        dueStart: Instant?,
        dueEnd: Instant?,
    ) = Task(
        id = TaskId("t-1"),
        ownerId = LOCAL_OWNER_ID,
        goalId = null,
        milestoneId = null,
        title = "Task",
        notes = null,
        status = TaskStatus.Todo,
        priority = Priority.Normal,
        effort = null,
        estimateMinutes = null,
        dueStart = dueStart,
        dueEnd = dueEnd,
        recurrenceRule = null,
        tags = emptyList(),
        version = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
