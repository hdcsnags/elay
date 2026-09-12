package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.NextTimeSuggestionDto
import dev.elay.data.remote.dto.SessionOutcomeRpcEnvelopeDto
import dev.elay.data.remote.dto.toDomain
import dev.elay.domain.model.NextTimeSuggestionResult
import dev.elay.domain.model.RecordOutcomeResult
import dev.elay.domain.model.SessionOutcomeKind
import dev.elay.domain.repository.OutcomeRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

/**
 * Everything [SupabaseOutcomeRepository] needs from the network, behind one seam — same reason
 * [AvailabilityTransport] exists: exercised in commonTest against a scripted fake, no
 * [SupabaseClient], no network. Like [AvailabilityTransport] and unlike [ProposalTransport], there
 * is no continuously-observed refetch loop here (contract: "NO new realtime events" per the lead's
 * scope ruling) — every method is a plain on-demand suspend call. [SupabaseOutcomeTransport] is
 * the only production implementation.
 */
internal interface OutcomeTransport {
    /** `rpc_record_session_outcome(p_operation_id, p_time_block_id, p_outcome,
     * p_actual_minutes)`. [outcome] is the already-resolved wire string
     * ([SessionOutcomeKind.wire]) — this seam stays DTO/wire-typed throughout, matching
     * [AvailabilityTransport.upsertManualBusy]'s plain-`String` parameters. */
    suspend fun recordOutcome(
        operationId: String,
        timeBlockId: String,
        outcome: String,
        actualMinutes: Int?,
    ): SessionOutcomeRpcEnvelopeDto

    /** `rpc_next_time_suggestion(p_task_id, p_title_key)`. */
    suspend fun nextTimeSuggestion(
        taskId: String?,
        titleKey: String?,
    ): NextTimeSuggestionDto
}

/**
 * ADR-002 thin adapter: the only class where `postgrest` is visible for outcome/next-time RPCs.
 * Constructed with a client supplied by DI — never builds its own (concierge wiring owns the
 * [SupabaseClient] lifecycle, matching [SupabaseAvailabilityRepository]'s transport).
 *
 * Wire parameter names follow the house `p_`-prefix convention the contract itself names
 * explicitly for this stage (`p_operation_id`, `p_time_block_id`, `p_outcome`,
 * `p_actual_minutes`, `p_task_id`, `p_title_key`) — unlike Stage 2/4's transports, this one is NOT
 * flagged UNVERIFIED for the parameter-name prefix itself, since contracts/stage5-retention-
 * hardening.md spells the `p_`-prefixed names out verbatim.
 */
private class SupabaseOutcomeTransport(
    private val client: SupabaseClient,
) : OutcomeTransport {
    override suspend fun recordOutcome(
        operationId: String,
        timeBlockId: String,
        outcome: String,
        actualMinutes: Int?,
    ): SessionOutcomeRpcEnvelopeDto =
        client.postgrest
            .rpc(
                "rpc_record_session_outcome",
                buildJsonObject {
                    put("p_operation_id", operationId)
                    put("p_time_block_id", timeBlockId)
                    put("p_outcome", outcome)
                    put("p_actual_minutes", actualMinutes)
                },
            ).decodeAs()

    override suspend fun nextTimeSuggestion(
        taskId: String?,
        titleKey: String?,
    ): NextTimeSuggestionDto =
        client.postgrest
            .rpc(
                "rpc_next_time_suggestion",
                buildJsonObject {
                    put("p_task_id", taskId)
                    put("p_title_key", titleKey)
                },
            ).decodeAs()
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

/** Single classifier reused by every result type this repository builds — mirrors
 * [SupabaseAvailabilityRepository]'s `toReason`/one-classifier-many-call-sites shape rather than
 * duplicating the `when` per sealed type. No classifier is shared *across* repository impl files
 * (house convention: each owns its own copy — see that file's kdoc). */
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

private fun Throwable.toFailedRecordResult(): RecordOutcomeResult.Failed {
    val (reason, retryable) = toReason()
    return RecordOutcomeResult.Failed(reason, retryable)
}

private fun Throwable.toFailedSuggestionResult(): NextTimeSuggestionResult.Failed {
    val (reason, retryable) = toReason()
    return NextTimeSuggestionResult.Failed(reason, retryable)
}

/**
 * Outcome branch for `rpc_record_session_outcome` (contract: elapsed-only future-block guard +
 * cross-action guard, both terminal per [RecordOutcomeResult]'s kdoc — no `Conflict` variant).
 * Runs INSIDE [runCatchingSuspend]'s block so a malformed envelope OR an unrecognized
 * `session_outcome.outcome` wire string (via [dev.elay.data.remote.dto.toDomain]'s
 * `requireNotNull`) degrades to [RecordOutcomeResult.Failed] the same way
 * [SupabaseProposalRepository]'s F12 lesson requires, rather than needing a second try/catch
 * layer.
 */
private fun SessionOutcomeRpcEnvelopeDto.toResult(): RecordOutcomeResult =
    when (outcome) {
        "applied" -> {
            val dto = sessionOutcome
            if (dto == null) {
                RecordOutcomeResult.Failed("malformed_applied_response", retryable = false)
            } else {
                RecordOutcomeResult.Applied(dto.toDomain())
            }
        }
        else -> RecordOutcomeResult.Failed("unexpected_outcome:$outcome", retryable = false)
    }

/**
 * [OutcomeRepository] over [OutcomeTransport] (contracts/stage5-retention-hardening.md;
 * council/stage5-hardening-opus.md §A). Remote-only, no memory/cache and no background loop — like
 * [SupabaseAvailabilityRepository], this surface is read/written on-demand (contract: "NO new
 * realtime events"), so there is nothing to observe continuously and nothing to tear down on
 * sign-out beyond the underlying [SupabaseClient] itself.
 */
class SupabaseOutcomeRepository internal constructor(
    private val transport: OutcomeTransport,
) : OutcomeRepository {
    constructor(client: SupabaseClient) : this(SupabaseOutcomeTransport(client))

    override suspend fun recordOutcome(
        operationId: String,
        timeBlockId: String,
        outcome: SessionOutcomeKind,
        actualMinutes: Int?,
    ): RecordOutcomeResult =
        // toResult() (which maps the nested DTO -> domain) runs INSIDE the catch — an unknown
        // outcome wire string or a malformed envelope must degrade to Failed, never escape and
        // crash the caller (F12 lesson, mirrored from SupabaseProposalRepository.mutate()).
        runCatchingSuspend { transport.recordOutcome(operationId, timeBlockId, outcome.wire, actualMinutes).toResult() }
            .getOrElse { it.toFailedRecordResult() }

    override suspend fun nextTimeSuggestion(
        taskId: String?,
        titleKey: String?,
    ): NextTimeSuggestionResult =
        runCatchingSuspend { transport.nextTimeSuggestion(taskId, titleKey).toDomain() }
            .fold(
                onSuccess = { NextTimeSuggestionResult.Loaded(it) },
                onFailure = { it.toFailedSuggestionResult() },
            )
}
