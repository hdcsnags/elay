package dev.elay.domain.model

import kotlinx.datetime.Instant
import kotlin.jvm.JvmInline

/**
 * Stage 1 pairing domain shapes (contracts/stage1-pairing.md; the incorporated
 * council/stage1-pairing-contract-sol.md §4 "Frozen client surface"). Consumed by
 * [dev.elay.domain.repository.PairRepository]; reuses [UserId] from [PlannerModels] rather than
 * inventing a parallel identity type.
 */

@JvmInline value class PairId(
    val value: String,
)

enum class PairStatus(
    val wire: String,
) {
    Active("active"),
    Ended("ended"),
}

data class PairMember(
    val userId: UserId,
    val displayName: String,
    val homeTz: String,
    val joinedAt: Instant,
)

/** Mirrors `PairSnapshotDto` exactly (contract §4) — no invite code, only its expiry. */
data class PairSnapshot(
    val id: PairId,
    val status: PairStatus,
    val version: Long,
    val channelTopic: String,
    val members: List<PairMember>,
    val activeInviteExpiresAt: Instant?,
)

/**
 * Opaque domain-level RPC outcomes. `invalid_or_unavailable` covers every one of
 * `rpc_redeem_pair_invite`'s rejection paths (expired, revoked, wrong code, self-redeem,
 * rate-limited, already paired, etc) by contractual design — ADR-defended against a client
 * distinguishing them (that would leak which failure mode applied, i.e. an oracle for guessing
 * codes). Never pattern-match past [InvalidOrUnavailable] to recover a finer reason.
 */
sealed interface PairError {
    data object InvalidOrUnavailable : PairError

    /** `rpc_leave_pair` replay-when-already-absent outcome. */
    data object NotMember : PairError

    /** Forward-compatible catch-all for any RPC outcome string with no named case yet
     * (e.g. one of Stage 2's reserved event/outcome names surfacing early). */
    data class Unrecognized(
        val outcome: String,
    ) : PairError
}

/** The two ways the background resync loop can stall — folded into [PairState.Failed]. */
sealed interface PairFailure {
    data class Domain(
        val error: PairError,
    ) : PairFailure

    data class Network(
        val reason: String,
        val retryable: Boolean,
    ) : PairFailure
}

/** Frozen client surface (contract §4): `Loading | Unpaired | Inviting | Paired | Failed`. */
sealed interface PairState {
    data object Loading : PairState

    data object Unpaired : PairState

    /**
     * [code] is only known for the lifetime of the session that created (or replayed the
     * creation of) the invite — the server persists just its SHA-256 hash, never the plaintext,
     * so a cold `rpc_get_pair` fetch that finds a live invite cannot recover it. `null` here means
     * "there is a live invite, but this process doesn't hold its code" (e.g. after a process
     * restart) — Together renders that as a waiting state without a shareable code.
     */
    data class Inviting(
        val snapshot: PairSnapshot,
        val code: String?,
        val expiresAt: Instant,
    ) : PairState

    data class Paired(
        val snapshot: PairSnapshot,
    ) : PairState

    data class Failed(
        val failure: PairFailure,
    ) : PairState
}

/** `rpc_create_pair_invite` result (contract §4: "sealed applied/domain-error/network-error"). */
sealed interface InviteResult {
    data class Applied(
        val snapshot: PairSnapshot,
        val code: String,
        val expiresAt: Instant,
    ) : InviteResult

    data class DomainError(
        val error: PairError,
    ) : InviteResult

    data class NetworkError(
        val reason: String,
        val retryable: Boolean,
    ) : InviteResult
}

/** `rpc_redeem_pair_invite` result. */
sealed interface RedeemResult {
    data class Applied(
        val snapshot: PairSnapshot,
    ) : RedeemResult

    data class DomainError(
        val error: PairError,
    ) : RedeemResult

    data class NetworkError(
        val reason: String,
        val retryable: Boolean,
    ) : RedeemResult
}

/** `rpc_leave_pair` result. */
sealed interface LeaveResult {
    data class Applied(
        val leftPairId: PairId,
    ) : LeaveResult

    data class DomainError(
        val error: PairError,
    ) : LeaveResult

    data class NetworkError(
        val reason: String,
        val retryable: Boolean,
    ) : LeaveResult
}
