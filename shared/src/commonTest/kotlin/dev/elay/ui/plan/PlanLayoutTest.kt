package dev.elay.ui.plan

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [blockPosition] is a pure function — no Clock, no zone lookups — tested directly (brief §4). */
class PlanLayoutTest {
    private val zone = TimeZone.UTC
    private val windowStart = LocalDateTime(2026, 6, 15, 6, 0).toInstant(zone)
    private val windowEnd = LocalDateTime(2026, 6, 16, 0, 0).toInstant(zone)

    @Test
    fun planWindowRunsFromSixAmToMidnightTheNextDay() {
        val window = planWindow(LocalDate(2026, 6, 15), zone)

        assertEquals(windowStart, window.start)
        assertEquals(windowEnd, window.endExclusive)
    }

    @Test
    fun blockFullyInsideWindowGetsExactFractions() {
        // 09:00-10:30: starts 3h into an 18h window and lasts 1.5h.
        val block =
            timeBlock(
                startsAt = LocalDateTime(2026, 6, 15, 9, 0).toInstant(zone),
                endsAt = LocalDateTime(2026, 6, 15, 10, 30).toInstant(zone),
            )

        val position = blockPosition(block, windowStart, windowEnd)

        checkNotNull(position)
        assertEquals(3f / 18f, position.topFraction)
        assertEquals(1.5f / 18f, position.heightFraction)
    }

    @Test
    fun blockStartingBeforeTheWindowIsClampedToTheWindowStart() {
        val block =
            timeBlock(
                startsAt = LocalDateTime(2026, 6, 15, 4, 0).toInstant(zone),
                endsAt = LocalDateTime(2026, 6, 15, 7, 0).toInstant(zone),
            )

        val position = blockPosition(block, windowStart, windowEnd)

        checkNotNull(position)
        assertEquals(0f, position.topFraction)
        assertEquals(1f / 18f, position.heightFraction)
    }

    @Test
    fun blockEndingAfterTheWindowIsClampedToTheWindowEnd() {
        val block =
            timeBlock(
                startsAt = LocalDateTime(2026, 6, 15, 23, 0).toInstant(zone),
                endsAt = LocalDateTime(2026, 6, 16, 2, 0).toInstant(zone),
            )

        val position = blockPosition(block, windowStart, windowEnd)

        checkNotNull(position)
        assertEquals(17f / 18f, position.topFraction)
        assertEquals(1f / 18f, position.heightFraction)
    }

    @Test
    fun blockEntirelyBeforeTheWindowIsNull() {
        val block =
            timeBlock(
                startsAt = LocalDateTime(2026, 6, 15, 3, 0).toInstant(zone),
                endsAt = LocalDateTime(2026, 6, 15, 5, 0).toInstant(zone),
            )

        assertNull(blockPosition(block, windowStart, windowEnd))
    }

    @Test
    fun blockEntirelyAfterTheWindowIsNull() {
        val block =
            timeBlock(
                startsAt = LocalDateTime(2026, 6, 16, 1, 0).toInstant(zone),
                endsAt = LocalDateTime(2026, 6, 16, 2, 0).toInstant(zone),
            )

        assertNull(blockPosition(block, windowStart, windowEnd))
    }

    @Test
    fun allDayBlockIsNeverPositionedOnTheTimeline() {
        val block =
            timeBlock(
                startsAt = LocalDateTime(2026, 6, 15, 0, 0).toInstant(zone),
                endsAt = LocalDateTime(2026, 6, 16, 0, 0).toInstant(zone),
                allDay = true,
            )

        assertNull(blockPosition(block, windowStart, windowEnd))
    }

    private fun timeBlock(
        startsAt: Instant,
        endsAt: Instant,
        allDay: Boolean = false,
    ) = TimeBlock(
        id = TimeBlockId("b-1"),
        ownerId = UserId("owner"),
        taskId = null,
        title = "Block",
        startsAt = startsAt,
        endsAt = endsAt,
        originTz = zone,
        type = BlockType.Personal,
        status = BlockStatus.Scheduled,
        recurrenceRule = null,
        allDay = allDay,
        version = 1,
    )
}
