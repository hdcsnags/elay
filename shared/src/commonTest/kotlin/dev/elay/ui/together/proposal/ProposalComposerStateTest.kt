package dev.elay.ui.together.proposal

import dev.elay.domain.model.Candidate
import dev.elay.domain.model.PairId
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalRevision
import dev.elay.domain.model.ProposalState
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.UserId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

/** The composer's pure state machine (council/stage2-timelock-gemini.md §1) — defaults, the
 * add/remove/step transitions, validation's calm error copy, and the built RPC commands. */
class ProposalComposerStateTest {
    private val viewerZone = TimeZone.of("America/New_York")
    private val partnerZone = TimeZone.of("America/Chicago")
    private val now = Instant.parse("2026-09-12T14:00:00Z")
    private val future = LocalDate(2026, 12, 1)

    @Test
    fun newComposerStateDefaultsToTomorrowTenAmSixtyMinutes() {
        val state = newComposerState(now, viewerZone, partnerZone, "Alex")
        assertEquals(1, state.candidates.size)
        val candidate = state.candidates.single()
        val expectedTomorrow = now.toLocalDateTime(viewerZone).date.plus(1, DateTimeUnit.DAY)
        assertEquals(expectedTomorrow, candidate.date)
        assertEquals(LocalTime(10, 0), candidate.startTime)
        assertEquals(60, candidate.durationMinutes)
    }

    @Test
    fun stepCandidateStartRollsTheDateForwardAcrossMidnight() {
        val state =
            newComposerState(now, viewerZone, partnerZone, "Alex")
                .copy(candidates = listOf(ComposerCandidate(date = future, startTime = LocalTime(23, 45))))
        val stepped = state.stepCandidateStart(0, COMPOSER_TIME_STEP_MINUTES)
        val candidate = stepped.candidates.single()
        assertEquals(future.plus(1, DateTimeUnit.DAY), candidate.date)
        assertEquals(LocalTime(0, 0), candidate.startTime)
    }

    @Test
    fun stepCandidateDayMovesTheDateWithoutTouchingTheTime() {
        val state =
            newComposerState(now, viewerZone, partnerZone, "Alex")
                .copy(candidates = listOf(ComposerCandidate(date = future, startTime = LocalTime(9, 0))))
        val stepped = state.stepCandidateDay(0, 3)
        assertEquals(future.plus(3, DateTimeUnit.DAY), stepped.candidates.single().date)
        assertEquals(LocalTime(9, 0), stepped.candidates.single().startTime)
    }

    @Test
    fun stepCandidateDurationClampsToTheOneMinuteToTwentyFourHourBounds() {
        val state =
            newComposerState(now, viewerZone, partnerZone, "Alex")
                .copy(
                    candidates =
                        listOf(
                            ComposerCandidate(date = future, startTime = LocalTime(10, 0), durationMinutes = 5),
                        ),
                )
        val steppedDown = state.stepCandidateDuration(0, -100)
        assertEquals(MIN_CANDIDATE_DURATION_MINUTES, steppedDown.candidates.single().durationMinutes)

        val steppedUp = state.stepCandidateDuration(0, MAX_CANDIDATE_DURATION_MINUTES * 2)
        assertEquals(MAX_CANDIDATE_DURATION_MINUTES, steppedUp.candidates.single().durationMinutes)
    }

    @Test
    fun withAddedCandidateAppendsADayLaterCopyAndStopsAtThree() {
        var state = newComposerState(now, viewerZone, partnerZone, "Alex")
        state = state.withAddedCandidate()
        assertEquals(2, state.candidates.size)
        assertEquals(state.candidates[0].date.plus(1, DateTimeUnit.DAY), state.candidates[1].date)

        state = state.withAddedCandidate()
        assertEquals(3, state.candidates.size)

        state = state.withAddedCandidate() // no-op past the cap
        assertEquals(3, state.candidates.size)
    }

