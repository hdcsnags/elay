package dev.elay.data.remote.dto

import dev.elay.domain.model.Candidate
import dev.elay.domain.model.Commitment
import dev.elay.domain.model.CommitmentId
import dev.elay.domain.model.CommitmentState
import dev.elay.domain.model.PairId
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalResponse
import dev.elay.domain.model.ProposalRevision
import dev.elay.domain.model.ProposalState
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.ResponseKind
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for Stage 2 time-lock negotiation (contracts/stage2-timelock.md;
 * council/stage2-timelock-sol.md §A.1-§A.2). snake_case keys, ISO-8601 UTC instant strings — same
 * house convention as the Phase 1 planner DTOs (contracts/phase1-planner.md §4) and the Stage 1
 * pairing DTOs ([PairSnapshotDto] et al).
 *
 * No `contracts/fixtures/proposal-*.json` exists yet in this seat's grant (A3 owns that path and
 * produces it from real RPC output — lead amendment 2). These DTOs, and the fixtures in
 * `ProposalDtosTest`, are hand-authored from council/stage2-timelock-sol.md's frozen field lists
 * the same way B3's [PairSnapshotDto] fixture was for Stage 1 — the lead diffs both at merge and
 * any mismatch is fixed on this seat's side unless the SQL deviates from the contract.
 *
 * One `proposal_revisions.candidates[]` element (contract §A.1) starts below. Ordered array;
 * `candidate_idx` is carried explicitly on the wire rather than relied upon implicitly from array
 * position.
 */
@Serializable
data class CandidateDto(
    @SerialName("candidate_idx") val candidateIdx: Int,
    @SerialName("starts_at_utc") val startsAtUtc: String,
    @SerialName("ends_at_utc") val endsAtUtc: String,
    @SerialName("duration_min") val durationMin: Int,
)

fun CandidateDto.toDomain(): Candidate =
    Candidate(
        index = candidateIdx,
        startsAt = Instant.parse(startsAtUtc),
        endsAt = Instant.parse(endsAtUtc),
        durationMinutes = durationMin,
    )

fun Candidate.toDto(): CandidateDto =
    CandidateDto(
        candidateIdx = index,
        startsAtUtc = startsAt.toString(),
        endsAtUtc = endsAt.toString(),
        durationMin = durationMinutes,
    )

/** One `proposal_revisions` row (contract §A.1). */
@Serializable
data class ProposalRevisionDto(
    @SerialName("revision_no") val revisionNo: Int,
    @SerialName("author_id") val authorId: String,
    @SerialName("origin_tz") val originTz: String,
    val candidates: List<CandidateDto>,
    @SerialName("created_at") val createdAt: String,
)

fun ProposalRevisionDto.toDomain(): ProposalRevision =
    ProposalRevision(
        revisionNo = revisionNo,
        authorId = UserId(authorId),
        originZoneId = originTz,
        candidates = candidates.map { it.toDomain() },
        createdAt = Instant.parse(createdAt),
    )

fun ProposalRevision.toDto(): ProposalRevisionDto =
    ProposalRevisionDto(
        revisionNo = revisionNo,
        authorId = authorId.value,
        originTz = originZoneId,
        candidates = candidates.map { it.toDto() },
        createdAt = createdAt.toString(),
    )

/** One `proposal_responses` row (contract §A.1). `candidate_idx`/`counter_revision` are mutually
 * exclusive with each other and with a plain decline — see [ResponseKind]. */
@Serializable
data class ProposalResponseDto(
    val id: String,
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("revision_no") val revisionNo: Int,
    @SerialName("user_id") val userId: String,
    val response: String,
    @SerialName("candidate_idx") val candidateIdx: Int? = null,
    @SerialName("counter_revision") val counterRevision: Int? = null,
    @SerialName("responded_at") val respondedAt: String,
)

fun ProposalResponseDto.toDomain(): ProposalResponse =
    ProposalResponse(
        id = id,
        proposalId = ProposalId(proposalId),
        revisionNo = revisionNo,
        userId = UserId(userId),
        response = ResponseKind.entries.first { it.wire == response },
        candidateIdx = candidateIdx,
        counterRevision = counterRevision,
        respondedAt = Instant.parse(respondedAt),
    )

