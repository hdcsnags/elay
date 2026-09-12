package dev.elay.ui.together.proposal

import dev.elay.domain.model.Candidate
import dev.elay.domain.model.PairId
import dev.elay.domain.model.PairMember
import dev.elay.domain.model.PairSnapshot
import dev.elay.domain.model.PairState
import dev.elay.domain.model.PairStatus
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalResult
import dev.elay.domain.model.ProposalRevision
import dev.elay.domain.model.ProposalState
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.RespondProposal
import dev.elay.domain.model.UserId
import dev.elay.ui.together.fake.FakePairRepository
import dev.elay.ui.together.proposal.fake.FakeProposalRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** [TogetherProposalViewModel] state machine transitions over both frozen data surfaces
 * (contracts/stage2-timelock.md; contracts/stage1-pairing.md). */
class TogetherProposalViewModelTest {
    private val viewerZone = TimeZone.of("America/New_York")
    private val now = Instant.parse("2026-09-12T14:00:00Z")

    private fun pairedRepository(): FakePairRepository =
        FakePairRepository(
            initialState =
                PairState.Paired(
                    PairSnapshot(
                        id = PairId("pair-1"),
                        status = PairStatus.Active,
                        version = 1,
                        channelTopic = "pair:pair-1:gen-1",
                        members =
                            listOf(
                                PairMember(UserId("me"), "You", "America/New_York", now),
                                PairMember(UserId("peer"), "Jordan", "America/Chicago", now),
                            ),
                        activeInviteExpiresAt = null,
                    ),
                ),
        )

    private fun TestScope.viewModel(
        proposalRepository: FakeProposalRepository = FakeProposalRepository(),
        pairRepository: FakePairRepository = pairedRepository(),
    ) = TogetherProposalViewModel(
        repository = proposalRepository,
        pairRepository = pairRepository,
        scope = backgroundScope,
        selfId = UserId("me"),
        viewerZone = viewerZone,
        clock =
            object : Clock {
                override fun now() = now
            },
    )

    @Test
    fun learnsThePartnerFromThePairRepository() =
        runTest {
            val vm = viewModel()
            runCurrent()
            assertEquals(
                "Jordan",
                vm.state.value.partner
                    ?.displayName,
            )
        }

    @Test
    fun openComposerDefaultsToTomorrowTenAmAndThePartnersZone() =
        runTest {
            val vm = viewModel()
            runCurrent()
            vm.openComposer()
            val composer = vm.state.value.composer
            requireNotNull(composer)
            assertEquals(TimeZone.of("America/Chicago"), composer.partnerZone)
            assertEquals(1, composer.candidates.size)
        }

    @Test
    fun submitComposerCallsCreateWithOneToThreeCandidatesAndCorrectInstants() =
        runTest {
            val repository = FakeProposalRepository()
            val vm = viewModel(proposalRepository = repository)
            runCurrent()
            vm.openComposer()
            vm.addComposerCandidate()
            repository.nextCreateResult = ProposalResult.Applied(sampleProposal())

            vm.submitComposer()
            runCurrent()

            assertEquals(1, repository.createCalls.size)
            val command = repository.createCalls.single()
            assertEquals(2, command.candidates.size)
            assertEquals(0, command.candidates[0].index)
            assertEquals(1, command.candidates[1].index)
            assertTrue(command.candidates[0].startsAt < command.candidates[1].startsAt)
            assertNull(vm.state.value.composer) // closed on success
        }

    @Test
    fun submitComposerWithAnInvalidStateShowsTheCalmErrorAndDoesNotCallCreate() =
        runTest {
            val repository = FakeProposalRepository()
            val vm = viewModel(proposalRepository = repository)
            runCurrent()
            vm.openComposer()
            // Force an already-past candidate.
            vm.stepComposerCandidateDay(0, -365)

            vm.submitComposer()
            runCurrent()

            assertTrue(repository.createCalls.isEmpty())
            assertEquals(
                "This time has already passed",
                vm.state.value.composer
                    ?.errorMessage,
            )
        }

    @Test
    fun acceptCandidateRespondsWithTheSelectedCandidateIndex() =
        runTest {
            val repository = FakeProposalRepository()
            val proposal = sampleProposal()
            repository.emitActive(listOf(proposal))
            val vm = viewModel(proposalRepository = repository)
            runCurrent()

            vm.selectCandidate(proposal.id, 1)
            repository.nextRespondResult = ProposalResult.Applied(proposal)
            vm.acceptCandidate(proposal.id)
            runCurrent()

            val command = assertIs<RespondProposal.Accept>(repository.respondCalls.single())
            assertEquals(1, command.candidateIdx)
            assertEquals(proposal.currentRevision, command.expectedRevision)
        }

