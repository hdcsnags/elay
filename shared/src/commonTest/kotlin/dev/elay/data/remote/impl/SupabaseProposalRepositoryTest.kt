package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.ProposalRpcEnvelopeDto
import dev.elay.data.remote.dto.ProposalSummaryDto
import dev.elay.domain.model.Candidate
import dev.elay.domain.model.CreateProposal
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalResult
import dev.elay.domain.model.ProposalState
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.RespondProposal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PROPOSAL_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

private fun proposalDto(
    id: String = PROPOSAL_ID,
    status: String = "proposed",
    currentRevision: Int = 1,
) = ProposalSummaryDto(
    id = id,
    pairId = "70000000-0000-4000-8000-000000000001",
    creatorId = "00000000-0000-0000-0000-000000000001",
    title = "Study session",
    status = status,
    responseDeadline = "2026-09-13T19:00:00Z",
    originTz = "America/Vancouver",
    currentRevision = currentRevision,
    version = 1,
    createdAt = "2026-09-12T19:00:00Z",
    updatedAt = "2026-09-12T19:00:00Z",
)

private fun candidate(index: Int = 0) =
    Candidate(
        index = index,
        startsAt = Instant.parse("2026-09-20T23:00:00Z"),
        endsAt = Instant.parse("2026-09-21T00:00:00Z"),
        durationMinutes = 60,
    )

private fun createCommand() =
    CreateProposal(
        operationId = "op-create-1",
        title = "Study session",
        originZoneId = "America/Vancouver",
        responseDeadline = Instant.parse("2026-09-13T19:00:00Z"),
        candidates = listOf(candidate()),
    )

/** Collects every emission of [flow] into a list on [TestScope.backgroundScope] — used instead of
 * `StateFlow.value` because the frozen [dev.elay.domain.repository.ProposalRepository] surface
 * returns a plain `Flow`, not a `StateFlow` (unlike [dev.elay.domain.repository.PairRepository]'s
 * `observePair()`). */
private fun <T> TestScope.collectValues(flow: Flow<T>): List<T> {
    val values = mutableListOf<T>()
    backgroundScope.launch { flow.collect { values += it } }
    return values
}

/**
 * State-machine tests for [SupabaseProposalRepository] against [FakeProposalTransport] — no
 * `SupabaseClient`, no network. Uses `backgroundScope`/`runCurrent()` (never
 * `advanceUntilIdle()` — the refetch loop is a perpetual coroutine that never goes idle on its
 * own, so `advanceUntilIdle()` would never return; same reason as
 * [SupabasePairRepositoryTest]).
 */
class SupabaseProposalRepositoryTest {
    @Test
    fun createAppliedRefetchesAndSurfacesTheNewProposalInActive() =
        runTest {
            val transport = FakeProposalTransport()
            transport.enqueueActive(emptyList())
            transport.enqueueHistory(emptyList())
            val hints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val repository = SupabaseProposalRepository(backgroundScope, transport, hints)
            val activeValues = collectValues(repository.observeActive())
            runCurrent()
            assertEquals(listOf<List<ProposalSummary>>(emptyList()), activeValues)
            assertEquals(1, transport.fetchActiveCount)

            transport.createResult =
                ProposalRpcEnvelopeDto(
                    outcome = "applied",
                    action = "create_proposal",
                    proposal = proposalDto(status = "proposed"),
                )
            transport.enqueueActive(listOf(proposalDto(status = "proposed")))
            transport.enqueueHistory(emptyList())

            val result = repository.create(createCommand())
            runCurrent()

            assertTrue(result is ProposalResult.Applied)
            assertEquals(ProposalState.Proposed, (result as ProposalResult.Applied).proposal.state)
            assertEquals(2, transport.fetchActiveCount)
            assertEquals(1, activeValues.last().size)
            assertEquals(ProposalId(PROPOSAL_ID), activeValues.last().single().id)
        }

    @Test
    fun respondAcceptAppliedMovesTheProposalFromActiveToHistory() =
        runTest {
            val transport = FakeProposalTransport()
            transport.enqueueActive(listOf(proposalDto(status = "proposed")))
            transport.enqueueHistory(emptyList())
            val hints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val repository = SupabaseProposalRepository(backgroundScope, transport, hints)
            val activeValues = collectValues(repository.observeActive())
            val historyValues = collectValues(repository.observeHistory())
            runCurrent()
            assertEquals(1, activeValues.last().size)

            transport.respondResult =
                ProposalRpcEnvelopeDto(
                    outcome = "applied",
                    action = "respond_proposal",
                    proposal = proposalDto(status = "accepted"),
                )
            transport.enqueueActive(emptyList())
            transport.enqueueHistory(listOf(proposalDto(status = "accepted")))

            val command =
                RespondProposal.Accept(
                    operationId = "op-respond-1",
                    proposalId = ProposalId(PROPOSAL_ID),
                    expectedRevision = 1,
                    candidateIdx = 0,
                )
            val result = repository.respond(command)
            runCurrent()

            assertTrue(result is ProposalResult.Applied)
            assertEquals(ProposalState.Accepted, (result as ProposalResult.Applied).proposal.state)
            assertEquals(2, transport.fetchActiveCount)
            assertTrue(activeValues.last().isEmpty())
            assertEquals(ProposalState.Accepted, historyValues.last().single().state)
        }

