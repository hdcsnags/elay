package dev.elay.domain.model

import kotlinx.datetime.Instant
import kotlin.jvm.JvmInline

/**
 * Frozen Stage 2 time-lock domain shapes (contracts/stage2-timelock.md; the incorporated
 * council/stage2-timelock-sol.md §4 "Frozen Kotlin surface"). Consumed by
 * [dev.elay.domain.repository.ProposalRepository]. Reuses [UserId]/[PairId]/[TimeBlockId] from
 * [PlannerModels]/[PairModels] rather than inventing parallel identity types.
 *
 * Time truth mirrors [TimeBlock] (ADR-006): every instant here is a UTC [Instant]; zone ids are
 * plain IANA strings kept alongside it (`originZoneId` below), never a formatted string or a
 * fixed offset. Rendering (Gemini's §B dual-time rule) combines an [Instant] with the origin zone
 * id carried here, the viewer's zone id, and the other pair member's zone id (e.g. from
 * [PairMember.homeTz]) entirely in the UI layer — this model layer never formats.
 */

@JvmInline value class ProposalId(
    val value: String,
)

@JvmInline value class CommitmentId(
    val value: String,
)

/**
 * All eight server strings (ELAY-SPEC.md "Time-lock state machine";
 * council/stage2-timelock-sol.md §4: "matching all eight server strings"). `Draft` is retained
 * because the schema names it, though no Stage-2 RPC creates one client-side —
 * `rpc_create_proposal` sends straight to `Proposed` (contract §A.1).
 */
enum class ProposalState(
    val wire: String,
) {
    Draft("draft"),
    Proposed("proposed"),
    Accepted("accepted"),
    Countered("countered"),
    Declined("declined"),
    Expired("expired"),
    Cancelled("cancelled"),
    Completed("completed"),
}

/** `proposal_responses.response` (contract §A.1). */
enum class ResponseKind(
    val wire: String,
) {
    Accept("accept"),
    Decline("decline"),
    Counter("counter"),
}

/** `commitments.state` (ADR-010 §1). */
enum class CommitmentState(
    val wire: String,
) {
    Active("active"),
    Withdrawn("withdrawn"),
    Completed("completed"),
}

/**
 * One candidate instant window within a [ProposalRevision]. `index` is `candidate_idx` —
 * contiguous zero-based within its revision, 1-3 total (contract §A.1: "an immutable validation
 * function enforces contiguous zero-based indexes"). `durationMinutes` is carried as sent by the
 * server; the server (not this client) asserts its equality with the instant interval, since
 * DST-adjusted candidates (ADR-006) can make that equality subtler than plain subtraction.
 */
data class Candidate(
    val index: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    val durationMinutes: Int,
)

/** One immutable, append-only revision (contract §A.1: UPDATE/DELETE-rejecting trigger). */
data class ProposalRevision(
    val revisionNo: Int,
    val authorId: UserId,
    val originZoneId: String,
    val candidates: List<Candidate>,
    val createdAt: Instant,
)

/** One member's response to a specific revision — unique per (proposal, revision, user). */
data class ProposalResponse(
    val id: String,
    val proposalId: ProposalId,
    val revisionNo: Int,
    val userId: UserId,
    val response: ResponseKind,
    val candidateIdx: Int?,
    val counterRevision: Int?,
    val respondedAt: Instant,
)

/**
 * The caller's own stake in an accepted proposal (ADR-010 §1/§2: "commitments are
 * per-participant"). The other pair member's commitment and private `time_blocks` row are never
 * projected to this client (contract §A.1: "Commitments and time_blocks remain owner-readable
 * only").
 */
data class Commitment(
    val id: CommitmentId,
    val proposalId: ProposalId,
    val userId: UserId,
    val state: CommitmentState,
    val createdFromRevision: Int,
    val candidateIdx: Int,
    val timeBlockId: TimeBlockId,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val withdrawnAt: Instant?,
    val completedAt: Instant?,
)