    @Test
    fun withRemovedCandidateNeverRemovesSlotOneOrDropsBelowOne() {
        var state = newComposerState(now, viewerZone, partnerZone, "Alex").withAddedCandidate()
        assertEquals(2, state.candidates.size)

        val afterRemovingZero = state.withRemovedCandidate(0)
        assertEquals(2, afterRemovingZero.candidates.size) // slot 1 is protected

        state = state.withRemovedCandidate(1)
        assertEquals(1, state.candidates.size)

        val afterRemovingOnlySlot = state.withRemovedCandidate(0)
        assertEquals(1, afterRemovingOnlySlot.candidates.size) // floor of 1
    }

    @Test
    fun validateComposerRejectsAPastCandidate() {
        val state =
            newComposerState(now, viewerZone, partnerZone, "Alex")
                .copy(candidates = listOf(ComposerCandidate(date = LocalDate(2020, 1, 1), startTime = LocalTime(9, 0))))
        val result = validateComposer(state, now)
        assertIs<ComposerValidation.Invalid>(result)
        assertEquals("This time has already passed", result.message)
    }

    @Test
    fun validateComposerRejectsAnOutOfBoundsDuration() {
        val state =
            newComposerState(now, viewerZone, partnerZone, "Alex")
                .copy(
                    candidates =
                        listOf(
                            ComposerCandidate(date = future, startTime = LocalTime(9, 0), durationMinutes = 0),
                        ),
                )
        val result = validateComposer(state, now)
        assertIs<ComposerValidation.Invalid>(result)
        assertEquals("Duration must be between 1 minute and 24 hours", result.message)
    }

    @Test
    fun validateComposerRejectsTwoCandidatesAtTheExactSameInstant() {
        val state =
            newComposerState(now, viewerZone, partnerZone, "Alex").copy(
                candidates =
                    listOf(
                        ComposerCandidate(date = future, startTime = LocalTime(9, 0)),
                        ComposerCandidate(date = future, startTime = LocalTime(9, 0)),
                    ),
            )
        val result = validateComposer(state, now)
        assertIs<ComposerValidation.Invalid>(result)
        assertEquals("Options 1 and 2 are at the exact same time", result.message)
    }

    @Test
    fun validateComposerRejectsADeadlineAtOrAfterTheFirstCandidate() {
        val state =
            newComposerState(now, viewerZone, partnerZone, "Alex").copy(
                candidates = listOf(ComposerCandidate(date = future, startTime = LocalTime(9, 0))),
                deadlineOption = DeadlineOption.Custom,
                customDeadline = future.atStartOfDayIn(viewerZone) + 100.hours,
            )
        val result = validateComposer(state, now)
        assertIs<ComposerValidation.Invalid>(result)
        assertEquals("Deadline must be before the first proposed time", result.message)
    }

    @Test
    fun validateComposerAcceptsAWellFormedState() {
        val state =
            newComposerState(now, viewerZone, partnerZone, "Alex")
                .copy(candidates = listOf(ComposerCandidate(date = future, startTime = LocalTime(9, 0))))
        assertEquals(ComposerValidation.Valid, validateComposer(state, now))
    }

    @Test
    fun defaultDeadlineOptionPrefersTwentyFourHoursBeforeAFutureCandidate() {
        assertEquals(DeadlineOption.TwentyFourHoursBefore, defaultDeadlineOption(now, now + 72.hours))
    }

    @Test
    fun defaultDeadlineOptionFallsBackToTwoHoursBeforeForASameDayCandidate() {
        assertEquals(DeadlineOption.TwoHoursBefore, defaultDeadlineOption(now, now + 3.hours))
    }