/** One `commitments` row — always the caller's own (contract §A.1: peer commitments are never
 * projected to this client). */
@Serializable
data class CommitmentDto(
    val id: String,
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("user_id") val userId: String,
    val state: String,
    @SerialName("created_from_revision") val createdFromRevision: Int,
    @SerialName("candidate_idx") val candidateIdx: Int,
    @SerialName("time_block_id") val timeBlockId: String,
    val version: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("withdrawn_at") val withdrawnAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

fun CommitmentDto.toDomain(): Commitment =
    Commitment(
        id = CommitmentId(id),
        proposalId = ProposalId(proposalId),
        userId = UserId(userId),
        state = CommitmentState.entries.first { it.wire == state },
        createdFromRevision = createdFromRevision,
        candidateIdx = candidateIdx,
        timeBlockId = TimeBlockId(timeBlockId),
        version = version,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
        withdrawnAt = withdrawnAt?.let(Instant::parse),
        completedAt = completedAt?.let(Instant::parse),
    )

/**
 * `time_lock_proposals` row plus its nested revision/response history and the caller's own
 * commitment — `rpc_list_proposals`/`rpc_get_proposal`'s participant-safe projection (contract
 * §A.1-§A.2). Field order mirrors the contract's schema listing.
 */
@Serializable
data class ProposalSummaryDto(
    val id: String,
    @SerialName("pair_id") val pairId: String,
    @SerialName("creator_id") val creatorId: String,
    val title: String,
    val status: String,
    @SerialName("response_deadline") val responseDeadline: String,
    @SerialName("origin_tz") val originTz: String,
    @SerialName("current_revision") val currentRevision: Int,
    @SerialName("accepted_revision") val acceptedRevision: Int? = null,
    @SerialName("accepted_candidate_idx") val acceptedCandidateIdx: Int? = null,
    val version: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    // Transition timestamps + caller's commitment: present in the real RPC output
    // (fixture diff at merge, 2026-09-12) - nullable so absence stays tolerable.
    @SerialName("accepted_at") val acceptedAt: String? = null,
    @SerialName("declined_at") val declinedAt: String? = null,
    @SerialName("expired_at") val expiredAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("my_commitment") val myCommitment: CommitmentDto? = null,
    val revisions: List<ProposalRevisionDto> = emptyList(),
    val responses: List<ProposalResponseDto> = emptyList(),
    val commitment: CommitmentDto? = null,
)

fun ProposalSummaryDto.toDomain(): ProposalSummary =
    ProposalSummary(
        id = ProposalId(id),
        pairId = PairId(pairId),
        creatorId = UserId(creatorId),
        title = title,
        state = ProposalState.entries.first { it.wire == status },
        responseDeadline = Instant.parse(responseDeadline),
        originZoneId = originTz,
        currentRevision = currentRevision,
        acceptedRevision = acceptedRevision,
        acceptedCandidateIdx = acceptedCandidateIdx,
        version = version,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
        revisions = revisions.map { it.toDomain() },
        responses = responses.map { it.toDomain() },
        commitment = commitment?.toDomain(),
    )

/**
 * Uniform result envelope for the four mutating RPCs (`rpc_create_proposal`,
 * `rpc_respond_proposal`, `rpc_cancel_proposal`, `rpc_complete_lock`) — contract §A.2. `action` is
 * the cross-RPC replay guard field, matching [PairRpcEnvelopeDto]'s pattern. On `"conflict"`
 * (stale revision), `proposal` is absent and `current_revision`/`status` carry the live values
 * instead (contract §A.2: "a non-mutating `{"outcome":"conflict","current_revision":n,
 * "status":…}`").
 */
@Serializable
data class ProposalRpcEnvelopeDto(
    val outcome: String,
    val action: String,
    val proposal: ProposalSummaryDto? = null,
    @SerialName("current_revision") val currentRevision: Int? = null,
    val status: String? = null,
)
