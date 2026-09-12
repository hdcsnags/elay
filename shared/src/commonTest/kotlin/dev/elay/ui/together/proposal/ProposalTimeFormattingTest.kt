package dev.elay.ui.together.proposal

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Pure dual-time formatting (council/stage2-timelock-gemini.md §3) — clock/time-range/date
 * formatting, day-crossover badges, the DST-divergence caption, a11y phrasing, and deadline text. */
class ProposalTimeFormattingTest {
    @Test
    fun formatClockTimeRendersTwelveHourWithPeriod() {
        assertEquals("7:00 AM", formatClockTime(LocalTime(7, 0)))
        assertEquals("12:00 PM", formatClockTime(LocalTime(12, 0)))
        assertEquals("12:00 AM", formatClockTime(LocalTime(0, 0)))
        assertEquals("11:30 PM", formatClockTime(LocalTime(23, 30)))
    }

    @Test
    fun formatTimeRangeSharesOneSuffixWithinTheSamePeriod() {
        assertEquals("7:00 – 8:00 PM", formatTimeRange(LocalTime(19, 0), LocalTime(20, 0)))
    }

    @Test
    fun formatTimeRangeShowsBothSuffixesAcrossAPeriodChange() {
        assertEquals("11:30 PM – 12:30 AM", formatTimeRange(LocalTime(23, 30), LocalTime(0, 30)))
    }

    @Test
    fun relativeDayBadgeIsNullOnTheSameDay() {
        val date = LocalDate(2026, 9, 12)
        assertNull(relativeDayBadge(date, date))
    }

    @Test
    fun relativeDayBadgeUsesTomorrowYesterdayForASingleDayCrossover() {
        val today = LocalDate(2026, 9, 12)
        val tomorrow = LocalDate(2026, 9, 13)
        val yesterday = LocalDate(2026, 9, 11)
        assertEquals("(Tomorrow)", relativeDayBadge(today, tomorrow))
        assertEquals("(Yesterday)", relativeDayBadge(today, yesterday))
    }

    @Test
    fun relativeDayBadgeUsesASignedCountBeyondOneDay() {
        val start = LocalDate(2026, 9, 12)
        assertEquals("(+2 day)", relativeDayBadge(start, LocalDate(2026, 9, 14)))
        assertEquals("(-2 day)", relativeDayBadge(start, LocalDate(2026, 9, 10)))
    }

    @Test
    fun clockChangeCaptionIsNullWhenTheOffsetDifferenceMatchesToday() {
        val toronto = TimeZone.of("America/Toronto")
        val chicago = TimeZone.of("America/Chicago")
        // Toronto/Chicago share the same DST calendar (both US rules) — no divergence gap exists.
        val now = Instant.parse("2026-01-15T12:00:00Z")
        val candidate = Instant.parse("2026-07-15T12:00:00Z")
        assertNull(clockChangeCaption(now, candidate, toronto, chicago))
    }

    @Test
    fun clockChangeCaptionExplainsTheNorthAmericaVsEuropeDstGapCalmly() {
        // 2026: Toronto (America/Toronto) springs forward Sun Mar 8; London (Europe/London)
        // doesn't spring forward until Sun Mar 29 — a real ~3-week window where the pair's usual
        // 5-hour gap temporarily narrows to 4.
        val toronto = TimeZone.of("America/Toronto")
        val london = TimeZone.of("Europe/London")
        val now = Instant.parse("2026-01-15T12:00:00Z")
        val candidateStart = Instant.parse("2026-03-15T18:00:00Z")

        val caption = clockChangeCaption(now, candidateStart, toronto, london)

        assertEquals(
            "Time difference is 4 hours on this day (1 hour less than usual due to clock change)",
            caption,
        )
    }

    @Test
    fun buildDualTimeLinesOrdersViewerFirstAndBadgesAPartnerDayCrossover() {
        val viewerZone = TimeZone.of("America/New_York")
        val partnerZone = TimeZone.of("Asia/Kolkata") // +5:30 vs viewer, guarantees a crossover for a late slot
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val start = Instant.parse("2026-06-15T23:00:00Z") // 7 PM EDT -> 4:30 AM IST next day
        val end = start + 1.hours

        val lines = buildDualTimeLines(start, end, viewerZone, partnerZone, "Alex", now, includeDate = false)

        assertEquals("7:00 – 8:00 PM for you", lines.viewerLine)
        assertEquals("4:30 – 5:30 AM for Alex (Tomorrow)", lines.partnerLine)
    }

