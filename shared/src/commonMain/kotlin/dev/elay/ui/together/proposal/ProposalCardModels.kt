package dev.elay.ui.together.proposal

import dev.elay.domain.model.Candidate
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalState
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.ResponseKind
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/** The Together feed's fallback title (§2.1: "Title: Optional proposal title ... or fallback
 * 'Shared block'"). */
private const val TITLE_FALLBACK = "Shared block"

/** One candidate's fully-formatted dual-time presentation, ready for a chip/row (§2, §3).
 * [startsAt]/[endsAt]/[durationMinutes] are carried alongside the formatted [lines] (not just the
 * raw [Candidate] itself) so an incoming card's chips can rebuild an
 * `rpc_self_conflict_hints` request (contracts/stage4-honest-availability.md; this seat's grant §3
 * "Responder certainty") without this file's Compose-facing callers reaching back into
 * [dev.elay.domain.model.ProposalRevision]. */
data class CandidateChipUiModel(
    val index: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    val durationMinutes: Int,
    val lines: DualTimeLines,
    val accessibilityDescription: String,
)

private fun Candidate.toChip(
    viewerZone: TimeZone,
    partnerZone: TimeZone,
    partnerDisplayName: String,
    now: Instant,
): CandidateChipUiModel =
    CandidateChipUiModel(
        index = index,
        startsAt = startsAt,
        endsAt = endsAt,
        durationMinutes = durationMinutes,
        lines =
            buildDualTimeLines(
                startsAt,
                endsAt,
                viewerZone,
                partnerZone,
                partnerDisplayName,
                now,
                includeDate = true,
            ),
        accessibilityDescription =
            candidateAccessibilityDescription(
                index,
                startsAt,
                endsAt,
                viewerZone,
                partnerZone,
                partnerDisplayName,
            ),
    )

/** One Together-feed card, mapped from a [ProposalSummary] by [toCardUiModel] (§2's five states). */
sealed interface ProposalCardUiModel {
    val id: ProposalId
    val title: String
}

/** §2.1 (revision 1) / §2.3 (revision 2+, "Countered") — the card for whoever did **not** author
 * the live revision; they're the one who can accept/counter/decline it. */
data class IncomingProposalCard(
    override val id: ProposalId,
    override val title: String,
    val partnerDisplayName: String,
    val isCountered: Boolean,
    val revisionNo: Int,
    val candidates: List<CandidateChipUiModel>,
    val previousCandidates: List<CandidateChipUiModel>,
    val deadline: Instant,
) : ProposalCardUiModel

/** §2.2 — the card for whoever authored the live revision; they're waiting on the other member. */
data class OutgoingProposalCard(
    override val id: ProposalId,
    override val title: String,
    val partnerDisplayName: String,
    val candidates: List<CandidateChipUiModel>,
    val deadline: Instant,
) : ProposalCardUiModel

/** §2.4 — the winning time-lock, now on both members' plans. */
data class AcceptedProposalCard(
    override val id: ProposalId,
    override val title: String,
    val winning: CandidateChipUiModel,
) : ProposalCardUiModel

/** §2.5's three calm-resolution reasons. */
enum class ClosedProposalReason { Declined, Expired, Cancelled }

/** §2.5 — declined/expired/cancelled, calm copy with no shame banners. [byMe] picks the
 * "You ..."/"$partner ..." wording; for [ClosedProposalReason.Cancelled] this is approximated as
 * "the proposal's creator" since the frozen [ProposalSummary] surface carries no separate
 * "cancelled by" field (a documented simplification — see this seat's report). */
data class ClosedProposalCard(
    override val id: ProposalId,
    override val title: String,
    val partnerDisplayName: String,
    val reason: ClosedProposalReason,
    val byMe: Boolean,
) : ProposalCardUiModel

/**
 * Maps one [ProposalSummary] to its Together-feed card (§2), given the caller's own [selfId] and
 * the pair's zones/display name for dual-time rendering (§3). `null` for [ProposalState.Draft] —
 * no Stage-2 RPC ever creates one client-side (see [ProposalSummary]'s neighboring kdoc).
 */
@Suppress("CyclomaticComplexMethod") // one branch per ProposalState (contract §4's frozen state machine)
fun ProposalSummary.toCardUiModel(
    selfId: UserId,
    partnerDisplayName: String,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
    now: Instant,
): ProposalCardUiModel? {
    val displayTitle = title.ifBlank { TITLE_FALLBACK }
    return when (state) {
        ProposalState.Draft -> null
        ProposalState.Proposed, ProposalState.Countered ->
            negotiationCard(displayTitle, selfId, partnerDisplayName, viewerZone, partnerZone, now)
        ProposalState.Accepted, ProposalState.Completed ->
            acceptedCard(displayTitle, viewerZone, partnerZone, partnerDisplayName, now)
        ProposalState.Declined ->
            ClosedProposalCard(
                id,
                displayTitle,
                partnerDisplayName,
                ClosedProposalReason.Declined,
                byMe = declinedByMe(selfId),
            )
        ProposalState.Expired ->
            ClosedProposalCard(id, displayTitle, partnerDisplayName, ClosedProposalReason.Expired, byMe = false)
        ProposalState.Cancelled ->
            ClosedProposalCard(
                id,
                displayTitle,
                partnerDisplayName,
                ClosedProposalReason.Cancelled,
                byMe =
                    creatorId == selfId,
            )
    }
}

private fun ProposalSummary.declinedByMe(selfId: UserId): Boolean =
    responses.any { it.response == ResponseKind.Decline && it.userId == selfId }

@Suppress("LongParameterList") // one field per §2's incoming/outgoing render inputs (title/self/pair zones+now)
private fun ProposalSummary.negotiationCard(
    displayTitle: String,
    selfId: UserId,
    partnerDisplayName: String,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
    now: Instant,
): ProposalCardUiModel? {
    val revision = revisions.maxByOrNull { it.revisionNo } ?: return null
    val chips =
        revision.candidates
            .sortedBy {
                it.index
            }.map { it.toChip(viewerZone, partnerZone, partnerDisplayName, now) }
    val isCountered = revision.revisionNo > 1
    return if (revision.authorId == selfId) {
        OutgoingProposalCard(id, displayTitle, partnerDisplayName, chips, responseDeadline)
    } else {
        val previous =
            if (isCountered) {
                revisions
                    .filter { it.revisionNo == revision.revisionNo - 1 }
                    .flatMap { it.candidates }
                    .sortedBy { it.index }
                    .map { it.toChip(viewerZone, partnerZone, partnerDisplayName, now) }
            } else {
                emptyList()
            }
        IncomingProposalCard(
            id,
            displayTitle,
            partnerDisplayName,
            isCountered,
            revision.revisionNo,
            chips,
            previous,
            responseDeadline,
        )
    }
}

@Suppress("ReturnCount") // guard-clause style: null returns for a missing index/candidate (defensive only)
private fun ProposalSummary.acceptedCard(
    displayTitle: String,
    viewerZone: TimeZone,
    partnerZone: TimeZone,
    partnerDisplayName: String,
    now: Instant,
): ProposalCardUiModel? {
    val revisionNo = acceptedRevision ?: currentRevision
    val idx = acceptedCandidateIdx ?: return null
    val winning =
        revisions.firstOrNull { it.revisionNo == revisionNo }?.candidates?.firstOrNull { it.index == idx }
            ?: return null
    return AcceptedProposalCard(id, displayTitle, winning.toChip(viewerZone, partnerZone, partnerDisplayName, now))
}
