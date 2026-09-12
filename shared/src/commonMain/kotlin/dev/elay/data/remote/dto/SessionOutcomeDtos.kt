package dev.elay.data.remote.dto

import dev.elay.domain.model.NextTimeSuggestion
import dev.elay.domain.model.SessionOutcome
import dev.elay.domain.model.SessionOutcomeId
import dev.elay.domain.model.SessionOutcomeKind
import dev.elay.domain.model.TimeBlockId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for Stage 5 plan-vs-actual (contracts/stage5-retention-hardening.md). snake_case
 * keys — same house convention as `ProposalDtos`/`AvailabilityDtos`.
 *
 * No `contracts/fixtures/session-outcome-*.json` exists in this seat's grant (A7 owns that path
 * — seat grants table — and produces it from real RPC output in parallel with this seat). These
 * DTOs, and the fixtures in `SessionOutcomeDtosTest`, are hand-authored from the frozen contract's
 * wire-shape prose the same way `AvailabilitySourceDto`'s were for Stage 4 — the lead diffs both
 * against A7's real RPC output at merge; any mismatch is fixed on this seat's side unless the SQL
 * deviates from the contract.
 *
 * `session_outcomes` row, nested under the record RPC's `session_outcome` envelope key (contract:
 * `{"outcome":"applied","action":"record_session_outcome","session_outcome":{…incl.
 * planned_minutes/actual_minutes/delta_minutes…}}`). [actualMinutes]/[deltaMinutes] are
 * nullable-with-defaults per the contract's "`actual_minutes` null-iff rule" — a `didnt_happen`/
 * `rescheduled` row carries neither.
 */
@Serializable
data class SessionOutcomeDto(
    val id: String,
    @SerialName("time_block_id") val timeBlockId: String,
    val outcome: String,
    @SerialName("planned_minutes") val plannedMinutes: Int,
    @SerialName("actual_minutes") val actualMinutes: Int? = null,
    @SerialName("delta_minutes") val deltaMinutes: Int? = null,
    val version: Long,
)

/**
 * [SessionOutcomeKind.fromWire] is `firstOrNull`-tolerant (see that enum's kdoc) — an unrecognized
 * `outcome` string here throws via [requireNotNull] rather than silently defaulting, so this
 * function is only ever safe to call from inside a catch that folds the failure into a terminal
 * result (F12 lesson; see `SupabaseOutcomeRepository`'s `toResult()`, which runs this INSIDE its
 * `runCatchingSuspend` block). Never call this from a context that lets the exception escape
 * uncaught.
 */
fun SessionOutcomeDto.toDomain(): SessionOutcome =
    SessionOutcome(
        id = SessionOutcomeId(id),
        timeBlockId = TimeBlockId(timeBlockId),
        outcome =
            requireNotNull(SessionOutcomeKind.fromWire(outcome)) {
                "unrecognized session_outcomes.outcome wire value: $outcome"
            },
        plannedMinutes = plannedMinutes,
        actualMinutes = actualMinutes,
        deltaMinutes = deltaMinutes,
        version = version,
    )

/**
 * Uniform result envelope for `rpc_record_session_outcome` — `action`/`outcome` cross-RPC replay-
 * guard pattern, matching [ProposalRpcEnvelopeDto]/[ExternalBusyRpcEnvelopeDto]. [action] is
 * nullable defensively (same F12-adjacent tolerance as [ProposalRpcEnvelopeDto.action]) in case a
 * non-`applied` outcome omits it, as the Stage 2 conflict envelopes do.
 *
 * UNVERIFIED (this seat never observed a live envelope; A7 builds the SQL in parallel): the exact
 * non-`applied` outcome string(s) the elapsed-only future-block guard and the cross-action guard
 * (contract: "elapsed-only: future block → 22023… cross-action guard") emit through this soft
 * envelope versus surfacing as a raised/thrown exception instead. [SupabaseOutcomeRepository]'s
 * classifier tolerates either: a non-`applied` outcome string folds to a terminal
 * `RecordOutcomeResult.Failed` here, and a thrown exception (e.g. a genuine HTTP-level 22023
 * rejection) folds to the same terminal shape via
 * [dev.elay.data.remote.impl.SupabaseOutcomeRepository]'s network-failure classifier. Flagged for
 * the lead's merge diff against A7's real RPC.
 */
@Serializable
data class SessionOutcomeRpcEnvelopeDto(
    val outcome: String,
    val action: String? = null,
    @SerialName("session_outcome") val sessionOutcome: SessionOutcomeDto? = null,
)

/**
 * `rpc_next_time_suggestion` result (contract: `{"suggested_minutes","sample_size","basis"}`) — a
 * bare object, not a list/array envelope (unlike `rpc_list_proposals`'s cursor-driven shape): one
 * suggestion per call, never a collection. [suggestedMinutes]/[basis] are nullable per the
 * contract's honesty threshold ("emitted only at `sample_size >= 2`, else null"); [sampleSize]
 * defaults to `0` defensively should the server omit the key for a caller with zero matching rows,
 * though the contract's prose implies it is always present.
 */
@Serializable
data class NextTimeSuggestionDto(
    @SerialName("suggested_minutes") val suggestedMinutes: Int? = null,
    @SerialName("sample_size") val sampleSize: Int = 0,
    val basis: String? = null,
)

fun NextTimeSuggestionDto.toDomain(): NextTimeSuggestion =
    NextTimeSuggestion(
        suggestedMinutes = suggestedMinutes,
        sampleSize = sampleSize,
        basis = basis,
    )
