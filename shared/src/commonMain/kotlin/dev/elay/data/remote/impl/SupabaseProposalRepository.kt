package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.CandidateDto
import dev.elay.data.remote.dto.ProposalListEnvelopeDto
import dev.elay.data.remote.dto.ProposalRpcEnvelopeDto
import dev.elay.data.remote.dto.ProposalSummaryDto
import dev.elay.data.remote.dto.RsvpMintEnvelopeDto
import dev.elay.data.remote.dto.toDomain
import dev.elay.data.remote.dto.toDto
import dev.elay.domain.model.CreateProposal
import dev.elay.domain.model.MintRsvpResult
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalResult
import dev.elay.domain.model.ProposalState
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.RespondProposal
import dev.elay.domain.repository.ProposalRepository
import dev.elay.util.ElayLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

/**
 * Everything [SupabaseProposalRepository]'s refetch loop needs from the network, behind one seam
 * — same reason [PairTransport] exists: the loop (fetch active+history -> publish -> wait for the
 * next invalidation -> loop) is exercised in commonTest against a scripted fake, no
 * `SupabaseClient`, no network. [SupabaseRealtimeProposalTransport] is the only production
 * implementation.
 *
 * Deliberately has no `subscribe`/channel method, unlike [PairTransport] — see
 * [SupabaseProposalRepository]'s kdoc for the invalidation-hint seam this repository uses
 * instead.
 */
internal interface ProposalTransport {
    /** `rpc_list_proposals(scope='active')`. Wire shape verified against the live RPC at merge
     * (2026-09-12): `{"items":[…],"next_cursor":…}` — decoded via [ProposalListEnvelopeDto],
     * resolving the bare-array assumption this seat originally flagged. Still passes a `null`
     * cursor, i.e. only the first page is fetched: the frozen client surface has no pagination
     * controls ([ProposalRepository.observeActive] is a plain `Flow<List<ProposalSummary>>`).
     */
    suspend fun fetchActive(): List<ProposalSummaryDto>

    /** `rpc_list_proposals(scope='history')`. Same first-page-only assumption as [fetchActive]. */
    suspend fun fetchHistory(): List<ProposalSummaryDto>

    suspend fun createProposal(command: CreateProposal): ProposalRpcEnvelopeDto

    suspend fun respondProposal(command: RespondProposal): ProposalRpcEnvelopeDto