    @Test
    fun respondConflictSurfacesCurrentRevisionWithoutRefetching() =
        runTest {
            val transport = FakeProposalTransport()
            transport.enqueueActive(listOf(proposalDto(status = "countered", currentRevision = 2)))
            transport.enqueueHistory(emptyList())
            val hints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val repository = SupabaseProposalRepository(backgroundScope, transport, hints)
            runCurrent()
            assertEquals(1, transport.fetchActiveCount)

            transport.respondResult =
                ProposalRpcEnvelopeDto(
                    outcome = "conflict",
                    action = "respond_proposal",
                    currentRevision = 3,
                    status = "countered",
                )
            val command =
                RespondProposal.Accept(
                    operationId = "op-respond-2",
                    proposalId = ProposalId(PROPOSAL_ID),
                    expectedRevision = 2,
                    candidateIdx = 0,
                )
            val result = repository.respond(command)
            runCurrent()

            assertEquals(ProposalResult.Conflict(3, ProposalState.Countered), result)
            // A conflict is non-mutating (contract §A.2) — no kick, so no second fetch.
            assertEquals(1, transport.fetchActiveCount)
        }

    @Test
    fun externalInvalidationHintTriggersARefetch() =
        runTest {
            val transport = FakeProposalTransport()
            transport.enqueueActive(emptyList())
            transport.enqueueHistory(emptyList())
            val hints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val repository = SupabaseProposalRepository(backgroundScope, transport, hints)
            val activeValues = collectValues(repository.observeActive())
            runCurrent()
            assertEquals(1, transport.fetchActiveCount)

            transport.enqueueActive(listOf(proposalDto()))
            transport.enqueueHistory(emptyList())
            // Simulates a pair.proposal_updated.v1/pair.commitment_changed.v1 broadcast forwarded
            // by whatever owns the pair channel (see SupabaseProposalRepository's invalidation-
            // seam kdoc) — this repository never inspects which event fired.
            hints.tryEmit(Unit)
            runCurrent()

            assertEquals(2, transport.fetchActiveCount)
            assertEquals(1, activeValues.last().size)
        }

    @Test
    fun invalidationHintDuringASuspendedFetchStillTriggersAFollowUpRefetch() =
        runTest {
            // Pins the mid-refetch retention fix (pre-gate finding F10, SupabaseProposalRepository's
            // `kick`/`refetchLoop` kdoc): a bare `MutableSharedFlow(replay = 0)` kick DISCARDS an
            // emission while nothing is yet suspended on `kick.receive()` (refetch() hasn't
            // returned) — the conflated channel + forwarding collector armed before the first
            // fetch is what retains it instead.
            val transport = FakeProposalTransport()
            transport.enqueueActive(emptyList())
            transport.enqueueHistory(emptyList())
            val gate = transport.armFetchActiveGate()
            val hints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val repository = SupabaseProposalRepository(backgroundScope, transport, hints)
            val activeValues = collectValues(repository.observeActive())
            runCurrent()

            // The first fetchActive() call has started (count bumped) and is suspended on the
            // gate — this is the mid-refetch window F10 is about. fetchHistory() hasn't run yet
            // either: refetch() awaits fetchActive() before calling it.
            assertEquals(1, transport.fetchActiveCount)
            assertEquals(0, transport.fetchHistoryCount)

            // Queue what the follow-up refetch this hint must trigger should see, then emit the
            // hint WHILE the first fetch is still suspended.
            transport.enqueueActive(listOf(proposalDto()))
            transport.enqueueHistory(emptyList())
            hints.tryEmit(Unit)
            runCurrent()

            // Still mid-first-fetch: the hint landed (forwarded into the conflated `kick`) but
            // nothing has re-run yet.
            assertEquals(1, transport.fetchActiveCount)

            // Release the gate: the first fetch completes, refetch() finishes its history call,
            // and the loop's `kick.receive()` must already hold the retained pulse — not block
            // forever waiting on a signal that already happened — so it loops straight into a
            // second refetch (NOT advanceUntilIdle — the loop never goes idle on its own, same
            // reason as the class kdoc and SupabasePairRepositoryTest).
            gate.complete(Unit)
            runCurrent()

            assertEquals(2, transport.fetchActiveCount)
            assertEquals(2, transport.fetchHistoryCount)
            assertEquals(1, activeValues.last().size)
            assertEquals(ProposalId(PROPOSAL_ID), activeValues.last().single().id)
        }

    @Test
    fun closeCancelsTheRefetchLoop() =
        runTest {
            val transport = FakeProposalTransport()
            transport.enqueueActive(emptyList())
            transport.enqueueHistory(emptyList())
            val hints = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val repository = SupabaseProposalRepository(backgroundScope, transport, hints)
            runCurrent()
            assertEquals(1, transport.fetchActiveCount)

            repository.close()
            runCurrent()

            val countAtClose = transport.fetchActiveCount
            hints.tryEmit(Unit)
            runCurrent()

            assertEquals(countAtClose, transport.fetchActiveCount)
        }
}