    @Test
    fun openCounterComposerPrefillsFromTheProposalsLiveRevision() =
        runTest {
            val repository = FakeProposalRepository()
            val proposal = sampleProposal()
            repository.emitActive(listOf(proposal))
            val vm = viewModel(proposalRepository = repository)
            runCurrent()

            vm.openCounterComposer(proposal.id)

            val composer = vm.state.value.composer
            requireNotNull(composer)
            assertEquals(proposal.id, composer.counterProposalId)
            assertEquals(proposal.currentRevision, composer.counterExpectedRevision)

            repository.nextRespondResult = ProposalResult.Applied(proposal)
            vm.submitComposer()
            runCurrent()
            assertIs<RespondProposal.Counter>(repository.respondCalls.single())
        }

    @Test
    fun declineRequiresConfirmationBeforeRespondingAndClearsThePendingState() =
        runTest {
            val repository = FakeProposalRepository()
            val proposal = sampleProposal()
            repository.emitActive(listOf(proposal))
            val vm = viewModel(proposalRepository = repository)
            runCurrent()

            vm.requestDecline(proposal.id)
            assertEquals(proposal.id, vm.state.value.pendingDecline)
            assertTrue(repository.respondCalls.isEmpty())

            repository.nextRespondResult = ProposalResult.Applied(proposal)
            vm.confirmDecline()
            runCurrent()

            assertNull(vm.state.value.pendingDecline)
            assertIs<RespondProposal.Decline>(repository.respondCalls.single())
        }

    @Test
    fun withdrawRequiresConfirmationBeforeCancelling() =
        runTest {
            val repository = FakeProposalRepository()
            val proposal = sampleProposal()
            repository.emitActive(listOf(proposal))
            val vm = viewModel(proposalRepository = repository)
            runCurrent()

            vm.requestWithdraw(proposal.id)
            vm.cancelWithdrawConfirmation()
            runCurrent()
            assertNull(vm.state.value.pendingWithdraw)
            assertTrue(repository.cancelCalls.isEmpty())

            vm.requestWithdraw(proposal.id)
            repository.nextCancelResult = ProposalResult.Applied(proposal)
            vm.confirmWithdraw()
            runCurrent()
            assertEquals(listOf(proposal.id), repository.cancelCalls)
        }

    @Test
    fun aConflictResultSurfacesACalmActionErrorWithoutCrashing() =
        runTest {
            val repository = FakeProposalRepository()
            val proposal = sampleProposal()
            repository.emitActive(listOf(proposal))
            val vm = viewModel(proposalRepository = repository)
            runCurrent()

            repository.nextRespondResult = ProposalResult.Conflict(2, ProposalState.Countered)
            vm.acceptCandidate(proposal.id)
            runCurrent()

            assertTrue(
                vm.state.value.actionError!!
                    .isNotBlank(),
            )
        }

    @Test
    fun aFailedResultRendersACalmNetworkMessageNotTheRawReason() =
        runTest {
            val repository = FakeProposalRepository()
            val proposal = sampleProposal()
            repository.emitActive(listOf(proposal))
            val vm = viewModel(proposalRepository = repository)
            runCurrent()

            repository.nextRespondResult = ProposalResult.Failed("connection_reset_by_peer", retryable = true)
            vm.acceptCandidate(proposal.id)
            runCurrent()

            assertEquals("Couldn't reach the server — try again in a moment.", vm.state.value.actionError)
        }

    @Test
    fun cardsAtMapsActiveAndHistoryProposalsForThePartner() =
        runTest {
            val repository = FakeProposalRepository()
            val proposal = sampleProposal()
            repository.emitActive(listOf(proposal))
            val vm = viewModel(proposalRepository = repository)
            runCurrent()

            val cards = vm.cardsAt(now)
            assertEquals(1, cards.size)
            assertIs<IncomingProposalCard>(cards.single())
        }

    @Test
    fun dismissClosedProposalHidesItFromCardsAt() =
        runTest {
            val repository = FakeProposalRepository()
            val proposal = sampleProposal().copy(state = ProposalState.Expired)
            repository.emitHistory(listOf(proposal))
            val vm = viewModel(proposalRepository = repository)
            runCurrent()

            assertEquals(1, vm.cardsAt(now).size)
            vm.dismissClosedProposal(proposal.id)
            assertEquals(0, vm.cardsAt(now).size)
        }

    private fun sampleProposal(): ProposalSummary {
        val start = LocalDate(2026, 9, 20).atTime(LocalTime(19, 0)).toInstant(viewerZone)
        return ProposalSummary(
            id = ProposalId("p1"),
            pairId = PairId("pair-1"),
            creatorId = UserId("peer"),
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
                        authorId = UserId("peer"),
                        originZoneId = viewerZone.id,
                        candidates =
                            listOf(
                                Candidate(0, start, start + 1.hours, 60),
                                Candidate(1, start + 5.hours, start + 6.hours, 60),
                            ),
                        createdAt = now,
                    ),
                ),
            responses = emptyList(),
            commitment = null,
        )
    }
}
