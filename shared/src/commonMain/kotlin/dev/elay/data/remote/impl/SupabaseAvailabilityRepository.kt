package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.AvailabilitySourceDto
import dev.elay.data.remote.dto.ConflictHintDto
import dev.elay.data.remote.dto.ExternalBusyRpcEnvelopeDto
import dev.elay.data.remote.dto.toDomain
import dev.elay.data.remote.dto.toDto
import dev.elay.domain.availability.AvailabilityRepository
import dev.elay.domain.availability.AvailabilitySourcesResult
import dev.elay.domain.availability.ExternalBusyResult
import dev.elay.domain.availability.SelfConflictHintsResult
import dev.elay.domain.model.Candidate
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

/**
 * Everything [SupabaseAvailabilityRepository] needs from the network, behind one seam — same
 * reason [dev.elay.data.remote.impl.ProposalTransport] exists: exercised in commonTest against a
 * scripted fake, no [SupabaseClient], no network. Unlike [ProposalTransport], there is no
 * `fetchActive`/`fetchHistory`-style continuously-observed pair here and therefore no refetch
 * loop to drive — the contract forbids new realtime events for this surface ("clients refetch
 * hints on composer/responder open"), so every method here is a plain on-demand suspend call.
 * [SupabaseAvailabilityTransport] is the only production implementation.
 */
internal interface AvailabilityTransport {
    /** `rpc_my_availability_sources()`. UNVERIFIED bare-array assumption — see
     * [AvailabilitySourceDto]'s kdoc; flagged for the lead's merge diff against A6's real RPC. */
    suspend fun fetchMySources(): List<AvailabilitySourceDto>

    /** `rpc_upsert_external_busy(p_operation_id, p_id, p_starts_at_utc, p_ends_at_utc,
     * p_origin_tz, p_label)` — no `p_source_tag` parameter: the server hard-codes `'manual'`
     * (contract: "client-supplied tags forbidden"). `p_label` verified against the shipped
     * migration at the pre-gate round (F23: it was silently dropped before). */
    @Suppress("LongParameterList") // operation identity + interval + zone + optional label (wire shape)
    suspend fun upsertManualBusy(
        operationId: String,
        busyId: String,
        startsAtUtc: String,
        endsAtUtc: String,
        originZoneId: String,
        label: String?,
    ): ExternalBusyRpcEnvelopeDto

    /** `rpc_delete_external_busy(p_operation_id, p_id)`. */
    suspend fun deleteManualBusy(
        operationId: String,
        busyId: String,
    ): ExternalBusyRpcEnvelopeDto

    /** `rpc_self_conflict_hints(p_candidates)`. UNVERIFIED bare-array response and request-shape
     * assumption — see [ConflictHintDto]'s kdoc; the freeze's key set only documents the response
     * shape, not the candidate array's wire field names, so this reuses
     * `rpc_create_proposal`'s `p_candidates` shape (candidate_idx/starts_at_utc/ends_at_utc/
     * duration_min) on the theory that composer/responder call-sites are passing the same
     * [Candidate] values they already hold for proposal creation — flagged for the lead's merge
     * diff. */
    suspend fun selfConflictHints(candidates: List<Candidate>): List<ConflictHintDto>
}

/**
 * ADR-002 thin adapter: the only class where `postgrest` is visible for availability RPCs.
 * Constructed with a client supplied by DI — never builds its own (concierge wiring owns the
 * [SupabaseClient] lifecycle, matching [SupabaseProposalRepository]/[SupabasePairRepository]).
 *
 * Wire parameter names follow the house `p_`-prefix convention already used by
 * [SupabaseProposalRepository]'s and [SupabasePairRepository]'s transports (`p_operation_id`, …)
 * rather than any unprefixed prose form — same flagged-for-merge caveat as that class's kdoc.
 */
private class SupabaseAvailabilityTransport(
    private val client: SupabaseClient,
) : AvailabilityTransport {
    override suspend fun fetchMySources(): List<AvailabilitySourceDto> =
        client.postgrest
            .rpc("rpc_my_availability_sources", buildJsonObject {})
            .decodeAs()

    override suspend fun upsertManualBusy(
        operationId: String,
        busyId: String,
        startsAtUtc: String,
        endsAtUtc: String,
        originZoneId: String,
        label: String?,
    ): ExternalBusyRpcEnvelopeDto =
        client.postgrest
            .rpc(
                "rpc_upsert_external_busy",
                buildJsonObject {
                    put("p_operation_id", operationId)
                    put("p_id", busyId)
                    put("p_starts_at_utc", startsAtUtc)
                    put("p_ends_at_utc", endsAtUtc)
                    put("p_origin_tz", originZoneId)
                    put("p_label", label)
                },
            ).decodeAs()

    override suspend fun deleteManualBusy(
        operationId: String,
        busyId: String,
    ): ExternalBusyRpcEnvelopeDto =
        client.postgrest
            .rpc(
                "rpc_delete_external_busy",
                buildJsonObject {
                    put("p_operation_id", operationId)
                    put("p_id", busyId)
                },
            ).decodeAs()

    override suspend fun selfConflictHints(candidates: List<Candidate>): List<ConflictHintDto> =
        client.postgrest
            .rpc(
                "rpc_self_conflict_hints",
                buildJsonObject {
                    put("p_candidates", candidates.toJsonArray())
                },
            ).decodeAs()
}

