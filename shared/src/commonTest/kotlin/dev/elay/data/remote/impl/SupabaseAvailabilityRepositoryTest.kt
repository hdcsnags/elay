package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.AvailabilitySourceDto
import dev.elay.data.remote.dto.ConflictHintDto
import dev.elay.data.remote.dto.ExternalBusyRpcEnvelopeDto
import dev.elay.domain.availability.AvailabilitySourcesResult
import dev.elay.domain.availability.Certainty
import dev.elay.domain.availability.ExternalBusyResult
import dev.elay.domain.availability.SelfConflictHintsResult
import dev.elay.domain.availability.SourceTag
import dev.elay.domain.model.Candidate
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun candidate(index: Int = 0) =
    Candidate(
        index = index,
        startsAt = Instant.parse("2026-09-20T23:00:00Z"),
        endsAt = Instant.parse("2026-09-21T00:00:00Z"),
        durationMinutes = 60,
    )

/**
 * State-machine tests for [SupabaseAvailabilityRepository] against [FakeAvailabilityTransport] —
 * no `SupabaseClient`, no network. Unlike [SupabaseProposalRepositoryTest] there is no refetch
 * loop/`runCurrent()` dance: every method here is a single on-demand suspend call (contract: no
 * new realtime events for this surface), so plain `runTest { }` suffices.
 */
class SupabaseAvailabilityRepositoryTest {
    @Test
    fun mySourcesAppliedMapsEverySourceToDomain() =
        runTest {
            val transport = FakeAvailabilityTransport()
            transport.mySourcesResult =
                listOf(
                    AvailabilitySourceDto(
                        sourceTag = "manual",
                        status = "connected",
                        lastSyncedAt = "2026-09-12T19:00:00Z",
                        freshnessTtlMinutes = 43200,
                        windowStart = "2026-09-11T00:00:00Z",
                        windowEnd = "2026-10-17T00:00:00Z",
                    ),
                    AvailabilitySourceDto(
                        sourceTag = "google",
                        status = "not_connected",
                        freshnessTtlMinutes = 360,
                    ),
                )
            val repository = SupabaseAvailabilityRepository(transport)

            val result = repository.mySources()

            assertTrue(result is AvailabilitySourcesResult.Loaded)
            val sources = (result as AvailabilitySourcesResult.Loaded).sources
            assertEquals(2, sources.size)
            assertEquals(SourceTag.Manual, sources[0].sourceTag)
            assertEquals(SourceTag.Google, sources[1].sourceTag)
            assertEquals(1, transport.mySourcesCallCount)
        }

    @Test
    fun mySourcesNetworkFailureSurfacesAsFailedRetryable() =
        runTest {
            val transport = FakeAvailabilityTransport()
            transport.mySourcesError = RuntimeException("boom")
            val repository = SupabaseAvailabilityRepository(transport)

            val result = repository.mySources()

            assertTrue(result is AvailabilitySourcesResult.Failed)
            val failed = result as AvailabilitySourcesResult.Failed
            assertEquals("boom", failed.reason)
            assertTrue(failed.retryable)
        }

    @Test
    fun upsertManualBusyAppliedReturnsApplied() =
        runTest {
            val transport = FakeAvailabilityTransport()
            transport.upsertResult = ExternalBusyRpcEnvelopeDto(outcome = "applied", action = "upsert_external_busy")
            val repository = SupabaseAvailabilityRepository(transport)

            val result =
                repository.upsertManualBusy(
                    operationId = "op-busy-1",
                    busyId = "busy-1",
                    startsAt = Instant.parse("2026-09-20T22:00:00Z"),
                    endsAt = Instant.parse("2026-09-20T23:30:00Z"),
                    originZoneId = "America/Vancouver",
                )

            assertEquals(ExternalBusyResult.Applied, result)
            // The transport must never receive a client-supplied source tag (contract: hard-coded
            // server-side) — pinned by [FakeAvailabilityTransport.upsertManualBusy]'s signature
            // itself having no such parameter; here we pin the rest of the params reach the wire.
            val call = transport.lastUpsertCall
            assertEquals("op-busy-1", call?.operationId)
            assertEquals("busy-1", call?.busyId)
            assertEquals("2026-09-20T22:00:00Z", call?.startsAtUtc)
            assertEquals("2026-09-20T23:30:00Z", call?.endsAtUtc)
            assertEquals("America/Vancouver", call?.originZoneId)
        }

    @Test
    fun upsertManualBusyNetworkFailureSurfacesAsFailedRetryable() =
        runTest {
            val transport = FakeAvailabilityTransport()
            transport.upsertError = RuntimeException("boom")
            val repository = SupabaseAvailabilityRepository(transport)

            val result =
                repository.upsertManualBusy(
                    operationId = "op-busy-2",
                    busyId = "busy-2",
                    startsAt = Instant.parse("2026-09-20T22:00:00Z"),
                    endsAt = Instant.parse("2026-09-20T23:30:00Z"),
                    originZoneId = "America/Vancouver",
                )

            assertTrue(result is ExternalBusyResult.Failed)
            val failed = result as ExternalBusyResult.Failed
            assertEquals("boom", failed.reason)
            assertTrue(failed.retryable)
        }

