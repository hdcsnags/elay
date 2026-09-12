package dev.elay.data.remote.dto

import dev.elay.domain.availability.AvailabilitySource
import dev.elay.domain.availability.BusyInterval
import dev.elay.domain.availability.BusySnapshot
import dev.elay.domain.availability.Certainty
import dev.elay.domain.availability.SourceStatus
import dev.elay.domain.availability.SourceTag
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for Stage 4 honest availability (contracts/stage4-honest-availability.md;
 * council/stage4-availability-opus.md §A). snake_case keys, ISO-8601 UTC instant strings — same
 * house convention as `ProposalDtos`/`RsvpDtos`/`PairDtos`.
 *
 * No `contracts/fixtures/availability-*.json` exists yet in this seat's grant — A6 owns that path
 * (seat grants table) and produces it from real RPC output in parallel with this seat. These DTOs
 * are hand-authored from the freeze's §A key sets the same way `ProposalDtos`'s were for Stage 2 —
 * the lead diffs both against A6's real RPC output at merge; any mismatch is fixed on this seat's
 * side unless the SQL deviates from the contract. Fields not spelled out verbatim in the freeze
 * excerpt (the request shape of `rpc_self_conflict_hints`'s candidate array, and whether
 * `rpc_my_availability_sources`/`rpc_self_conflict_hints` wrap their arrays in an envelope the way
 * `rpc_list_proposals` does) are flagged inline below — UNVERIFIED, for the lead's merge diff.
 *
 * `availability_sources` row (`rpc_my_availability_sources()`). All four of [lastSyncedAt],
 * [windowStart], [windowEnd] are nullable-with-defaults (Stage-2 wire lesson: server may omit
 * keys for a source that has never synced) — [status] is decoded raw, matching [SourceStatus]'s
 * unknown-tolerant design (the contract's lane docs do not enumerate a closed status set).
 *
 * UNVERIFIED: assumed bare-array response (`rpc_my_availability_sources()` returns
 * `List<AvailabilitySourceDto>` directly, no `{"items":[…]}` envelope) since this RPC is
 * unbounded-but-tiny (at most one row per known provider, no pagination need) — unlike
 * `rpc_list_proposals`'s cursor-driven envelope. Flagged for A6/lead reconciliation at merge.
 */
@Serializable
data class AvailabilitySourceDto(
    @SerialName("source_tag") val sourceTag: String,
    val status: String,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
    @SerialName("freshness_ttl_minutes") val freshnessTtlMinutes: Int,
    @SerialName("window_start") val windowStart: String? = null,
    @SerialName("window_end") val windowEnd: String? = null,
)

fun AvailabilitySourceDto.toDomain(): AvailabilitySource =
    AvailabilitySource(
        sourceTag = SourceTag(sourceTag),
        status = SourceStatus(status),
        lastSyncedAt = lastSyncedAt?.let(Instant::parse),
        freshnessTtlMinutes = freshnessTtlMinutes,
        windowStart = windowStart?.let(Instant::parse),
        windowEnd = windowEnd?.let(Instant::parse),
    )

/** One `busy_windows[]` element inside a conflict-hint entry (contract: "the SAME clipped
 * `busy_windows`… coalescing of overlapping/adjacent intervals before return"). No source
 * attribution on the wire by design — provenance is masked by the server's union. */
@Serializable
data class BusyWindowDto(
    @SerialName("starts_at_utc") val startsAtUtc: String,
    @SerialName("ends_at_utc") val endsAtUtc: String,
)

fun BusyWindowDto.toDomain(): BusyInterval =
    BusyInterval(
        startsAt = Instant.parse(startsAtUtc),
        endsAt = Instant.parse(endsAtUtc),
    )

fun BusyInterval.toDto(): BusyWindowDto =
    BusyWindowDto(
        startsAtUtc = startsAt.toString(),
        endsAtUtc = endsAt.toString(),
    )

/**
 * One `rpc_self_conflict_hints` result entry — the exact key set the freeze names for both
 * conflict-hint RPCs: "`{candidate_idx, has_conflict, busy_windows:[{starts_at_utc,ends_at_utc}],
 * certainty}`". [certainty] is decoded raw here and only resolved to [Certainty] in [toDomain] via
 * [Certainty.fromWire] — never throws on drift (an unrecognized string, including the rate-limited
 * "opaque `certainty='unknown'`" degradation the contract calls out, must decode and fall back to
 * [Certainty.Unknown], not crash the composer).
 *
 * UNVERIFIED: assumed bare-array response (`List<ConflictHintDto>`, one entry per requested
 * candidate, `candidate_idx`-ordered) — flagged alongside [AvailabilitySourceDto]'s envelope
 * assumption for A6/lead reconciliation at merge.
 */
@Serializable
data class ConflictHintDto(
    @SerialName("candidate_idx") val candidateIdx: Int,
    @SerialName("has_conflict") val hasConflict: Boolean,
    @SerialName("busy_windows") val busyWindows: List<BusyWindowDto> = emptyList(),
    val certainty: String,
)

fun ConflictHintDto.toDomain(): BusySnapshot =
    BusySnapshot(
        candidateIdx = candidateIdx,
        hasConflict = hasConflict,
        busyWindows = busyWindows.map { it.toDomain() },
        certainty = Certainty.fromWire(certainty),
    )

/**
 * Uniform result envelope for the two manual-busy mutations (`rpc_upsert_external_busy`,
 * `rpc_delete_external_busy`) — `action`/`outcome` cross-RPC replay-guard pattern, matching
 * [PairRpcEnvelopeDto]/[ProposalRpcEnvelopeDto]. Carries no payload field: both commands' callers
 * already hold every value worth reporting back (the [dev.elay.domain.availability.UpsertManualBusy]/
 * [dev.elay.domain.availability.DeleteManualBusy] command's own `busyId`), so `applied` needs
 * nothing more than the bare outcome.
 *
 * UNVERIFIED: this seat did not observe a live cross-action-22023 conflict envelope for either RPC
 * (A6 is building the SQL in parallel) — [action] is nullable defensively, same F12-adjacent
 * tolerance as [ProposalRpcEnvelopeDto.action], in case a future conflict shape omits it.
 */
@Serializable
data class ExternalBusyRpcEnvelopeDto(
    val outcome: String,
    val action: String? = null,
)