private fun List<Candidate>.toJsonArray(): JsonArray =
    buildJsonArray {
        for (candidate in this@toJsonArray) {
            val dto = candidate.toDto()
            add(
                buildJsonObject {
                    put("candidate_idx", dto.candidateIdx)
                    put("starts_at_utc", dto.startsAtUtc)
                    put("ends_at_utc", dto.endsAtUtc)
                    put("duration_min", dto.durationMin)
                },
            )
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

/** Single classifier reused by every result type this repository builds — mirrors the
 * `SupabasePairRepository.kt` file's `toNetworkFailure`/`networkErrorReason` one-classifier-many-
 * call-sites shape rather than duplicating the `when` per sealed type. */
private fun Throwable.toReason(): Pair<String, Boolean> =
    when (this) {
        is RestException -> {
            val retryable =
                statusCode == HTTP_UNAUTHORIZED ||
                    statusCode == HTTP_FORBIDDEN ||
                    statusCode == HTTP_TOO_MANY_REQUESTS ||
                    statusCode >= HTTP_SERVER_ERROR_FLOOR
            "http_$statusCode" to retryable
        }
        is HttpRequestException -> ("network: $message") to true
        else -> (message ?: "unknown_error") to true
    }

private fun Throwable.toFailedSourcesResult(): AvailabilitySourcesResult.Failed {
    val (reason, retryable) = toReason()
    return AvailabilitySourcesResult.Failed(reason, retryable)
}

private fun Throwable.toFailedHintsResult(): SelfConflictHintsResult.Failed {
    val (reason, retryable) = toReason()
    return SelfConflictHintsResult.Failed(reason, retryable)
}

private fun Throwable.toFailedExternalBusyResult(): ExternalBusyResult.Failed {
    val (reason, retryable) = toReason()
    return ExternalBusyResult.Failed(reason, retryable)
}

/** Outcome branch for the two manual-busy mutations (contract: no non-terminal conflict shape
 * described for either RPC — every non-`applied` outcome, including the cross-action 22023 guard,
 * is terminal). Runs INSIDE [runCatchingSuspend]'s block so a malformed envelope degrades to
 * [ExternalBusyResult.Failed] the same way [SupabaseProposalRepository]'s F12 lesson requires,
 * rather than needing a second try/catch layer. */
private fun ExternalBusyRpcEnvelopeDto.toResult(): ExternalBusyResult =
    when (outcome) {
        "applied" -> ExternalBusyResult.Applied
        else -> ExternalBusyResult.Failed("unexpected_outcome:$outcome", retryable = false)
    }

/**
 * [AvailabilityRepository] over [AvailabilityTransport] (contracts/stage4-honest-availability.md;
 * council/stage4-availability-opus.md §A). Remote-only, no memory/cache and no background loop —
 * unlike [SupabaseProposalRepository]/[SupabasePairRepository], this surface is read on-demand
 * (contract: "clients refetch hints on composer/responder open"), so there is nothing to observe
 * continuously and nothing to tear down on sign-out beyond the underlying [SupabaseClient] itself.
 */
class SupabaseAvailabilityRepository internal constructor(
    private val transport: AvailabilityTransport,
) : AvailabilityRepository {
    constructor(client: SupabaseClient) : this(SupabaseAvailabilityTransport(client))

    override suspend fun mySources(): AvailabilitySourcesResult =
        // Mapping (toDomain) runs INSIDE the catch — an unrecognized status/tag string or a
        // malformed timestamp must degrade to Failed, never escape and crash the caller (F12
        // lesson, mirrored from SupabaseProposalRepository.refetch()).
        runCatchingSuspend { transport.fetchMySources().map { it.toDomain() } }
            .fold(
                onSuccess = { AvailabilitySourcesResult.Loaded(it) },
                onFailure = { it.toFailedSourcesResult() },
            )

    override suspend fun upsertManualBusy(
        operationId: String,
        busyId: String,
        startsAt: Instant,
        endsAt: Instant,
        originZoneId: String,
        label: String?,
    ): ExternalBusyResult =
        runCatchingSuspend {
            transport
                .upsertManualBusy(operationId, busyId, startsAt.toString(), endsAt.toString(), originZoneId, label)
                .toResult()
        }.getOrElse { it.toFailedExternalBusyResult() }

    override suspend fun deleteManualBusy(
        operationId: String,
        busyId: String,
    ): ExternalBusyResult =
        runCatchingSuspend { transport.deleteManualBusy(operationId, busyId).toResult() }
            .getOrElse { it.toFailedExternalBusyResult() }

    override suspend fun selfConflictHints(candidates: List<Candidate>): SelfConflictHintsResult =
        // toDomain() (certainty via Certainty.fromWire, never throwing on drift) runs INSIDE the
        // catch — same F12 shape as mySources() above.
        runCatchingSuspend { transport.selfConflictHints(candidates).map { it.toDomain() } }
            .fold(
                onSuccess = { SelfConflictHintsResult.Loaded(it) },
                onFailure = { it.toFailedHintsResult() },
            )
}
