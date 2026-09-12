package dev.elay.domain.availability

import dev.elay.domain.model.Candidate
import kotlinx.datetime.Instant

/**
 * Stage 4 honest-availability client surface (contracts/stage4-honest-availability.md; this
 * seat's grant: "NEW `shared/.../domain/availability` (recursive) … not touching
 * ProposalRepository"). Deliberately a fresh interface rather than an addition to
 * [dev.elay.domain.repository.ProposalRepository] — the contract's B7 grant lists this file as
 * new, and the two are functionally independent (availability hints are read on-demand at
 * composer/responder open, never through the proposal refetch loop).
 *
 * No `AutoCloseable`: unlike [dev.elay.domain.repository.PairRepository]/
 * [dev.elay.domain.repository.ProposalRepository], this surface owns no background refetch loop
 * and no realtime channel to tear down (contract: "No new realtime events… clients refetch hints
 * on composer/responder open" — plain on-demand suspend calls, nothing to cancel on sign-out
 * beyond what already tears down the underlying [io.github.jan.supabase.SupabaseClient]).
 */
interface AvailabilityRepository {
    /** `rpc_my_availability_sources()` — the caller's own per-source freshness/window bookkeeping
     * (contract: staleness copy at every surface reads this, never a peer-directed hint response,
     * which carries no sync timestamps/source tags). No arguments: sources are always the caller's
     * own, derived server-side from the authenticated session. */
    suspend fun mySources(): AvailabilitySourcesResult

    /** `rpc_upsert_external_busy` — the manual "I'm busy then" sheet's only write path (contract:
     * "Manual provider ships as UI (one sheet, §B)"). [busyId] is client-generated and reused by a
     * later [deleteManualBusy] call against the same row; `source_tag` is never a parameter here —
     * the server hard-codes `'manual'` (contract: "client-supplied tags forbidden"). */
    @Suppress("LongParameterList") // operation identity + interval + zone + optional label (wire shape)
    suspend fun upsertManualBusy(
        operationId: String,
        busyId: String,
        startsAt: Instant,
        endsAt: Instant,
        originZoneId: String,
        label: String? = null,
    ): ExternalBusyResult

    /** `rpc_delete_external_busy` — removes a previously-upserted manual busy row by the same
     * [busyId] the caller minted for it. */
    suspend fun deleteManualBusy(
        operationId: String,
        busyId: String,
    ): ExternalBusyResult

    /** `rpc_self_conflict_hints` — the caller-facing sibling of the partner-directed
     * `rpc_proposal_conflict_hints` extension (contract: "New `rpc_self_conflict_hints` returns the
     * identical shape for the caller"). Reuses [Candidate] rather than a parallel request type —
     * the same instants a composer/responder is already holding for
     * [dev.elay.domain.model.CreateProposal]/[dev.elay.domain.model.RespondProposal.Counter]. No
     * on-demand third-party read happens here (contract: "on-demand third-party reads in user
     * paths are forbidden") — this only ever reads the server's windowed snapshot. */
    suspend fun selfConflictHints(candidates: List<Candidate>): SelfConflictHintsResult
}
