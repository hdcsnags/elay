package dev.elay.domain.availability

import kotlinx.datetime.Instant
import kotlin.jvm.JvmInline

/**
 * Stage 4 honest-availability domain shapes (contracts/stage4-honest-availability.md;
 * council/stage4-availability-opus.md §A). Consumed by [AvailabilityRepository], which reuses
 * `dev.elay.domain.model.Candidate` for `rpc_self_conflict_hints`'s request shape rather than
 * inventing a parallel candidate type — same house convention as
 * [dev.elay.domain.repository.ProposalRepository] reusing [dev.elay.domain.model.UserId]/
 * [dev.elay.domain.model.PairId].
 *
 * Time truth mirrors `dev.elay.domain.model.TimeBlock`/`dev.elay.domain.model.Candidate`
 * (ADR-006): every instant here is a UTC [Instant]; this layer never formats.
 *
 * Provenance tag for one external-busy source (contract: "Separate `external_busy` table…plus
 * `availability_sources`"; "manual-provider-as-UI with server-hard-coded source_tag"). A plain
 * wrapper over the wire string, not an enum — deliberately unknown-tolerant: the deferred Google
 * adapter, or any future provider added server-side before this client build knows its name, must
 * decode to a live [SourceTag] value rather than throwing (Stage-2 wire lesson: never let an
 * unrecognized server string crash the decode). Callers who need to branch on "do I recognize
 * this" compare against [Manual]/[Google] or check [isKnown]; nothing here matches on candidate
 * strings.
 */
@JvmInline
value class SourceTag(
    val wire: String,
) {
    val isKnown: Boolean get() = this == Manual || this == Google

    companion object {
        val Manual = SourceTag("manual")
        val Google = SourceTag("google")
    }
}

/**
 * `availability_sources.status` wire value, kept as unknown-tolerant as [SourceTag] for the same
 * reason: the contract's lane docs do not enumerate a closed set of status strings for this
 * client to pattern-match, and the deferred Google adapter may introduce values
 * (connecting/error/revoked/…) this build has never seen. UI call-sites (C6, after this seat)
 * render [wire] through their own calm copy table with an unrecognized-value fallback, never by
 * crashing on an unmapped case.
 */
@JvmInline
value class SourceStatus(
    val wire: String,
)

/** One busy interval, either the caller's own manual entry or a partner's/own external-busy
 * window as unioned-and-coalesced by the server (contract: "coalescing of overlapping/adjacent
 * intervals before return… interval shape is a 'why' — provenance masked by union"). This client
 * never attempts to attribute a [BusyInterval] back to a source; the server has already erased
 * that distinction by design. */
data class BusyInterval(
    val startsAt: Instant,
    val endsAt: Instant,
)

/**
 * Server-computed certainty ladder (contract: "Certainty ladder, server-computed only… busy
 * always wins"). [fromWire] never throws on drift — an unrecognized string (a future ladder rung,
 * or the rate-limited "opaque" degradation the contract calls out: "over-limit returns the opaque
 * `certainty='unknown'` shape") falls back to [Unknown], mirroring [fromWire]'s `firstOrNull`
 * pattern rather than [dev.elay.domain.model.ProposalState]'s `first` (which is intentionally
 * strict because every one of Stage 2's eight wire strings is contractually enumerable; this
 * ladder is not, by the contract's own design).
 */
enum class Certainty(
    val wire: String,
) {
    Busy("busy"),
    FreePerElay("free_per_elay"),
    FreePerCalendar("free_per_calendar"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWire(wire: String?): Certainty = entries.firstOrNull { it.wire == wire } ?: Unknown
    }
}

/**
 * `rpc_my_availability_sources()` row — the caller's own per-source freshness/window bookkeeping
 * (contract: "`availability_sources` (per-source freshness/window bookkeeping,
 * `freshness_ttl_minutes`: manual=43200, google=360)"). [lastSyncedAt]/[windowStart]/[windowEnd]
 * are nullable: a source with no sync yet (e.g. the deferred Google adapter's row before its first
 * `fn_sync_external_busy` run) has none of the three. Peer-directed hint responses never carry
 * this bookkeeping (contract: "staleness copy reads the caller's OWN
 * `rpc_my_availability_sources()`") — this type is only ever populated for the caller's own
 * sources.
 */
data class AvailabilitySource(
    val sourceTag: SourceTag,
    val status: SourceStatus,
    val lastSyncedAt: Instant?,
    val freshnessTtlMinutes: Int,
    val windowStart: Instant?,
    val windowEnd: Instant?,
)

/** `rpc_upsert_external_busy` command. [busyId] is client-generated (same idempotent-identifier
 * convention as [operationId] elsewhere in the house — e.g.
 * [dev.elay.domain.model.CreateProposal.operationId]): the caller mints it once and reuses it for
 * a later [AvailabilityRepository.deleteManualBusy] call. No `sourceTag` field exists here by
 * design — the contract hard-codes `source_tag='manual'` server-side for every row this command
 * creates ("client-supplied tags forbidden: users could fabricate the provenance
 * `free_per_calendar` trusts"), so there is nothing for this client to supply or even represent. */
data class UpsertManualBusy(
    val operationId: String,
    val busyId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val originZoneId: String,
)

/** `rpc_delete_external_busy` command. */
data class DeleteManualBusy(
    val operationId: String,
    val busyId: String,
)

/**
 * Result of the two manual-busy mutations (contract: manual-provider-as-UI; "receipts +
 * cross-action 22023 per house ledger"). Mirrors [dev.elay.domain.model.MintRsvpResult]'s
 * applied/failed shape: neither RPC's contract text describes a non-terminal `conflict` outcome
 * (unlike `rpc_respond_proposal`'s stale-revision conflict), so there is no `Conflict` variant
 * here — every non-`applied` outcome, including the cross-action 22023 guard, folds into [Failed].
 * [Applied] carries no payload: both [busyId]/[operationId] are already known to the caller (they
 * supplied them), so there is no new domain value to hand back.
 */
sealed interface ExternalBusyResult {
    data object Applied : ExternalBusyResult

    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : ExternalBusyResult
}

/**
 * One candidate's conflict snapshot — the identical shape `rpc_proposal_conflict_hints` (partner-
 * facing) and `rpc_self_conflict_hints` (this seat's grant) both return per the contract's
 * additive extension: "exactly one new key `certainty`" alongside the existing clipped
 * `busy_windows`. [certainty] never throws on an unrecognized wire string — see [Certainty.fromWire].
 */
data class BusySnapshot(
    val candidateIdx: Int,
    val hasConflict: Boolean,
    val busyWindows: List<BusyInterval>,
    val certainty: Certainty,
)

/** `rpc_my_availability_sources()` result. Mirrors [ExternalBusyResult]'s applied/failed shape
 * (named `Loaded`/`Failed` here rather than `Applied`/`Failed` since nothing is being applied — this
 * is a plain read, not a mutation). */
sealed interface AvailabilitySourcesResult {
    data class Loaded(
        val sources: List<AvailabilitySource>,
    ) : AvailabilitySourcesResult

    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : AvailabilitySourcesResult
}

/** `rpc_self_conflict_hints` result — one [BusySnapshot] per requested candidate, in the same
 * order (contract's `candidate_idx` key lets callers re-associate defensively rather than relying
 * on array order alone). */
sealed interface SelfConflictHintsResult {
    data class Loaded(
        val snapshots: List<BusySnapshot>,
    ) : SelfConflictHintsResult

    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : SelfConflictHintsResult
}