    suspend fun cancelProposal(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalRpcEnvelopeDto

    suspend fun completeLock(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalRpcEnvelopeDto

    /** `rpc_mint_rsvp_token(p_operation_id, p_proposal_id)`
     * (council/stage3-web-rsvp-security-opus.md §2). Recipient is derived server-side; not
     * passed here. */
    suspend fun mintRsvpToken(
        operationId: String,
        proposalId: ProposalId,
    ): RsvpMintEnvelopeDto
}

/**
 * ADR-002 thin adapter: the only class where `postgrest` is visible for proposal negotiation.
 * Constructed with a client supplied by DI — never builds its own (concierge wiring owns the
 * [SupabaseClient] lifecycle, matching [SupabaseDataGateway]/[SupabasePairRepository]).
 *
 * Wire parameter names follow the house `p_`-prefix convention already used by
 * [SupabaseDataGateway] and [SupabasePairRepository]'s transport (`p_operation_id`,
 * `p_expected_version`, …) rather than the unprefixed names in council/stage2-timelock-sol.md
 * §A.2's prose signatures — that document describes RPCs conceptually, not their literal
 * Postgres parameter names. Flagged for the lead/A3 diff at merge alongside the fixture
 * reconciliation (lead amendment 2).
 */
private class SupabaseRealtimeProposalTransport(
    private val client: SupabaseClient,
) : ProposalTransport {
    override suspend fun fetchActive(): List<ProposalSummaryDto> = listProposals(scope = "active")

    override suspend fun fetchHistory(): List<ProposalSummaryDto> = listProposals(scope = "history")

    private suspend fun listProposals(scope: String): List<ProposalSummaryDto> =
        client.postgrest
            .rpc(
                "rpc_list_proposals",
                buildJsonObject {
                    put("p_scope", scope)
                    put("p_cursor", JsonNull)
                },
            ).decodeAs<ProposalListEnvelopeDto>()
            .items

    override suspend fun createProposal(command: CreateProposal): ProposalRpcEnvelopeDto =
        callProposalRpc(
            "rpc_create_proposal",
            buildJsonObject {
                put("p_operation_id", command.operationId)
                put("p_title", command.title)
                put("p_origin_tz", command.originZoneId)
                put("p_response_deadline", command.responseDeadline.toString())
                put("p_candidates", command.candidates.map { it.toDto() }.toJsonArray())
            },
        )

    override suspend fun respondProposal(command: RespondProposal): ProposalRpcEnvelopeDto =
        callProposalRpc("rpc_respond_proposal", command.toParams())

    override suspend fun cancelProposal(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalRpcEnvelopeDto = callProposalRpc("rpc_cancel_proposal", idParams(operationId, proposalId))

    override suspend fun completeLock(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalRpcEnvelopeDto = callProposalRpc("rpc_complete_lock", idParams(operationId, proposalId))

    override suspend fun mintRsvpToken(
        operationId: String,
        proposalId: ProposalId,
    ): RsvpMintEnvelopeDto =
        client.postgrest
            .rpc("rpc_mint_rsvp_token", idParams(operationId, proposalId))
            .decodeAs()

    private suspend fun callProposalRpc(
        function: String,
        params: JsonObject,
    ): ProposalRpcEnvelopeDto = client.postgrest.rpc(function, params).decodeAs()
}

private fun idParams(
    operationId: String,
    proposalId: ProposalId,
) = buildJsonObject {
    put("p_operation_id", operationId)
    put("p_proposal_id", proposalId.value)
}

private fun List<CandidateDto>.toJsonArray(): JsonArray =
    buildJsonArray {
        for (candidate in this@toJsonArray) {
            add(
                buildJsonObject {
                    put("candidate_idx", candidate.candidateIdx)
                    put("starts_at_utc", candidate.startsAtUtc)
                    put("ends_at_utc", candidate.endsAtUtc)
                    put("duration_min", candidate.durationMin)
                },
            )
        }
    }

private fun RespondProposal.toParams(): JsonObject =
    buildJsonObject {
        put("p_operation_id", operationId)
        put("p_proposal_id", proposalId.value)
        put("p_expected_revision", expectedRevision)
        when (this@toParams) {
            is RespondProposal.Accept -> {
                put("p_response", "accept")
                put("p_candidate_idx", candidateIdx)
            }
            is RespondProposal.Decline -> put("p_response", "decline")
            is RespondProposal.Counter -> {
                put("p_response", "counter")
                put("p_new_origin_tz", originZoneId)
                put("p_new_deadline", responseDeadline.toString())
                put("p_new_candidates", candidates.map { it.toDto() }.toJsonArray())
            }
        }
    }

@Suppress("TooGenericExceptionCaught") // this adapter must never let a network failure escape uncaught.
private suspend fun <T> runCatchingSuspend(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

private fun Throwable.toFailedResult(): ProposalResult.Failed =
    when (this) {
        is RestException -> {
            val retryable =
                statusCode == HTTP_UNAUTHORIZED ||
                    statusCode == HTTP_FORBIDDEN ||
                    statusCode == HTTP_TOO_MANY_REQUESTS ||
                    statusCode >= HTTP_SERVER_ERROR_FLOOR
            ProposalResult.Failed("http_$statusCode", retryable)
        }
        is HttpRequestException -> ProposalResult.Failed("network: $message", retryable = true)
        else -> ProposalResult.Failed(message ?: "unknown_error", retryable = true)
    }

/** [MintRsvpResult] sibling of [Throwable.toFailedResult] — no shared classifier exists across the
 * house's repository impls (each owns its own; see [SupabasePairRepository.toNetworkFailure] and
 * [SupabaseDataGateway]'s inline classification for the same duplication pattern). */
private fun Throwable.toFailedMintResult(): MintRsvpResult.Failed =
    when (this) {
        is RestException -> {
            val retryable =
                statusCode == HTTP_UNAUTHORIZED ||
                    statusCode == HTTP_FORBIDDEN ||
                    statusCode == HTTP_TOO_MANY_REQUESTS ||
                    statusCode >= HTTP_SERVER_ERROR_FLOOR
            MintRsvpResult.Failed("http_$statusCode", retryable)
        }
        is HttpRequestException -> MintRsvpResult.Failed("network: $message", retryable = true)
        else -> MintRsvpResult.Failed(message ?: "unknown_error", retryable = true)
    }

/** Outcome branch for `rpc_mint_rsvp_token` (contract §2: no conflict shape — every non-`applied`
 * outcome is terminal). [RsvpMintEnvelopeDto.toDomain] runs INSIDE this function so a missing
 * applied field surfaces via the same catch as the envelope decode itself (F12 lesson) rather
 * than needing a second try/catch layer. */
private fun RsvpMintEnvelopeDto.toMintResult(): MintRsvpResult =
    when (outcome) {
        "applied" -> MintRsvpResult.Applied(toDomain())
        else -> MintRsvpResult.Failed("unexpected_outcome:$outcome", retryable = false)
    }

private fun ProposalRpcEnvelopeDto.toResult(): ProposalResult =
    when (outcome) {
        "applied" -> {
            val dto = proposal
            if (dto == null) {
                ProposalResult.Failed("malformed_applied_response", retryable = false)
            } else {
                ProposalResult.Applied(dto.toDomain())
            }
        }
        "conflict" -> {
            val revision = currentRevision
            val wireStatus = status
            val proposalState = wireStatus?.let { w -> ProposalState.entries.firstOrNull { it.wire == w } }
            when {
                revision != null && proposalState != null -> ProposalResult.Conflict(revision, proposalState)
                // rpc_complete_lock's conflict shape carries only commitment_state — there is no
                // revision to retry against, so it is a terminal domain failure, not a Conflict.
                commitmentState != null ->
                    ProposalResult.Failed("commitment_state:$commitmentState", retryable = false)
                else -> ProposalResult.Failed("malformed_conflict_response", retryable = false)
            }
        }
        else -> ProposalResult.Failed("unexpected_outcome:$outcome", retryable = false)
    }

/**
 * [ProposalRepository] over [ProposalTransport] (contracts/stage2-timelock.md;
 * council/stage2-timelock-sol.md §A.1-§A.4). Remote+memory, same as [SupabasePairRepository] — no
 * Room cache.
 *
 * ## The invalidation seam
 *
 * Stage 1 already reserves the pair's private Broadcast topic and [SupabasePairRepository] alone
 * owns opening/resubscribing/tearing down that one channel (ADR-009: never leave a private
 * subscription authorized past its owning session, and never open a second channel onto the same
 * topic — Realtime private channels are per-connection state, not a pub/sub bus you fan out
 * against for free). Stage 2 reserves three more event names on that *same* topic
 * (`pair.proposal_created.v1`, `pair.proposal_updated.v1`, `pair.commitment_changed.v1` —
 * council/stage2-timelock-sol.md §3), but this repository must not open its own channel to hear
 * them.
 *
 * So [SupabaseProposalRepository] takes the hint as a plain `Flow<Unit>` constructor parameter —
 * [invalidationHints] — instead of any realtime type. It never inspects *which* event fired
 * (contract §3: "Events are invalidation hints; clients refetch authoritative projections" —
 * there is nothing in the payload this repository would act on beyond "go refetch"). Every pulse
 * triggers the same refetch as a just-applied local mutation.
 *
 * Wiring this in production is the **lead's** job (contracts/stage2-timelock.md's seat grants:
 * lead owns DI/user-scope lifetime), because only [SupabasePairRepository] holds the live
 * `RealtimeChannel`. The intended shape: extend [PairChannelHandle] (or add a sibling accessor
 * alongside it) with a `proposalInvalidations: Flow<Unit>` that merges
 * `channel.broadcastFlow("pair.proposal_created.v1")`,
 * `channel.broadcastFlow("pair.proposal_updated.v1")`, and
 * `channel.broadcastFlow("pair.commitment_changed.v1")`, each mapped to `Unit`. Expose *that* flow
 * from [SupabasePairRepository] (surviving resubscribes/generation changes the same way
 * [observePair] does) and pass it as this constructor's [invalidationHints] at DI time. Until
 * that plumbing lands, an empty/never-emitting flow is a safe default: [SupabaseProposalRepository]
 * still refreshes on every successful local mutation and on construction, it just won't pick up
 * the *other* member's changes until the next local mutation or process restart.
 *
 * Stage 3's [mintRsvpToken] (contracts/stage3-web-rsvp.md item 4) is the 11th member on the
 * frozen [ProposalRepository] surface this class implements — one method per RPC, not
 * decomposable further, hence the [Suppress] below.
 */
@Suppress("TooManyFunctions")
class SupabaseProposalRepository internal constructor(
    private val scope: CoroutineScope,
    private val transport: ProposalTransport,
    invalidationHints: Flow<Unit>,
) : ProposalRepository {
    constructor(
        client: SupabaseClient,
        scope: CoroutineScope,
        invalidationHints: Flow<Unit>,
    ) : this(scope, SupabaseRealtimeProposalTransport(client), invalidationHints)

    private val mutableActive = MutableStateFlow<List<ProposalSummary>>(emptyList())
    private val mutableHistory = MutableStateFlow<List<ProposalSummary>>(emptyList())

    /** CONFLATED channel, not a SharedFlow: `MutableSharedFlow(replay = 0)` DISCARDS emissions
     * while nobody is suspended collecting (pre-gate finding F10, 2026-09-12) — a kick landing
     * mid-refetch was silently lost. The conflated channel retains the latest pulse until
     * [refetchLoop] receives it (mirrors [SupabasePairRepository]'s kick). */
    private val kick = Channel<Unit>(Channel.CONFLATED)

    private val refetchJob: Job = scope.launch { refetchLoop(invalidationHints) }

    override fun observeActive(): Flow<List<ProposalSummary>> = mutableActive.asStateFlow()

    override fun observeHistory(): Flow<List<ProposalSummary>> = mutableHistory.asStateFlow()

    override suspend fun create(command: CreateProposal): ProposalResult = mutate { transport.createProposal(command) }

    override suspend fun respond(command: RespondProposal): ProposalResult =
        mutate { transport.respondProposal(command) }

    override suspend fun cancel(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalResult = mutate { transport.cancelProposal(operationId, proposalId) }

    override suspend fun complete(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalResult = mutate { transport.completeLock(operationId, proposalId) }

    /** `rpc_mint_rsvp_token` (contract §2). Deliberately does NOT go through [mutate]: minting
     * changes no proposal row, so there is nothing for [observeActive]/[observeHistory] to
     * reflect and no refetch to kick — unlike every other mutating method on this class. Mapping
     * ([RsvpMintEnvelopeDto.toMintResult]) still runs INSIDE the [runCatchingSuspend] catch (the
     * F12 lesson: a malformed applied envelope must degrade to [MintRsvpResult.Failed], never
     * escape and crash the app). */
    override suspend fun mintRsvpToken(
        operationId: String,
        proposalId: ProposalId,
    ): MintRsvpResult =
        runCatchingSuspend { transport.mintRsvpToken(operationId, proposalId).toMintResult() }
            .getOrElse { it.toFailedMintResult() }

    /** Cancels the background refetch loop. There is no channel to tear down here — see the
     * invalidation-seam kdoc above; whatever owns [invalidationHints]'s upstream (the pair
     * channel) is torn down by [SupabasePairRepository.close] independently. */
    override fun close() {
        refetchJob.cancel()
    }

    /** Runs one RPC via [block], classifies its envelope into a [ProposalResult], and — only on
     * [ProposalResult.Applied] — kicks a refetch so [observeActive]/[observeHistory] reflect the
     * change without waiting for an invalidation hint to arrive back over Realtime. */
    private suspend fun mutate(block: suspend () -> ProposalRpcEnvelopeDto): ProposalResult {
        // toResult() (which maps DTO -> domain) runs INSIDE the catch: an unknown wire enum or
        // a timestamp format change must degrade to Failed, never escape and crash the app
        // (pre-gate finding F12, 2026-09-12).
        val result =
            runCatchingSuspend { block().toResult() }.getOrElse { it.toFailedResult() }
        if (result is ProposalResult.Applied) kick.trySend(Unit)
        return result
    }

    /** No `break`/`continue`: a fetch failure simply leaves the previous, possibly-stale, value
     * in place (there is no error slot in the frozen `Flow<List<ProposalSummary>>` surface to
     * publish one into) and this still waits for the next signal before retrying — the same
     * "refetch, then wait" shape as [SupabasePairRepository]'s resync loop, minus the
     * subscribe/resubscribe step this repository doesn't own.
     *
     * The hint collector is armed BEFORE the first refetch and forwards into the conflated
     * [kick] channel, so an invalidation landing mid-refetch is retained (F10). */
    private suspend fun refetchLoop(invalidationHints: Flow<Unit>): Unit =
        coroutineScope {
            val forward = launch { invalidationHints.collect { kick.trySend(Unit) } }
            try {
                while (currentCoroutineContext().isActive) {
                    refetch()
                    kick.receive()
                }
            } finally {
                forward.cancel()
            }
        }

    private suspend fun refetch() {
        // Mapping runs inside the catch — see [mutate]'s F12 note.
        runCatchingSuspend { transport.fetchActive().map { it.toDomain() } }
            .onFailure { error ->
                ElayLog.w("Proposal") { "ELAY proposal refetch failure (active): ${error::class.simpleName}" }
            }.getOrNull()
            ?.let { mutableActive.value = it }
        runCatchingSuspend { transport.fetchHistory().map { it.toDomain() } }
            .onFailure { error ->
                ElayLog.w("Proposal") { "ELAY proposal refetch failure (history): ${error::class.simpleName}" }
            }.getOrNull()
            ?.let { mutableHistory.value = it }
    }
}
