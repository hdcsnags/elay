package dev.elay.ui.plan

import dev.elay.domain.model.TimeBlock
import kotlinx.datetime.Instant

/*
 * Plan's capacity gauge (contracts/stage4-honest-availability.md; council/stage4-availability-gemini.md
 * §2): a quiet, judgment-free reading of how full the selected day already is against a realistic
 * daily budget. Pure functions only — no `@Composable`, directly unit-testable per this seat's
 * grant (§6 "gauge levels").
 *
 * §2.1's formula also sums external-busy-block duration into the numerator ("External busy windows
 * are included in the sum so that time committed outside ELAY directly reflects on the day's
 * realistic budget"). This seat's grant text is explicit that the gauge instead "compute[s] from
 * the day's blocks already available in Plan's ViewModel" — and
 * dev.elay.domain.availability.AvailabilityRepository exposes no "list this day's busy windows"
 * read (only a candidate-window `selfConflictHints` lookup and the caller's own
 * `mySources()` bookkeeping), so there is no RPC this client could call to fold external-busy
 * duration in honestly. UNVERIFIED gap, flagged for the lead: the gauge below only ever reflects
 * ELAY's own TimeBlocks, never external-calendar busy time, until a day-scoped external-busy read
 * exists server-side.
 */

/** §2.1's default daily planned-capacity budget: 8 hours. */
const val DEFAULT_DAILY_CAPACITY_BUDGET_MINUTES = 8 * 60

private const val MINUTES_PER_HOUR = 60
private const val COMFORTABLE_MAX_RATIO = 0.5f
private const val BALANCED_MAX_RATIO = 0.8f
private const val FULL_MAX_RATIO = 1.0f

/** §2.3's four visual/copy levels. */
enum class CapacityLevel { Comfortable, Balanced, Full, Stretched }

/** The gauge's full computed state for one Plan day (§2.1-§2.3). */
data class CapacityGaugeState(
    val plannedMinutes: Int,
    val budgetMinutes: Int,
    val level: CapacityLevel,
)

/** §2.3's ratio-to-level mapping: `<50%` Comfortable, `50-79%` Balanced, `80-100%` Full, `>100%` Stretched. */
fun capacityLevelFor(ratio: Float): CapacityLevel =
    when {
        ratio < COMFORTABLE_MAX_RATIO -> CapacityLevel.Comfortable
        ratio < BALANCED_MAX_RATIO -> CapacityLevel.Balanced
        ratio <= FULL_MAX_RATIO -> CapacityLevel.Full
        else -> CapacityLevel.Stretched
    }

/**
 * Computes the day's gauge from its already-loaded ELAY [TimeBlock]s (Plan's own timeline data —
 * see this file's kdoc for the external-busy gap). All-day blocks are excluded — an all-day marker
 * isn't a durational commitment against an hourly budget, same exclusion
 * [PlanUiState.timelineBlocks] already applies before this is ever called. Overlapping blocks are
 * merged before summing (§2.1's "minus Overlaps") so two blocks sharing a room don't double-count.
 */
fun capacityGaugeFor(
    timedBlocks: List<TimeBlock>,
    budgetMinutes: Int = DEFAULT_DAILY_CAPACITY_BUDGET_MINUTES,
): CapacityGaugeState {
    val plannedMinutes = mergedDurationMinutes(timedBlocks)
    val ratio = if (budgetMinutes <= 0) 0f else plannedMinutes.toFloat() / budgetMinutes
    return CapacityGaugeState(plannedMinutes, budgetMinutes, capacityLevelFor(ratio))
}

private fun mergedDurationMinutes(blocks: List<TimeBlock>): Int {
    val sorted = blocks.filterNot { it.allDay }.sortedBy { it.startsAt }
    var total = 0L
    var currentStart: Instant? = null
    var currentEnd: Instant? = null
    for (block in sorted) {
        val start = currentStart
        val end = currentEnd
        if (end == null || start == null) {
            currentStart = block.startsAt
            currentEnd = block.endsAt
            continue
        }
        if (block.startsAt <= end) {
            if (block.endsAt > end) currentEnd = block.endsAt
        } else {
            total += (end - start).inWholeMinutes
            currentStart = block.startsAt
            currentEnd = block.endsAt
        }
    }
    if (currentStart != null && currentEnd != null) {
        total += (currentEnd - currentStart).inWholeMinutes
    }
    return total.coerceAtLeast(0).toInt()
}

/** §2.3's exact calm copy per level: "{h}h {m}m planned · <level phrase>". */
fun capacityGaugeCopy(state: CapacityGaugeState): String {
    val plannedPhrase = "${state.plannedMinutes.hoursPart()}h ${state.plannedMinutes.minutesPart()}m planned"
    return when (state.level) {
        CapacityLevel.Comfortable -> "$plannedPhrase · Plenty of space"
        CapacityLevel.Balanced -> "$plannedPhrase · Balanced focus"
        CapacityLevel.Full -> "$plannedPhrase · Day is full"
        CapacityLevel.Stretched -> {
            val over = state.plannedMinutes - state.budgetMinutes
            "$plannedPhrase · ${over}m over ${state.budgetMinutes.hoursPart()}h budget"
        }
    }
}

/** §2.4's spoken `stateDescription`: "Capacity: 5 hours 15 minutes scheduled of 8 hour budget. Balanced focus." */
fun capacityGaugeAccessibilityDescription(state: CapacityGaugeState): String {
    val levelPhrase =
        when (state.level) {
            CapacityLevel.Comfortable -> "Plenty of space."
            CapacityLevel.Balanced -> "Balanced focus."
            CapacityLevel.Full -> "Day is full."
            CapacityLevel.Stretched -> "${state.plannedMinutes - state.budgetMinutes} minutes over budget."
        }
    return "Capacity: ${state.plannedMinutes.hoursPart()} hours ${state.plannedMinutes.minutesPart()} minutes " +
        "scheduled of ${state.budgetMinutes.hoursPart()} hour budget. $levelPhrase"
}

private fun Int.hoursPart(): Int = this / MINUTES_PER_HOUR

private fun Int.minutesPart(): Int = this % MINUTES_PER_HOUR
