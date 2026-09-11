package dev.elay.ui.today

import kotlinx.datetime.Instant
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
}
