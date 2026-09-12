package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.AvailabilitySourceDto
import dev.elay.data.remote.dto.ConflictHintDto
import dev.elay.data.remote.dto.ExternalBusyRpcEnvelopeDto
import dev.elay.domain.model.Candidate

/**
 * Scripted, in-memory [AvailabilityTransport] — no [io.github.jan.supabase.SupabaseClient], no
 * network. Unlike [FakeProposalTransport] there is no queue-then-repeat-last read loop to drive:
 * every method here is a plain on-demand suspend call (contract: no refetch loop for this
 * surface), so each result/error is simply set once per test.
 */
internal class FakeAvailabilityTransport : AvailabilityTransport {
    var mySourcesResult: List<AvailabilitySourceDto>? = null
    var mySourcesError: Throwable? = null
    var mySourcesCallCount = 0
        private set

    var upsertResult: ExternalBusyRpcEnvelopeDto? = null
    var upsertError: Throwable? = null
    var lastUpsertCall: UpsertCall? = null
        private set

    var deleteResult: ExternalBusyRpcEnvelopeDto? = null
    var deleteError: Throwable? = null
    var lastDeleteCall: DeleteCall? = null
        private set

    var selfConflictHintsResult: List<ConflictHintDto>? = null
    var selfConflictHintsError: Throwable? = null
    var lastSelfConflictHintsCandidates: List<Candidate>? = null
        private set

    data class UpsertCall(
        val operationId: String,
        val busyId: String,
        val startsAtUtc: String,
        val endsAtUtc: String,
        val originZoneId: String,
    )

    data class DeleteCall(
        val operationId: String,
        val busyId: String,
    )

    override suspend fun fetchMySources(): List<AvailabilitySourceDto> {
        mySourcesCallCount++
        mySourcesError?.let { throw it }
        return mySourcesResult ?: error("mySourcesResult not scripted for this test")
    }

    override suspend fun upsertManualBusy(
        operationId: String,
        busyId: String,
        startsAtUtc: String,
        endsAtUtc: String,
        originZoneId: String,
    ): ExternalBusyRpcEnvelopeDto {
        lastUpsertCall = UpsertCall(operationId, busyId, startsAtUtc, endsAtUtc, originZoneId)
        upsertError?.let { throw it }
        return upsertResult ?: error("upsertResult not scripted for this test")
    }

    override suspend fun deleteManualBusy(
        operationId: String,
        busyId: String,
    ): ExternalBusyRpcEnvelopeDto {
        lastDeleteCall = DeleteCall(operationId, busyId)
        deleteError?.let { throw it }
        return deleteResult ?: error("deleteResult not scripted for this test")
    }

    override suspend fun selfConflictHints(candidates: List<Candidate>): List<ConflictHintDto> {
        lastSelfConflictHintsCandidates = candidates
        selfConflictHintsError?.let { throw it }
        return selfConflictHintsResult ?: error("selfConflictHintsResult not scripted for this test")
    }
}
