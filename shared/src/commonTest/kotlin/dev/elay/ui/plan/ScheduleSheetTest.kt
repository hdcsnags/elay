package dev.elay.ui.plan

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [buildScheduledBlock]/[stepBy] are the Plan "Add block" sheet's pure logic (brief §6: "block
 * construction: correct instants for chosen local time/zone, duration bounds 1min..24h") —
 * exercised here with no Compose, no repository, no ViewModel.
 */
class ScheduleSheetTest {
    private val ownerId = UserId("owner-1")
    private val date = LocalDate(2026, 6, 15)

    @Test
    fun resolvesTheChosenLocalTimeToTheCorrectInstantInTheGivenZone() {
        val zone = TimeZone.of("America/New_York")
        val state = ScheduleSheetState(date = date, zone = zone, startTime = LocalTime(9, 30), durationMinutes = 45)

        val block = buildScheduledBlock(TimeBlockId("b-1"), ownerId, state)

        assertEquals(LocalTime(9, 30), block.startsAt.toLocalDateTime(zone).time)
        assertEquals(date, block.startsAt.toLocalDateTime(zone).date)
        assertEquals(45, (block.endsAt - block.startsAt).inWholeMinutes.toInt())
        assertEquals(zone, block.originTz)
    }

    @Test
    fun durationIsClampedToOneMinuteMinimumRatherThanRejected() {
        val state = ScheduleSheetState(date = date, zone = TimeZone.UTC, durationMinutes = 0)

        val block = buildScheduledBlock(TimeBlockId("b-2"), ownerId, state)

        assertEquals(1, (block.endsAt - block.startsAt).inWholeMinutes.toInt())
    }

    @Test
    fun negativeDurationIsClampedToOneMinuteMinimum() {
        val state = ScheduleSheetState(date = date, zone = TimeZone.UTC, durationMinutes = -30)

        val block = buildScheduledBlock(TimeBlockId("b-2b"), ownerId, state)

        assertEquals(1, (block.endsAt - block.startsAt).inWholeMinutes.toInt())
    }

    @Test
    fun durationIsClampedToTwentyFourHoursMaximumRatherThanRejected() {
        val state = ScheduleSheetState(date = date, zone = TimeZone.UTC, durationMinutes = 10_000)

        val block = buildScheduledBlock(TimeBlockId("b-3"), ownerId, state)

        assertEquals(MAX_BLOCK_DURATION_MINUTES, (block.endsAt - block.startsAt).inWholeMinutes.toInt())
    }

    @Test
    fun blankTitleIsStoredAsNullNotRejected() {
        val state = ScheduleSheetState(date = date, zone = TimeZone.UTC, title = "   ")

        val block = buildScheduledBlock(TimeBlockId("b-4"), ownerId, state)

        assertNull(block.title)
    }

    @Test
    fun linkedTaskAndDefaultsCarryThroughToTheBuiltBlock() {
        val state = ScheduleSheetState(date = date, zone = TimeZone.UTC, linkedTaskId = TaskId("t-1"))

        val block = buildScheduledBlock(TimeBlockId("b-5"), ownerId, state)

        assertEquals(TaskId("t-1"), block.taskId)
        assertEquals(BlockType.Personal, block.type)
        assertEquals(BlockStatus.Scheduled, block.status)
        assertEquals(0L, block.version)
        assertEquals(ownerId, block.ownerId)
    }

    @Test
    fun stepByWrapsForwardAcrossMidnight() {
        assertEquals(LocalTime(0, 15), LocalTime(23, 45).stepBy(30))
    }

    @Test
    fun stepByWrapsBackwardAcrossMidnight() {
        assertEquals(LocalTime(23, 30), LocalTime(0, 0).stepBy(-30))
    }

    @Test
    fun stepByWithinTheSameDayIsPlainAddition() {
        assertEquals(LocalTime(9, 45), LocalTime(9, 30).stepBy(15))
    }
}
