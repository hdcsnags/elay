package dev.elay.domain.model

import kotlin.jvm.JvmInline

/**
 * Stage 5 plan-vs-actual domain shapes (contracts/stage5-retention-hardening.md; the incorporated
 * council/stage5-hardening-opus.md §A / council/stage5-retention-gemini.md §B). Consumed by
 * [dev.elay.domain.repository.OutcomeRepository]. Reuses [TimeBlockId] from `PlannerModels.kt`
 * rather than inventing a parallel identity type — same house convention as
 * [dev.elay.domain.repository.ProposalRepository] reusing [UserId]/[PairId].
 *
 * `session_outcomes` is its own table, never columns on `commitments` (contract's "Adopted"
 * section, binding §A failure mode 1: "commitments-columns forbidden") — private, owner-only,
 * never shared-with-pair (contract: "Private, not shared… do not infer sharing"). This client
 * layer has no visibility knob to represent that: the server simply never projects another pair
 * member's outcome row to this client, the same way peer `time_blocks` rows are never projected
 * (contracts/stage2-timelock.md).
 */

@JvmInline
value class SessionOutcomeId(
    val value: String,
)

/**
 * `session_outcomes.outcome` (contract: "outcome in (ran_long, finished_early, didnt_happen,
 * rescheduled)") — a CLOSED four-value CHECK-constrained set per the contract's adopted schema,
 * yet [fromWire] is deliberately `firstOrNull`-tolerant (not [ProposalState]/[ResponseKind]'s
 * strict `entries.first`) rather than adding a placeholder fifth member: this seat never observed
 * a live `rpc_record_session_outcome` response (A7 builds the SQL in parallel from the same
 * contract), so an unrecognized wire string here is treated as a decode-time drift signal, not a
 * closed-set violation to crash on. Callers (see `SessionOutcomeDto.toDomain` in
 * `data/remote/dto/SessionOutcomeDtos.kt`) turn a `null` [fromWire] result into a mapping failure
 * that folds to a terminal `Failed` result (F12 lesson: mapping runs inside the repository's catch
 * — see `SupabaseOutcomeRepository`), never an uncaught crash.
 */
enum class SessionOutcomeKind(
    val wire: String,
) {
    RanLong("ran_long"),
    FinishedEarly("finished_early"),
    DidntHappen("didnt_happen"),
    Rescheduled("rescheduled"),
    ;

    companion object {
        fun fromWire(wire: String): SessionOutcomeKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * `session_outcomes` row (contract's adopted schema: "`actual_minutes` null-iff rule, one outcome
 * per (owner, block) re-recordable with version bump"). [actualMinutes]/[deltaMinutes] are
 * nullable-tolerant on the wire (see `SessionOutcomeDto`) — this layer does not re-derive the
 * null-iff invariant client-side; that rule is server-owned (RPC/CHECK constraint per A7's SQL).
 * [deltaMinutes] is carried as returned by the RPC (server-computed, contract: "server-side median
 * of last 5… keep it simple" is the sibling suggestion RPC's rule, but the per-row delta on this
 * type is likewise never computed client-side). [version] mirrors [Commitment.version]'s bare
 * `Long` optimistic-concurrency counter, bumped by the server on each re-recording.
 */
data class SessionOutcome(
    val id: SessionOutcomeId,
    val timeBlockId: TimeBlockId,
    val outcome: SessionOutcomeKind,
    val plannedMinutes: Int,
    val actualMinutes: Int?,
    val deltaMinutes: Int?,
    val version: Long,
)

/**
 * `rpc_next_time_suggestion` result (contract: "server-side median of last 5 matching
 * `actual_minutes`, emitted only at `sample_size >= 2`, else null — the honesty threshold pinned
 * in pgTAP"). [suggestedMinutes] is `null` whenever [sampleSize] is below the server's honesty
 * threshold; this client never re-derives or second-guesses that threshold, it only renders what
 * the server emits. [basis] is an opaque server-supplied descriptor string (e.g. a "median_last_5"
 * style label) — UNVERIFIED exact wire values, since this seat never observed a live response (A7
 * builds the SQL in parallel); rendered as-is or ignored by the UI layer (C7, after this seat),
 * never pattern-matched here.
 */
data class NextTimeSuggestion(
    val suggestedMinutes: Int?,
    val sampleSize: Int,
    val basis: String?,
)

/**
 * `rpc_record_session_outcome` command (contract: "Outcomes bypass the outbox by design
 * (idempotent receipt + foreground retry; lost outcome = lost nicety, never planner data)").
 * [operationId] is the idempotent-identifier convention shared with every other mutating command
 * in the house (e.g. [CreateProposal.operationId]). Mirrors
 * [dev.elay.domain.availability.UpsertManualBusy]/[dev.elay.domain.availability.DeleteManualBusy]'s
 * documented-but-not-interface-typed shape: [dev.elay.domain.repository.OutcomeRepository]'s
 * `recordOutcome` takes these same values as plain parameters rather than this command object
 * (same house convention as [dev.elay.domain.availability.AvailabilityRepository.upsertManualBusy]
 * not taking [dev.elay.domain.availability.UpsertManualBusy] directly) — this type documents the
 * wire-adjacent shape for call-sites that want to hold one value rather than four loose parameters.
 */
data class RecordSessionOutcome(
    val operationId: String,
    val timeBlockId: String,
    val outcome: SessionOutcomeKind,
    val actualMinutes: Int? = null,
)

/**
 * Result of [dev.elay.domain.repository.OutcomeRepository.recordOutcome]. Mirrors
 * [dev.elay.domain.availability.ExternalBusyResult]'s applied/failed shape: neither this seat nor
 * A7's parallel SQL lane describes a non-terminal `conflict` outcome for this RPC (the elapsed-only
 * future-block guard and the cross-action guard are both terminal per the contract's "receipts;
 * cross-action guard" note — UNVERIFIED whether either guard surfaces as a raised exception, a
 * non-`applied` soft envelope outcome, or both; this repository's classifier folds every
 * possibility into [Failed], flagged for the lead's merge diff against A7's real RPC). [Applied]
 * carries the full [SessionOutcome] row (unlike
 * [dev.elay.domain.availability.ExternalBusyResult.Applied], which carries nothing) because the
 * server-computed [SessionOutcome.deltaMinutes]/[SessionOutcome.version] are new information the
 * caller did not already hold.
 */
sealed interface RecordOutcomeResult {
    data class Applied(
        val outcome: SessionOutcome,
    ) : RecordOutcomeResult

    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : RecordOutcomeResult
}

/**
 * Result of [dev.elay.domain.repository.OutcomeRepository.nextTimeSuggestion]. Mirrors
 * [dev.elay.domain.availability.AvailabilitySourcesResult]'s `Loaded`/`Failed` naming (a plain read,
 * not a mutation — no `Applied`). A "no suggestion yet" answer (sub-threshold sample size) is still
 * [Loaded] with [NextTimeSuggestion.suggestedMinutes] `null`, never a [Failed] — there is nothing
 * wrong with the call, the server is just being honest about insufficient data.
 */
sealed interface NextTimeSuggestionResult {
    data class Loaded(
        val suggestion: NextTimeSuggestion,
    ) : NextTimeSuggestionResult

    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : NextTimeSuggestionResult
}
