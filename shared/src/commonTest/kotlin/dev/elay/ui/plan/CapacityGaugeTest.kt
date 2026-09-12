package dev.elay.ui.plan

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** [capacityGaugeFor]/[capacityGaugeCopy]/[capacityGaugeAccessibilityDescription] — Stage 4's
 * capacity gauge (contracts/stage4-honest-availability.md; council/stage4-availability-gemini.md
 * §2), exercised with no Compose, no repository, no ViewModel (this seat's grant §6). */
class CapacityGaugeTest {
    private val ownerId = UserId("owner-1")
    private val zone = TimeZone.UTC
    private val dayStart = Instant.parse("2026-06-15T00:00:00Z")

    private fun block(
        startsAt: Instant,
        durationMinutes: Int,
    ) = TimeBlock(
        id = TimeBlockId("b-${startsAt.epochSeconds}"),
        ownerId = ownerId,
        taskId = null,
        title = "block",
        startsAt = startsAt,
        endsAt = startsAt + durationMinutes.minutes,
        originTz = zone,
        type = BlockType.Personal,
        status = BlockStatus.Scheduled,
        recurrenceRule = null,
        allDay = false,
        version = 1,
    )

    @Test
    fun noBlocksIsComfortableWithZeroPlannedMinutes() {
        val state = capacityGaugeFor(emptyList())
        assertEquals(0, state.plannedMinutes)
        assertEquals(CapacityLevel.Comfortable, state.level)
        assertEquals(DEFAULT_DAILY_CAPACITY_BUDGET_MINUTES, state.budgetMinutes)
    }

    @Test
    fun oneHourOfEightIsComfortable() {
        val state = capacityGaugeFor(listOf(block(dayStart, 60)))
        assertEquals(60, state.plannedMinutes)
        assertEquals(CapacityLevel.Comfortable, state.level)
    }

    @Test
    fun fiveHoursOfEightIsBalanced() {
        val state = capacityGaugeFor(listOf(block(dayStart, 5 * 60)))
        assertEquals(CapacityLevel.Balanced, state.level)
    }

    @Test
    fun sevenHoursOfEightIsFull() {
        val state = capacityGaugeFor(listOf(block(dayStart, 7 * 60)))
        assertEquals(CapacityLevel.Full, state.level)
    }

    @Test
    fun overEightHoursIsStretched() {
        val state = capacityGaugeFor(listOf(block(dayStart, 9 * 60)))
        assertEquals(CapacityLevel.Stretched, state.level)
        assertEquals(9 * 60, state.plannedMinutes)
    }

    @Test
    fun overlappingBlocksAreMergedNotDoubleCounted() {
        val blocks =
            listOf(
                block(dayStart, 120), // 00:00-02:00
                block(dayStart + 1.hours, 120), // 01:00-03:00, overlaps the first by 1h
            )
        val state = capacityGaugeFor(blocks)
        assertEquals(180, state.plannedMinutes) // merged to 00:00-03:00, not 240
    }

    @Test
    fun allDayBlocksAreExcludedFromTheTally() {
        val allDay =
            block(dayStart, 60).copy(allDay = true, endsAt = dayStart + 24.hours)
        val state = capacityGaugeFor(listOf(allDay))
        assertEquals(0, state.plannedMinutes)
    }

    @Test
    fun copyMatchesTheExactPerLevelCalmText() {
        assertEquals(
            "2h 30m planned · Plenty of space",
            capacityGaugeCopy(
                CapacityGaugeState(150, DEFAULT_DAILY_CAPACITY_BUDGET_MINUTES, CapacityLevel.Comfortable),
            ),
        )
        assertEquals(
            "5h 15m planned · Balanced focus",
            capacityGaugeCopy(CapacityGaugeState(315, DEFAULT_DAILY_CAPACITY_BUDGET_MINUTES, CapacityLevel.Balanced)),
        )
        assertEquals(
            "7h 30m planned · Day is full",
            capacityGaugeCopy(CapacityGaugeState(450, DEFAULT_DAILY_CAPACITY_BUDGET_MINUTES, CapacityLevel.Full)),
        )
        assertEquals(
            "8h 45m planned · 45m over 8h budget",
            capacityGaugeCopy(CapacityGaugeState(525, DEFAULT_DAILY_CAPACITY_BUDGET_MINUTES, CapacityLevel.Stretched)),
        )
    }

    @Test
    fun accessibilityDescriptionSpeaksTheFullSpokenSentence() {
        val description =
            capacityGaugeAccessibilityDescription(
                CapacityGaugeState(315, DEFAULT_DAILY_CAPACITY_BUDGET_MINUTES, CapacityLevel.Balanced),
            )
        assertEquals(
            "Capacity: 5 hours 15 minutes scheduled of 8 hour budget. Balanced focus.",
            description,
        )
    }
}
