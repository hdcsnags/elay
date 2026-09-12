package dev.elay.ui.together

import dev.elay.domain.model.PairError
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Pure formatting/copy helpers — no repository, no clock lookups. */
class TogetherFormattingTest {
    private val start = Instant.fromEpochMilliseconds(1_000_000_000_000)

    @Test
    fun groupsATenCharacterCodeAsFiveAndFive() {
        assertEquals("ABCDE-FGHJK", formatInviteCode("ABCDEFGHJK"))
    }

    @Test
    fun reGroupsAnAlreadyGroupedCodeIdempotently() {
        assertEquals("ABCDE-FGHJK", formatInviteCode("ABCDE-FGHJK"))
    }

    @Test
    fun leavesAnUnexpectedLengthCodeUnchangedRatherThanMangleIt() {
        assertEquals("SHORT", formatInviteCode("SHORT"))
    }

    @Test
    fun expiresInMinutesUnderAnHour() {
        assertEquals("Expires in 15m", expiresInLabel(now = start, expiresAt = start + 15.minutes))
    }

    @Test
    fun expiresInDaysWhenOverTwentyFourHours() {
        assertEquals("Expires in 1d 2h", expiresInLabel(now = start, expiresAt = start + 26.hours))
    }

    @Test
    fun expiredWhenNowIsAtOrAfterExpiry() {
        assertEquals("Expired", expiresInLabel(now = start, expiresAt = start))
    }

    @Test
    fun invalidOrUnavailableNeverExpandsIntoAFinerReason() {
        val message = PairError.InvalidOrUnavailable.calmMessage()
        assertEquals("That didn't go through — check the code and try again.", message)
    }

    @Test
    fun unrecognizedOutcomeStillRendersACalmGenericMessage() {
        val message = PairError.Unrecognized("pair.commitment_changed.v1").calmMessage()
        assertEquals("Something didn't go through. Please try again.", message)
    }

    @Test
    fun retryableNetworkFailureInvitesAnotherAttempt() {
        assertEquals("Couldn't reach the server — try again in a moment.", networkFailureMessage(retryable = true))
    }

    @Test
    fun nonRetryableNetworkFailureDoesNotPromiseASecondAttemptWillHelp() {
        assertEquals("Couldn't reach the server right now.", networkFailureMessage(retryable = false))
    }

    // `start` is 2001-09-09T01:46:40Z — squarely inside northern-hemisphere DST, so Toronto/New
    // York read UTC-4 here (matches the "Toronto · UTC-4" example in the work item).

    @Test
    fun rendersASimpleIanaIdAsItsCityPlusCurrentOffset() {
        assertEquals("Toronto · UTC-4", formatZoneLabel("America/Toronto", now = start))
    }

    @Test
    fun rendersAnUnderscoredCitySegmentWithASpace() {
        assertEquals("New York · UTC-4", formatZoneLabel("America/New_York", now = start))
    }

    @Test
    fun rendersAHalfHourOffsetZoneWithMinutes() {
        assertEquals("Kolkata · UTC+5:30", formatZoneLabel("Asia/Kolkata", now = start))
    }

    @Test
    fun collapsesEtcUtcToBareUtcRatherThanDoublingItUp() {
        assertEquals("UTC", formatZoneLabel("Etc/UTC", now = start))
    }

    @Test
    fun fallsBackToTheBareIdWhenItIsNotARecognizedIanaZone() {
        assertEquals("not-a-zone", formatZoneLabel("not-a-zone", now = start))
    }
}
