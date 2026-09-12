package dev.elay.domain.repository

import dev.elay.domain.model.NextTimeSuggestionResult
import dev.elay.domain.model.RecordOutcomeResult
import dev.elay.domain.model.SessionOutcomeKind

/**
 * Stage 5 plan-vs-actual client surface (contracts/stage5-retention-hardening.md; this seat's
 * grant: "NEW `domain/repository/OutcomeRepository.kt`"). Deliberately a fresh interface rather
 * than an addition to [ProposalRepository] — `session_outcomes` is its own table, functionally
 * independent of proposal negotiation (contract's adopted schema: "never columns on
 * pair-projected `commitments`").
 *
 * No `AutoCloseable`, matching [dev.elay.domain.availability.AvailabilityRepository] rather than
 * [ProposalRepository]/[PairRepository]: this surface owns no background refetch loop and no
 * realtime channel (contract: "NO new realtime events, NO new external services" per the lead's
 * scope ruling; "Outcomes bypass the outbox by design") — every method here is a plain on-demand
 * suspend call.
 */
interface OutcomeRepository {
    /**
     * `rpc_record_session_outcome(p_operation_id, p_time_block_id, p_outcome, p_actual_minutes)`
     * (contract: "elapsed-only: future block → 22023; receipts; cross-action guard"). Re-recordable
     * for the same [timeBlockId] (contract: "one outcome per (owner, block) re-recordable with
     * version bump") — there is no separate update method; calling this again for a block that
     * already has a recorded outcome is the update path. [actualMinutes] is nullable per the
     * contract's "`actual_minutes` null-iff rule" (e.g. `didnt_happen`/`rescheduled` carry none).
     */
    suspend fun recordOutcome(
        operationId: String,
        timeBlockId: String,
        outcome: SessionOutcomeKind,
        actualMinutes: Int?,
    ): RecordOutcomeResult

    /**
     * `rpc_next_time_suggestion(p_task_id, p_title_key)` (contract: "server-side median of last 5
     * matching `actual_minutes`, emitted only at `sample_size >= 2`, else null"). [taskId]/
     * [titleKey] are alternative matching keys — UNVERIFIED which one (or both, or neither) A7's
     * SQL actually requires to identify "the same kind of session again" versus deriving it purely
     * server-side from [taskId]'s own history; both default to `null` so a caller that only knows
     * one of them (or neither, letting the server pick a sensible default match) has a valid call
     * shape. Flagged for the lead's merge diff against A7's real RPC signature.
     */
    suspend fun nextTimeSuggestion(
        taskId: String? = null,
        titleKey: String? = null,
    ): NextTimeSuggestionResult
}
