package dev.elay.data.remote.impl

import dev.elay.data.remote.DataGateway
import dev.elay.data.remote.MutationResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Sentinel [MutationResult.Failed.reason] the sync engine (`sync/impl`) treats as an
 * auth-pause trigger — a session refresh, not a terminal failure.
 */
internal const val AUTH_REQUIRED_REASON = "auth_required"

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

/**
 * ADR-002 thin adapter: the only class where [SupabaseClient]/Postgrest types are visible.
 * Constructed with a client supplied by DI — never builds its own (concierge wiring owns
 * the [SupabaseClient] lifecycle).
 *
 * Every RPC follows contracts/phase1-planner.md §2/§4: `p_operation_id`/`p_expected_version`/
 * `p_row`, response `{"outcome":"applied","row":...}` or `{"outcome":"conflict","current":...}`.
 * Never throws — network/HTTP failures are folded into [MutationResult.Failed] (see
 * [callRpc]).
 */
class SupabaseDataGateway(
    private val client: SupabaseClient,
) : DataGateway {
    override suspend fun upsertGoal(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = callRpc(client, "rpc_upsert_goal", operationId, expectedVersion, row)

    override suspend fun upsertMilestone(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = callRpc(client, "rpc_upsert_milestone", operationId, expectedVersion, row)

    override suspend fun upsertTask(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = callRpc(client, "rpc_upsert_task", operationId, expectedVersion, row)

    override suspend fun upsertCapture(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = callRpc(client, "rpc_upsert_capture", operationId, expectedVersion, row)

    override suspend fun upsertTimeBlock(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = callRpc(client, "rpc_upsert_time_block", operationId, expectedVersion, row)

    override suspend fun deleteGoal(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = callRpc(client, "rpc_delete_goal", operationId, expectedVersion, idRow(id))

    override suspend fun deleteTask(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = callRpc(client, "rpc_delete_task", operationId, expectedVersion, idRow(id))

    override suspend fun deleteCapture(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = callRpc(client, "rpc_delete_capture", operationId, expectedVersion, idRow(id))

    override suspend fun deleteTimeBlock(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = callRpc(client, "rpc_delete_time_block", operationId, expectedVersion, idRow(id))

    override suspend fun fetchSince(
        aggregate: String,
        sinceVersion: Long,
    ): Result<List<JsonObject>> =
        runCatching {
            client.postgrest
                .from("${aggregate}s")
                .select { filter { gt("version", sinceVersion) } }
                .decodeList()
        }
}

private fun idRow(id: String): JsonObject = buildJsonObject { put("id", id) }

@Suppress("TooGenericExceptionCaught") // ADR-002/contract §2: this adapter must never throw.
private suspend fun callRpc(
    client: SupabaseClient,
    function: String,
    operationId: String,
    expectedVersion: Long,
    row: JsonObject,
): MutationResult =
    try {
        val params =
            buildJsonObject {
                put("p_operation_id", operationId)
                put("p_expected_version", expectedVersion)
                put("p_row", row)
            }
        val result = client.postgrest.rpc(function, params)
        parseOutcome(result.decodeAs())
    } catch (e: HttpRequestException) {
        MutationResult.Failed(reason = "network: ${e.message}", retryable = true)
    } catch (e: RestException) {
        classifyRestException(e)
    } catch (e: Exception) {
        MutationResult.Failed(reason = e.message ?: "unknown_error", retryable = true)
    }

private fun classifyRestException(e: RestException): MutationResult.Failed =
    when {
        e.statusCode == HTTP_UNAUTHORIZED || e.statusCode == HTTP_FORBIDDEN ->
            MutationResult.Failed(AUTH_REQUIRED_REASON, retryable = true)
        e.statusCode == HTTP_TOO_MANY_REQUESTS || e.statusCode >= HTTP_SERVER_ERROR_FLOOR ->
            MutationResult.Failed(reason = "http_${e.statusCode}", retryable = true)
        else -> MutationResult.Failed(reason = "http_${e.statusCode}", retryable = false)
    }

private fun parseOutcome(body: JsonObject): MutationResult {
    val outcome = body["outcome"]?.jsonPrimitive?.content
    return when (outcome) {
        "applied" -> {
            val row = body["row"]?.jsonObject
            val version = row?.get("version")?.jsonPrimitive?.long
            if (row == null || version == null) {
                MutationResult.Failed(reason = "malformed_applied_response", retryable = false)
            } else {
                MutationResult.Applied(row, version)
            }
        }
        "conflict" -> {
            val current = body["current"]?.jsonObject
            if (current == null) {
                MutationResult.Failed(reason = "malformed_conflict_response", retryable = false)
            } else {
                MutationResult.Conflict(current)
            }
        }
        else -> MutationResult.Failed(reason = "unexpected_outcome:$outcome", retryable = false)
    }
}
