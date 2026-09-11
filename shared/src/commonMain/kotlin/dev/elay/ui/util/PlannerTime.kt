package dev.elay.ui.util

import dev.elay.domain.model.Task
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.UserId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Placeholder single-user owner id used by the seat C1 UI until an
 * AuthGateway/session lands (contracts/phase1-planner.md §5). Kept out of
 * `ui/fake` so ViewModels can default to it without depending on the fake
 * data source directly.
 */
val LOCAL_OWNER_ID = UserId("local-owner")

/**
 * A calendar day's instant window in [zone]: `[start, endExclusive)`.
 * Pure and zone-aware per ADR-006 — no fixed offsets, no manual hour math.
 */
data class DayWindow(
    val start: Instant,
    val endExclusive: Instant,
) {
    /**
     * Inclusive end instant, for repository queries defined as `dueStart in
     * [start, end]` (see TaskDao.observeToday).
     */
    val endInclusive: Instant get() = endExclusive - 1.nanoseconds
}

/** The [date]'s window in [zone], from local midnight through the following local midnight. */
fun dayWindow(
    date: LocalDate,
    zone: TimeZone,
): DayWindow {
    val start = date.atStartOfDayIn(zone)
    val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
    return DayWindow(start, end)
}

/**
 * Shifts this instant by [days] calendar days in [zone], preserving local
 * wall-clock time (so a 9 AM block stays at 9 AM even across a DST
 * transition). ADR-006: calendar-day math via kotlinx-datetime, not a fixed
 * duration offset.
 */
fun Instant.plusCalendarDays(
    days: Int,
    zone: TimeZone,
): Instant {
    val local = toLocalDateTime(zone)
    val shiftedDate = local.date.plus(days, DateTimeUnit.DAY)
    return shiftedDate.atTime(local.time).toInstant(zone)
}

/**
 * "Not today": moves a block a day forward without touching its duration or
 * type (spec §2 — rescheduling, not failure).
 */
fun TimeBlock.shiftedByOneDay(zone: TimeZone): TimeBlock =
    copy(
        startsAt = startsAt.plusCalendarDays(1, zone),
        endsAt = endsAt.plusCalendarDays(1, zone),
    )

/** "Not today" for a due-dated task: defers its due window a day forward, leaving unscheduled tasks untouched. */
fun Task.dueShiftedByOneDay(zone: TimeZone): Task =
    copy(
        dueStart = dueStart?.plusCalendarDays(1, zone),
        dueEnd = dueEnd?.plusCalendarDays(1, zone),
    )
