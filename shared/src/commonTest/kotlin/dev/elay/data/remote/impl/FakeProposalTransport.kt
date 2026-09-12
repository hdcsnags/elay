package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.ProposalRpcEnvelopeDto
import dev.elay.data.remote.dto.ProposalSummaryDto
import dev.elay.domain.model.CreateProposal
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.RespondProposal
import kotlinx.coroutines.CompletableDeferred

/**
 * Scripted, in-memory [ProposalTransport] driving [SupabaseProposalRepository]'s refetch loop with
 * no network — see [SupabaseProposalRepository]'s kdoc for why this seam exists. Mirrors
 * [FakePairTransport]'s queue-then-repeat-last semantics for the two read queues.
 */
internal class FakeProposalTransport : ProposalTransport {
    private val activeQueue = ArrayDeque<List<ProposalSummaryDto>>()
    private var lastActive: List<ProposalSummaryDto> = emptyList()
    var fetchActiveCount = 0
        private set

    private val historyQueue = ArrayDeque<List<ProposalSummaryDto>>()
    private var lastHistory: List<ProposalSummaryDto> = emptyList()
    var fetchHistoryCount = 0
        private set

    var createResult: ProposalRpcEnvelopeDto? = null
    var respondResult: ProposalRpcEnvelopeDto? = null
    var cancelResult: ProposalRpcEnvelopeDto? = null
    var completeResult: ProposalRpcEnvelopeDto? = null

    /** Set by [armFetchActiveGate] — when non-null, every [fetchActive] call suspends on it
     * before returning (a completed gate just resolves immediately, so this stays armed rather
     * than being cleared after first use). Scripts the mid-refetch invalidation-retention pin
     * (commonTest residual F10): a hint that lands while `fetchActive()` is still suspended. */
    private var fetchActiveGate: CompletableDeferred<Unit>? = null

    /** `rpc_list_proposals(scope='active')` responses, consumed one per call to [fetchActive];
     * once the queue is drained the last value returned keeps repeating. */
    fun enqueueActive(proposals: List<ProposalSummaryDto>) {
        activeQueue.addLast(proposals)
    }

    /** Same contract as [enqueueActive] for `scope='history'`. */
    fun enqueueHistory(proposals: List<ProposalSummaryDto>) {
        historyQueue.addLast(proposals)
    }

    /** Arms a gate that suspends every subsequent [fetchActive] call until the test completes
     * the returned [CompletableDeferred] — the caller's window to script an invalidation hint
     * arriving mid-fetch. */
    fun armFetchActiveGate(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        fetchActiveGate = gate
        return gate
    }

    override suspend fun fetchActive(): List<ProposalSummaryDto> {
        fetchActiveCount++
        fetchActiveGate?.await()
        val next = if (activeQueue.isNotEmpty()) activeQueue.removeFirst() else lastActive
        lastActive = next
        return next
    }

    override suspend fun fetchHistory(): List<ProposalSummaryDto> {
        fetchHistoryCount++
        val next = if (historyQueue.isNotEmpty()) historyQueue.removeFirst() else lastHistory
        lastHistory = next
        return next
    }

    override suspend fun createProposal(command: CreateProposal): ProposalRpcEnvelopeDto =
        createResult ?: error("createResult not scripted for this test")

    override suspend fun respondProposal(command: RespondProposal): ProposalRpcEnvelopeDto =
        respondResult ?: error("respondResult not scripted for this test")

    override suspend fun cancelProposal(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalRpcEnvelopeDto = cancelResult ?: error("cancelResult not scripted for this test")

    override suspend fun completeLock(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalRpcEnvelopeDto = completeResult ?: error("completeResult not scripted for this test")
}
