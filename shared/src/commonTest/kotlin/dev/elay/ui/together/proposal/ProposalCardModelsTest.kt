package dev.elay.ui.together.proposal

import dev.elay.domain.model.Candidate
import dev.elay.domain.model.PairId
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalResponse
import dev.elay.domain.model.ProposalRevision
import dev.elay.domain.model.ProposalState
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.ResponseKind
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

/** [ProposalSummary.toCardUiModel] — §2's five card states, mapped from the caller's own id and
 * the live/latest revision's author. */
class ProposalCardModelsTest {
    private val me = UserId("me")
    private val partner = UserId("partner")
    private val viewerZone = TimeZone.of("America/New_York")
    private val partnerZone = TimeZone.of("America/Chicago")
    private val now = Instant.parse("2026-09-12T14:00:00Z")
    private val start = Instant.parse("2026-09-20T19:00:00Z")

    private fun candidate(
        index: Int = 0,
        start: Instant = this.start,
    ) = Candidate(index, start, start + 1.hours, 60)

    private fun revision(
        revisionNo: Int,
        authorId: UserId,
        candidates: List<Candidate> = listOf(candidate()),
    ) = ProposalRevision(revisionNo, authorId, viewerZone.id, candidates, now)

    @Suppress("LongParameterList") // test fixture builder — one param per ProposalSummary field a case needs to vary
    private fun baseProposal(
        state: ProposalState,
        revisions: List<ProposalRevision>,
        responses: List<ProposalResponse> = emptyList(),
        creatorId: UserId = me,
        acceptedRevision: Int? = null,
        acceptedCandidateIdx: Int? = null,
        title: String = "Study session",
    ) = ProposalSummary(
        id = ProposalId("p1"),
        pairId = PairId("pair-1"),
        creatorId = creatorId,
        title = title,
        state = state,
        responseDeadline = start - 24.hours,
        originZoneId = viewerZone.id,
        currentRevision = revisions.maxOf { it.revisionNo },
        acceptedRevision = acceptedRevision,
        acceptedCandidateIdx = acceptedCandidateIdx,
        version = 1,
        createdAt = now,
        updatedAt = now,
        revisions = revisions,
        responses = responses,
        commitment = null,
    )

    private fun ProposalSummary.card() = toCardUiModel(me, "Alex", viewerZone, partnerZone, now)

    @Test
    fun proposedAuthoredByMeIsOutgoing() {
        val proposal = baseProposal(ProposalState.Proposed, listOf(revision(1, me)))
        val card = assertIs<OutgoingProposalCard>(proposal.card())
        assertEquals("Study session", card.title)
        assertEquals(1, card.candidates.size)
    }

    @Test
    fun proposedAuthoredByThePartnerIsIncomingAndNotMarkedCountered() {
        val proposal = baseProposal(ProposalState.Proposed, listOf(revision(1, partner)))
        val card = assertIs<IncomingProposalCard>(proposal.card())
        assertEquals(false, card.isCountered)
        assertEquals(1, card.revisionNo)
        assertEquals(emptyList(), card.previousCandidates)
    }

    @Test
    fun blankTitleFallsBackToSharedBlock() {
        val proposal = baseProposal(ProposalState.Proposed, listOf(revision(1, partner)), title = "")
        assertEquals("Shared block", proposal.card()!!.title)
    }

    @Test
    fun counteredAuthoredByThePartnerIsIncomingWithPreviousCandidates() {
        val proposal =
            baseProposal(
                ProposalState.Countered,
                listOf(
                    revision(1, me, listOf(candidate(0, start))),
                    revision(2, partner, listOf(candidate(0, start + 2.hours))),
                ),
            )
        val card = assertIs<IncomingProposalCard>(proposal.card())
        assertEquals(true, card.isCountered)
        assertEquals(2, card.revisionNo)
        assertEquals(1, card.previousCandidates.size)
    }

    @Test
    fun counteredAuthoredByMeIsOutgoingWaiting() {
        val proposal =
            baseProposal(
                ProposalState.Countered,
                listOf(
                    revision(1, partner, listOf(candidate(0, start))),
                    revision(2, me, listOf(candidate(0, start + 2.hours))),
                ),
            )
        assertIs<OutgoingProposalCard>(proposal.card())
    }

    @Test
    fun acceptedShowsTheWinningCandidate() {
        val proposal =
            baseProposal(
                ProposalState.Accepted,
                listOf(revision(1, me, listOf(candidate(0, start), candidate(1, start + 5.hours)))),
                acceptedRevision = 1,
                acceptedCandidateIdx = 1,
            )
        val card = assertIs<AcceptedProposalCard>(proposal.card())
        assertEquals(1, card.winning.index)
    }

    @Test
    fun completedAlsoRendersAsAnAcceptedCard() {
        val proposal =
            baseProposal(
                ProposalState.Completed,
                listOf(revision(1, me)),
                acceptedRevision = 1,
                acceptedCandidateIdx = 0,
            )
        assertIs<AcceptedProposalCard>(proposal.card())
    }

    @Test
    fun declinedByMeUsesTheYouWording() {
        val proposal =
            baseProposal(
                ProposalState.Declined,
                listOf(revision(1, partner)),
                responses =
                    listOf(
                        ProposalResponse("r1", ProposalId("p1"), 1, me, ResponseKind.Decline, null, null, now),
                    ),
            )
        val card = assertIs<ClosedProposalCard>(proposal.card())
        assertEquals(ClosedProposalReason.Declined, card.reason)
        assertEquals(true, card.byMe)
    }

    @Test
    fun declinedByThePartnerUsesTheirWording() {
        val proposal =
            baseProposal(
                ProposalState.Declined,
                listOf(revision(1, me)),
                responses =
                    listOf(
                        ProposalResponse("r1", ProposalId("p1"), 1, partner, ResponseKind.Decline, null, null, now),
                    ),
            )
        val card = assertIs<ClosedProposalCard>(proposal.card())
        assertEquals(false, card.byMe)
    }

    @Test
    fun expiredIsNeverAttributedToEitherMember() {
        val proposal = baseProposal(ProposalState.Expired, listOf(revision(1, me)))
        val card = assertIs<ClosedProposalCard>(proposal.card())
        assertEquals(ClosedProposalReason.Expired, card.reason)
        assertEquals(false, card.byMe)
    }

    @Test
    fun cancelledByMeWhenIAmTheCreator() {
        val proposal = baseProposal(ProposalState.Cancelled, listOf(revision(1, me)), creatorId = me)
        val card = assertIs<ClosedProposalCard>(proposal.card())
        assertEquals(ClosedProposalReason.Cancelled, card.reason)
        assertEquals(true, card.byMe)
    }

    @Test
    fun cancelledByThePartnerWhenTheyAreTheCreator() {
        val proposal = baseProposal(ProposalState.Cancelled, listOf(revision(1, partner)), creatorId = partner)
        val card = assertIs<ClosedProposalCard>(proposal.card())
        assertEquals(false, card.byMe)
    }

    @Test
    fun draftMapsToNoCard() {
        val proposal = baseProposal(ProposalState.Draft, listOf(revision(1, me)))
        assertNull(proposal.card())
    }
}