/**
 * `rpc_list_proposals`/`rpc_get_proposal` projection (contract §A.2): a participant-safe
 * active/history snapshot carrying its full revision + response history and the caller's own
 * commitment (`null` until accepted, or if the other member holds the commitment for a
 * declined/expired/cancelled proposal — there is none to hold in that case either).
 */
data class ProposalSummary(
    val id: ProposalId,
    val pairId: PairId,
    val creatorId: UserId,
    val title: String,
    val state: ProposalState,
    val responseDeadline: Instant,
    val originZoneId: String,
    val currentRevision: Int,
    val acceptedRevision: Int?,
    val acceptedCandidateIdx: Int?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revisions: List<ProposalRevision>,
    val responses: List<ProposalResponse>,
    val commitment: Commitment?,
)

private const val MIN_CANDIDATES = 1
private const val MAX_CANDIDATES = 3

/** Client-side mirror of the server's structural candidate-set invariants (contract §A.1: 1-3
 * candidates, contiguous zero-based indexes) — fails fast locally rather than round-tripping an
 * obviously malformed command to the RPC. Does not duplicate the server's duration-equality or
 * DST-transition checks (ADR-006); those stay server-owned. */
private fun List<Candidate>.requireValidCandidateSet() {
    require(size in MIN_CANDIDATES..MAX_CANDIDATES) { "expected 1..3 candidates, got $size" }
    forEachIndexed { position, candidate ->
        require(candidate.index == position) {
            "candidate_idx must be contiguous zero-based; expected $position, got ${candidate.index}"
        }
        require(candidate.endsAt > candidate.startsAt) { "candidate ${candidate.index}: end must be after start" }
    }
}

/**
 * `rpc_create_proposal` command (contract §A.2). [candidates] is the ordered 1-3 array; see
 * [requireValidCandidateSet].
 */
data class CreateProposal(
    val operationId: String,
    val title: String,
    val originZoneId: String,
    val responseDeadline: Instant,
    val candidates: List<Candidate>,
) {
    init {
        candidates.requireValidCandidateSet()
    }
}

/**
 * `rpc_respond_proposal` command (contract §A.2). One subtype per the RPC's three response
 * shapes — [Accept] only carries `candidate_idx`, [Counter] only carries the new revision's
 * fields — so an invalid combination (e.g. a decline with a candidate index) is unrepresentable
 * rather than merely unvalidated.
 */
sealed interface RespondProposal {
    val operationId: String
    val proposalId: ProposalId

    /** The revision this response answers — stale-revision replay surfaces as
     * [ProposalResult.Conflict] rather than mutating (contract §A.2). */
    val expectedRevision: Int

    data class Accept(
        override val operationId: String,
        override val proposalId: ProposalId,
        override val expectedRevision: Int,
        val candidateIdx: Int,
    ) : RespondProposal

    data class Decline(
        override val operationId: String,
        override val proposalId: ProposalId,
        override val expectedRevision: Int,
    ) : RespondProposal

    data class Counter(
        override val operationId: String,
        override val proposalId: ProposalId,
        override val expectedRevision: Int,
        val originZoneId: String,
        val responseDeadline: Instant,
        val candidates: List<Candidate>,
    ) : RespondProposal {
        init {
            candidates.requireValidCandidateSet()
        }
    }
}

/**
 * Result of the four mutating RPCs (contract §A.2/§4). Mirrors
 * [dev.elay.data.remote.MutationResult]'s applied/conflict/failed shape: the wire envelope only
 * ever carries those two named outcomes (`applied`, `conflict`) plus a raised exception for
 * authorization/validation failures, folded into [Failed] the same way
 * [dev.elay.data.remote.impl.SupabaseDataGateway] classifies `RestException`/`HttpRequestException`.
 */
sealed interface ProposalResult {
    data class Applied(
        val proposal: ProposalSummary,
    ) : ProposalResult

    /** Stale revision (contract §A.2: "a non-mutating `{"outcome":"conflict",
     * "current_revision":n,"status":…}` rather than raising"). [currentRevision] lets the caller
     * refetch and re-render the live revision instead of retrying blind. */
    data class Conflict(
        val currentRevision: Int,
        val status: ProposalState,
    ) : ProposalResult

    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : ProposalResult
}