    /** A cross-action/unexpected outcome (contract's ledger 22023 guard, or any other non-
     * `applied` outcome) is a terminal [ExternalBusyResult.Failed] — there is no `Conflict`
     * variant on this sealed type (contract: neither manual-busy RPC describes a non-terminal
     * conflict shape). */
    @Test
    fun upsertManualBusyUnexpectedOutcomeIsTerminalFailedNotRetryable() =
        runTest {
            val transport = FakeAvailabilityTransport()
            transport.upsertResult = ExternalBusyRpcEnvelopeDto(outcome = "conflict")
            val repository = SupabaseAvailabilityRepository(transport)

            val result =
                repository.upsertManualBusy(
                    operationId = "op-busy-3",
                    busyId = "busy-3",
                    startsAt = Instant.parse("2026-09-20T22:00:00Z"),
                    endsAt = Instant.parse("2026-09-20T23:30:00Z"),
                    originZoneId = "America/Vancouver",
                )

            assertEquals(ExternalBusyResult.Failed("unexpected_outcome:conflict", retryable = false), result)
        }

    @Test
    fun deleteManualBusyAppliedReturnsAppliedAndPassesBusyId() =
        runTest {
            val transport = FakeAvailabilityTransport()
            transport.deleteResult = ExternalBusyRpcEnvelopeDto(outcome = "applied", action = "delete_external_busy")
            val repository = SupabaseAvailabilityRepository(transport)

            val result = repository.deleteManualBusy(operationId = "op-busy-4", busyId = "busy-1")

            assertEquals(ExternalBusyResult.Applied, result)
            assertEquals("busy-1", transport.lastDeleteCall?.busyId)
        }

    @Test
    fun deleteManualBusyNetworkFailureSurfacesAsFailedRetryable() =
        runTest {
            val transport = FakeAvailabilityTransport()
            transport.deleteError = RuntimeException("boom")
            val repository = SupabaseAvailabilityRepository(transport)

            val result = repository.deleteManualBusy(operationId = "op-busy-5", busyId = "busy-2")

            assertTrue(result is ExternalBusyResult.Failed)
            assertTrue((result as ExternalBusyResult.Failed).retryable)
        }

    @Test
    fun selfConflictHintsAppliedMapsEverySnapshotAndForwardsCandidates() =
        runTest {
            val transport = FakeAvailabilityTransport()
            transport.selfConflictHintsResult =
                listOf(
                    ConflictHintDto(candidateIdx = 0, hasConflict = true, certainty = "busy"),
                    ConflictHintDto(candidateIdx = 1, hasConflict = false, certainty = "free_per_calendar"),
                )
            val repository = SupabaseAvailabilityRepository(transport)
            val candidates = listOf(candidate(0), candidate(1))

            val result = repository.selfConflictHints(candidates)

            assertTrue(result is SelfConflictHintsResult.Loaded)
            val snapshots = (result as SelfConflictHintsResult.Loaded).snapshots
            assertEquals(2, snapshots.size)
            assertEquals(Certainty.Busy, snapshots[0].certainty)
            assertEquals(Certainty.FreePerCalendar, snapshots[1].certainty)
            assertEquals(candidates, transport.lastSelfConflictHintsCandidates)
        }

    /** Pins this seat's central drift guardrail at the repository boundary (not just the DTO
     * layer, see `AvailabilityDtosTest`): an unrecognized `certainty` string must map to
     * [Certainty.Unknown] and the whole call must still surface as [SelfConflictHintsResult.Loaded]
     * — never crash the composer/responder mid-decode. */
    @Test
    fun selfConflictHintsUnknownCertaintyStringMapsToUnknownWithoutCrashing() =
        runTest {
            val transport = FakeAvailabilityTransport()
            transport.selfConflictHintsResult =
                listOf(ConflictHintDto(candidateIdx = 0, hasConflict = false, certainty = "some_future_rung"))
            val repository = SupabaseAvailabilityRepository(transport)

            val result = repository.selfConflictHints(listOf(candidate()))

            assertTrue(result is SelfConflictHintsResult.Loaded)
            val snapshot = (result as SelfConflictHintsResult.Loaded).snapshots.single()
            assertEquals(Certainty.Unknown, snapshot.certainty)
            assertFalse(snapshot.hasConflict)
        }

    @Test
    fun selfConflictHintsNetworkFailureSurfacesAsFailedRetryable() =
        runTest {
            val transport = FakeAvailabilityTransport()
            transport.selfConflictHintsError = RuntimeException("boom")
            val repository = SupabaseAvailabilityRepository(transport)

            val result = repository.selfConflictHints(listOf(candidate()))

            assertTrue(result is SelfConflictHintsResult.Failed)
            val failed = result as SelfConflictHintsResult.Failed
            assertEquals("boom", failed.reason)
            assertTrue(failed.retryable)
        }
}