    @Test
    fun buildDualTimeLinesFallsBackToThemWhenThePartnerNameIsBlank() {
        val zone = TimeZone.of("America/New_York")
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val start = Instant.parse("2026-06-15T23:00:00Z")
        val lines = buildDualTimeLines(start, start + 1.hours, zone, zone, "", now)
        assertEquals("7:00 – 8:00 PM for them", lines.partnerLine)
    }

    @Test
    fun buildDualTimeLinesPrefixesEachLineWithItsOwnDateWhenRequested() {
        val viewerZone = TimeZone.of("America/New_York")
        val partnerZone = TimeZone.of("Asia/Kolkata")
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val start = Instant.parse("2026-06-15T23:00:00Z")
        val lines = buildDualTimeLines(start, start + 1.hours, viewerZone, partnerZone, "Alex", now, includeDate = true)
        assertEquals("Mon, Jun 15 · 7:00 – 8:00 PM for you", lines.viewerLine)
        assertEquals("Tue, Jun 16 · 4:30 – 5:30 AM for Alex (Tomorrow)", lines.partnerLine)
    }

    @Test
    fun candidateAccessibilityDescriptionMentionsTheCrossoverAndPartnerName() {
        val viewerZone = TimeZone.of("America/New_York")
        val partnerZone = TimeZone.of("Asia/Kolkata")
        val start = Instant.parse("2026-06-15T23:00:00Z")
        val end = start + 1.hours
        val description = candidateAccessibilityDescription(0, start, end, viewerZone, partnerZone, "Alex")
        assertEquals(
            "Option 1: 7:00 PM to 8:00 PM your time, which is 4:30 AM to 5:30 AM Tue, next day for Alex",
            description,
        )
    }

    @Test
    fun candidateStateDescriptionVocalizesSelectionAndOrdinal() {
        assertEquals("Selected: Option 1 of 3", candidateStateDescription(0, 3, selected = true))
        assertEquals("Unselected: Option 2 of 3", candidateStateDescription(1, 3, selected = false))
    }

    @Test
    fun deadlineCountdownLabelIsCalmAndExpiresAtTheDeadline() {
        val now = Instant.parse("2026-06-15T12:00:00Z")
        assertEquals("Expires in 4h 12m", deadlineCountdownLabel(now, now + 4.hours + 12.minutes))
        assertEquals("Expired", deadlineCountdownLabel(now, now))
    }

    @Test
    fun deadlineWallClockLabelUsesTodayTomorrowThenAnExplicitDate() {
        val zone = TimeZone.of("America/New_York")
        val now = Instant.parse("2026-06-15T12:00:00Z") // 8 AM EDT
        assertEquals("Today at 6:00 PM", deadlineWallClockLabel(now, Instant.parse("2026-06-15T22:00:00Z"), zone))
        assertEquals("Tomorrow at 9:00 AM", deadlineWallClockLabel(now, Instant.parse("2026-06-16T13:00:00Z"), zone))
        assertEquals("Fri, Jun 19 at 9:00 AM", deadlineWallClockLabel(now, Instant.parse("2026-06-19T13:00:00Z"), zone))
    }

    @Test
    fun responseDeadlineLineAddsACountdownOnlyBeforeTheDeadline() {
        val zone = TimeZone.of("America/New_York")
        val now = Instant.parse("2026-06-15T12:00:00Z") // 8:00 AM EDT
        val deadline = now + 3.hours // 11:00 AM EDT
        assertEquals(
            "Response deadline: Today at 11:00 AM (in 3h)",
            responseDeadlineLine(now, deadline, zone),
        )
        assertEquals(
            "Response deadline: Today at 11:00 AM",
            responseDeadlineLine(deadline, deadline, zone),
        )
    }
}
