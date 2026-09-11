package dev.elay.ui.plan

import dev.elay.domain.model.TimeBlock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/** Plan's vertical timeline runs 06:00 through 24:00 local time (brief §4). */
const val PLAN_WINDOW_START_HOUR = 6
const val PLAN_WINDOW_END_HOUR = 24
private const val MIN_HEIGHT_FRACTION = 0.01f

/** The [date]'s Plan timeline window: `[start, endExclusive)` in the owning [TimeZone]. */
data class PlanWindow(
    val start: Instant,
    val endExclusive: Instant,
)

/** The [date]'s Plan timeline window `[06:00, 24:00)` in [zone] — zone-aware, no fixed offsets (ADR-006). */
fun planWindow(
    date: LocalDate,
    zone: TimeZone,
): PlanWindow {
    val start = date.atTime(LocalTime(PLAN_WINDOW_START_HOUR, 0)).toInstant(zone)
    val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
    return PlanWindow(start, end)
}

/**
 * A block's vertical position on the timeline as fractions of the window
 * height, so the composable can multiply by whatever pixel height it has.
 * Pure function — no Clock, no zone lookups, directly unit-testable.
 */
data class BlockPosition(
    val topFraction: Float,
    val heightFraction: Float,
)

/**
 * Positions [block] within `[windowStart, windowEnd)`, clamping to the
 * window. Returns null for all-day blocks (rendered outside the timeline)
 * and for blocks entirely outside the window.
 */
@Suppress("ReturnCount") // guard-clause style: null returns for out-of-window/all-day cases
fun blockPosition(
    block: TimeBlock,
    windowStart: Instant,
    windowEnd: Instant,
): BlockPosition? {
    if (block.allDay) return null
    val windowMinutes = (windowEnd - windowStart).inWholeMinutes.toFloat()
    if (windowMinutes <= 0f) return null

    val clampedStart = maxOf(block.startsAt, windowStart)
    val clampedEnd = minOf(block.endsAt, windowEnd)
    if (clampedEnd <= clampedStart) return null

    val topMinutes = (clampedStart - windowStart).inWholeMinutes.toFloat()
    val durationMinutes = (clampedEnd - clampedStart).inWholeMinutes.toFloat()
    return BlockPosition(
        topFraction = topMinutes / windowMinutes,
        heightFraction = (durationMinutes / windowMinutes).coerceAtLeast(MIN_HEIGHT_FRACTION),
    )
}
