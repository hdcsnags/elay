package dev.elay.ui.today

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

/** Calm-tone countdown text (spec §2) — pure, no clock/zone lookups. */
class TodayFormattingTest {
    private val start = Instant.fromEpochMilliseconds(1_000_000_000_000)

    @Test
    fun inProgressWhenNowIsWithinTheBlock() {
        val end = start + 30.minutes
        assertEquals("In progress", startInLabel(now = start + 10.minutes, startsAt = start, endsAt = end))
    }

    @Test
    fun finishedWhenNowIsAtOrAfterTheEnd() {
        val end = start + 30.minutes
        assertEquals("Finished", startInLabel(now = end, startsAt = start, endsAt = end))
    }

    @Test
    fun startsInMinutesUnderAnHour() {
        val now = start - 15.minutes
        assertEquals("Starts in 15m", startInLabel(now = now, startsAt = start, endsAt = start + 1.hours))
    }

    @Test
    fun startsInHoursAndMinutesOverAnHour() {
        val now = start - (2.hours + 5.minutes)
        assertEquals("Starts in 2h 5m", startInLabel(now = now, startsAt = start, endsAt = start + 1.hours))
    }

    @Test
    fun startsInWholeHoursWithNoRemainderMinutes() {
        val now = start - 3.hours
        assertEquals("Starts in 3h", startInLabel(now = now, startsAt = start, endsAt = start + 1.hours))
    }

    // --- Stage 5 wrap-up / next-time copy (council/stage5-retention-gemini.md §B) ---

    @Test
    fun plannedMinutesIsTheWholeMinuteSpanBetweenStartAndEnd() {
        val block = block(startsAt = start, endsAt = start + 1.hours)
        assertEquals(60, block.plannedMinutes())
    }

    @Test
    fun wrapUpSubtitleMatchesSectionBExactly() {
        val block = block(title = "Study session", startsAt = start, endsAt = start + 1.hours)
        assertEquals("Study session · Scheduled for 60m", wrapUpSubtitle(block))
    }

    @Test
    fun wrapUpSubtitleFallsBackToACalmLabelForAnUntitledBlock() {
        val block = block(title = null, startsAt = start, endsAt = start + 45.minutes)
        assertEquals("Untitled block · Scheduled for 45m", wrapUpSubtitle(block))
    }

    @Test
    fun nextTimeBodyCopyUsesTheRanLongFramingWhenSuggestedExceedsStandard() {
        assertEquals(
            "Last time ran 20m long. Book 80m this time?",
            nextTimeBodyCopy(standardMinutes = 60, suggestedMinutes = 80),
        )
    }

    @Test
    fun nextTimeBodyCopyUsesTheFinishedEarlyFramingWhenSuggestedIsBelowStandard() {
        assertEquals(
            "Finished 15m early last time. Book 45m?",
            nextTimeBodyCopy(standardMinutes = 60, suggestedMinutes = 45),
        )
    }

    @Test
    fun wrapUpAccessibilityDescriptionMatchesSectionBsOwnSnippet() {
        val block = block(title = "Study session", startsAt = start, endsAt = start + 1.hours)
        assertEquals(
            "Session wrap-up for Study session. Scheduled for 60 minutes. How did the time go?",
            wrapUpAccessibilityDescription(block),
        )
    }

    @Test
    fun nextTimeAccessibilityDescriptionUsesTheTogetherFramingOnlyForASharedLock() {
        val card =
            NextTimeCardUiState(
                "b-1",
                "Study session",
                standardMinutes = 60,
                suggestedMinutes = 80,
                isSharedLock = true,
            )
        assertEquals(
            "Next time together: Study session. Last time ran 20m long. Book 80m this time?",
            nextTimeAccessibilityDescription(card),
        )
    }

    @Test
    fun nextTimeAccessibilityDescriptionUsesThePlainFramingForAPersonalBlock() {
        val card =
            NextTimeCardUiState(
                "b-1",
                "Study session",
                standardMinutes = 60,
                suggestedMinutes = 80,
                isSharedLock = false,
            )
        assertEquals(
            "Next time: Study session. Last time ran 20m long. Book 80m this time?",
            nextTimeAccessibilityDescription(card),
        )
    }

    private fun block(
        title: String? = "Untitled",
        startsAt: Instant,
        endsAt: Instant,
    ) = TimeBlock(
        id = TimeBlockId("b-1"),
        ownerId = UserId("owner-1"),
        taskId = null,
        title = title,
        startsAt = startsAt,
        endsAt = endsAt,
        originTz = TimeZone.UTC,
        type = BlockType.Personal,
        status = BlockStatus.Scheduled,
        recurrenceRule = null,
        allDay = false,
        version = 1,
    )
}