    @Test
    fun buildCreateProposalCarriesContiguousZeroBasedCandidatesAndTheResolvedDeadline() {
        val state =
            newComposerState(now, viewerZone, partnerZone, "Alex").copy(
                title = "Study session",
                candidates =
                    listOf(
                        ComposerCandidate(date = future, startTime = LocalTime(19, 0), durationMinutes = 60),
                        ComposerCandidate(
                            date = future.plus(1, DateTimeUnit.DAY),
                            startTime = LocalTime(8, 0),
                            durationMinutes = 30,
                        ),
                    ),
                // Explicit rather than relying on newComposerState's own default (tested separately in
                // defaultDeadlineOption*) — this test is only about buildCreateProposal's wiring.
                deadlineOption = DeadlineOption.TwentyFourHoursBefore,
            )
        val command = buildCreateProposal(state, operationId = "op-1")

        assertEquals("op-1", command.operationId)
        assertEquals("Study session", command.title)
        assertEquals(viewerZone.id, command.originZoneId)
        assertEquals(2, command.candidates.size)
        assertEquals(0, command.candidates[0].index)
        assertEquals(1, command.candidates[1].index)
        assertEquals(future.atTime(LocalTime(19, 0)).toInstant(viewerZone), command.candidates[0].startsAt)
        assertEquals(60, command.candidates[0].durationMinutes)
        assertEquals(command.candidates[0].startsAt - 24.hours, command.responseDeadline)
    }

    @Test
    fun counterComposerStatePrefillsFromTheLiveRevisionAndTargetsIt() {
        val proposal = sampleProposal()
        val composer = counterComposerState(proposal, viewerZone, partnerZone, "Alex")
        assertEquals(proposal.id, composer.counterProposalId)
        assertEquals(proposal.currentRevision, composer.counterExpectedRevision)
        assertEquals(1, composer.candidates.size)
        assertEquals("Study session", composer.title)
    }

    @Test
    fun buildCounterResponseTargetsTheExpectedRevision() {
        val proposal = sampleProposal()
        val composer =
            counterComposerState(proposal, viewerZone, partnerZone, "Alex")
                .copy(
                    candidates =
                        listOf(
                            ComposerCandidate(date = future.plus(1, DateTimeUnit.DAY), startTime = LocalTime(9, 0)),
                        ),
                )
        val counter = buildCounterResponse(composer, operationId = "op-2")
        assertEquals(proposal.id, counter.proposalId)
        assertEquals(proposal.currentRevision, counter.expectedRevision)
        assertEquals(1, counter.candidates.size)
    }

    @Test
    fun rescheduleComposerStatePrefillsFromTheAcceptedCandidateAndIsNotACounter() {
        val proposal =
            sampleProposal().copy(
                state = ProposalState.Accepted,
                acceptedRevision = 1,
                acceptedCandidateIdx = 0,
            )
        val composer = rescheduleComposerState(proposal, viewerZone, partnerZone, "Alex", now)
        assertNull(composer.counterProposalId)
        assertEquals(1, composer.candidates.size)
        assertEquals(future, composer.candidates.single().date)
    }

    private fun sampleProposal(): ProposalSummary {
        val start = future.atTime(LocalTime(19, 0)).toInstant(viewerZone)
        return ProposalSummary(
            id = ProposalId("proposal-1"),
            pairId = PairId("pair-1"),
            creatorId = UserId("me"),
            title = "Study session",
            state = ProposalState.Proposed,
            responseDeadline = start - 24.hours,
            originZoneId = viewerZone.id,
            currentRevision = 1,
            acceptedRevision = null,
            acceptedCandidateIdx = null,
            version = 1,
            createdAt = now,
            updatedAt = now,
            revisions =
                listOf(
                    ProposalRevision(
                        revisionNo = 1,
                        authorId = UserId("me"),
                        originZoneId = viewerZone.id,
                        candidates =
                            listOf(
                                Candidate(index = 0, startsAt = start, endsAt = start + 1.hours, durationMinutes = 60),
                            ),
                        createdAt = now,
                    ),
                ),
            responses = emptyList(),
            commitment = null,
        )
    }
}
