package dev.elay.data.remote

import dev.elay.domain.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Frozen remote boundary (ADR-002: every supabase-kt touchpoint lives behind
 * these; replacing the SDK must never touch domain code).
 */

sealed interface SessionState {
    data object SignedOut : SessionState

    data object Refreshing : SessionState

    data class SignedIn(
        val userId: UserId,
    ) : SessionState
}

interface AuthGateway {
    val session: Flow<SessionState>

    suspend fun signInWithEmail(
        email: String,
        password: String,
    ): Result<UserId>

    suspend fun signOut()

    suspend fun refreshSession(): Result<Unit>
}

/** Result of one mutation RPC (contracts/phase1-planner.md §2). */
sealed interface MutationResult {
    data class Applied(
        val row: JsonObject,
        val version: Long,
    ) : MutationResult

    data class Conflict(
        val current: JsonObject,
    ) : MutationResult

    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : MutationResult
}

/**
 * Typed mutation surface: one call per RPC, `row` serialized per the wire
 * contract (§4 — snake_case, ISO-8601 UTC instants, exact enum strings).
 */
interface DataGateway {
    suspend fun upsertGoal(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult

    suspend fun upsertMilestone(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult

    suspend fun upsertTask(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult

    suspend fun upsertCapture(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult

    suspend fun upsertTimeBlock(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult

    suspend fun deleteGoal(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult

    suspend fun deleteTask(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult

    suspend fun deleteCapture(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult

    suspend fun deleteTimeBlock(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult

    /** Pull authoritative rows changed since the given version watermark (Phase 1 refresh). */
    suspend fun fetchSince(
        aggregate: String,
        sinceVersion: Long,
    ): Result<List<JsonObject>>
}
