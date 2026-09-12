package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.NextTimeSuggestionDto
import dev.elay.data.remote.dto.SessionOutcomeDto
import dev.elay.data.remote.dto.SessionOutcomeRpcEnvelopeDto
import dev.elay.domain.model.NextTimeSuggestionResult
import dev.elay.domain.model.RecordOutcomeResult
import dev.elay.domain.model.SessionOutcomeKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * State-machine tests for [SupabaseOutcomeRepository] against [FakeOutcomeTransport] — no
 * `SupabaseClient`, no network. Like [SupabaseAvailabilityRepositoryTest] there is no refetch
 * loop/`runCurrent()` dance: every method here is a single on-demand suspend call (contract: no
 * new realtime events for this surface), so plain `runTest { }` suffices.
 */
class SupabaseOutcomeRepositoryTest {
    @Test
    fun recordOutcomeAppliedMapsSessionOutcomeIncludingDeltaAndForwardsWireOutcome() =
        runTest {
            val transport = FakeOutcomeTransport()
            transport.recordResult =
                SessionOutcomeRpcEnvelopeDto(
                    outcome = "applied",
                    action = "record_session_outcome",
                    sessionOutcome =
                        SessionOutcomeDto(
                            id = "so-1",
                            timeBlockId = "tb-1",
                            outcome = "ran_long",
                            plannedMinutes = 60,
                            actualMinutes = 80,
                            deltaMinutes = 20,
                            version = 1,
                        ),
                )
            val repository = SupabaseOutcomeRepository(transport)

            val result =
                repository.recordOutcome(
                    operationId = "op-1",
                    timeBlockId = "tb-1",
                    outcome = SessionOutcomeKind.RanLong,
                    actualMinutes = 80,
                )

            assertTrue(result is RecordOutcomeResult.Applied)
            val applied = (result as RecordOutcomeResult.Applied).outcome
            assertEquals("so-1", applied.id.value)
            assertEquals("tb-1", applied.timeBlockId.value)
            assertEquals(SessionOutcomeKind.RanLong, applied.outcome)
            assertEquals(60, applied.plannedMinutes)
            assertEquals(80, applied.actualMinutes)
            assertEquals(20, applied.deltaMinutes)
            assertEquals(1L, applied.version)

            // The transport must receive the resolved wire string, not the enum (contract's
            // p_outcome parameter is a plain string) — pinned via the fake's recorded call.
            val call = transport.lastRecordCall
            assertEquals("op-1", call?.operationId)
            assertEquals("tb-1", call?.timeBlockId)
            assertEquals("ran_long", call?.outcome)
            assertEquals(80, call?.actualMinutes)
        }

    @Test
    fun recordOutcomeDidntHappenAppliedCarriesNullActualAndDeltaMinutes() =
        runTest {
            val transport = FakeOutcomeTransport()
            transport.recordResult =
                SessionOutcomeRpcEnvelopeDto(
                    outcome = "applied",
                    action = "record_session_outcome",
                    sessionOutcome =
                        SessionOutcomeDto(
                            id = "so-2",
                            timeBlockId = "tb-2",
                            outcome = "didnt_happen",
                            plannedMinutes = 45,
                            actualMinutes = null,
                            deltaMinutes = null,
                            version = 1,
                        ),
                )
            val repository = SupabaseOutcomeRepository(transport)

            val result =
                repository.recordOutcome(
                    operationId = "op-2",
                    timeBlockId = "tb-2",
                    outcome = SessionOutcomeKind.DidntHappen,
                    actualMinutes = null,
                )

            assertTrue(result is RecordOutcomeResult.Applied)
            val applied = (result as RecordOutcomeResult.Applied).outcome
            assertNull(applied.actualMinutes)
            assertNull(applied.deltaMinutes)
            assertNull(transport.lastRecordCall?.actualMinutes)
        }

    /** The elapsed-only future-block guard and the cross-action guard (contract: "elapsed-only:
     * future block → 22023… cross-action guard") are both terminal — there is no `Conflict`
     * variant on [RecordOutcomeResult] (see its kdoc). A non-`applied` soft envelope outcome
     * (UNVERIFIED exact wire string — see [SessionOutcomeRpcEnvelopeDto]'s kdoc) folds to a
     * terminal, non-retryable [RecordOutcomeResult.Failed] the same way
     * [dev.elay.domain.availability.ExternalBusyResult.Failed]'s cross-action case does. */
    @Test
    fun recordOutcomeElapsedGuardOutcomeIsTerminalFailedNotRetryable() =
        runTest {
            val transport = FakeOutcomeTransport()
            transport.recordResult = SessionOutcomeRpcEnvelopeDto(outcome = "not_elapsed")
            val repository = SupabaseOutcomeRepository(transport)

            val result =
                repository.recordOutcome(
                    operationId = "op-3",
                    timeBlockId = "tb-future",
                    outcome = SessionOutcomeKind.RanLong,
                    actualMinutes = 90,
                )

            assertEquals(RecordOutcomeResult.Failed("unexpected_outcome:not_elapsed", retryable = false), result)
        }

    @Test
    fun recordOutcomeNetworkFailureSurfacesAsFailedRetryable() =
        runTest {
            val transport = FakeOutcomeTransport()
            transport.recordError = RuntimeException("boom")
            val repository = SupabaseOutcomeRepository(transport)

            val result =
                repository.recordOutcome(
                    operationId = "op-4",
                    timeBlockId = "tb-4",
                    outcome = SessionOutcomeKind.FinishedEarly,
                    actualMinutes = 45,
                )

            assertTrue(result is RecordOutcomeResult.Failed)
            val failed = result as RecordOutcomeResult.Failed
            assertEquals("boom", failed.reason)
            assertTrue(failed.retryable)
        }

    /** Pins this seat's central drift guardrail at the repository boundary (not just the DTO
     * layer, see `SessionOutcomeDtosTest`): an unrecognized `outcome` string inside an otherwise
     * `applied` envelope must degrade the whole call to [RecordOutcomeResult.Failed] — never crash
     * — because [dev.elay.data.remote.dto.toDomain]'s `requireNotNull` throws and that throw runs
     * INSIDE this repository's catch (F12 lesson). */
    @Test
    fun recordOutcomeUnknownOutcomeStringInsideAppliedEnvelopeFoldsToFailedRatherThanCrashing() =
        runTest {
            val transport = FakeOutcomeTransport()
            transport.recordResult =
                SessionOutcomeRpcEnvelopeDto(
                    outcome = "applied",
                    action = "record_session_outcome",
                    sessionOutcome =
                        SessionOutcomeDto(
                            id = "so-5",
                            timeBlockId = "tb-5",
                            outcome = "interrupted",
                            plannedMinutes = 30,
                            actualMinutes = 10,
                            deltaMinutes = -20,
                            version = 1,
                        ),
                )
            val repository = SupabaseOutcomeRepository(transport)

            val result =
                repository.recordOutcome(
                    operationId = "op-5",
                    timeBlockId = "tb-5",
                    outcome = SessionOutcomeKind.RanLong,
                    actualMinutes = 10,
                )

            assertTrue(result is RecordOutcomeResult.Failed)
        }

    @Test
    fun recordOutcomeMalformedAppliedEnvelopeWithNoPayloadFoldsToFailedNotRetryable() =
        runTest {
            val transport = FakeOutcomeTransport()
            transport.recordResult = SessionOutcomeRpcEnvelopeDto(outcome = "applied", action = null)
            val repository = SupabaseOutcomeRepository(transport)

            val result =
                repository.recordOutcome(
                    operationId = "op-6",
                    timeBlockId = "tb-6",
                    outcome = SessionOutcomeKind.Rescheduled,
                    actualMinutes = null,
                )

            assertEquals(RecordOutcomeResult.Failed("malformed_applied_response", retryable = false), result)
        }

    @Test
    fun nextTimeSuggestionLoadedMapsToDomainAndForwardsQueryKeys() =
        runTest {
            val transport = FakeOutcomeTransport()
            transport.suggestionResult =
                NextTimeSuggestionDto(suggestedMinutes = 80, sampleSize = 5, basis = "median_last_5")
            val repository = SupabaseOutcomeRepository(transport)

            val result = repository.nextTimeSuggestion(taskId = "task-1", titleKey = "study-session")

            assertTrue(result is NextTimeSuggestionResult.Loaded)
            val suggestion = (result as NextTimeSuggestionResult.Loaded).suggestion
            assertEquals(80, suggestion.suggestedMinutes)
            assertEquals(5, suggestion.sampleSize)
            assertEquals("median_last_5", suggestion.basis)
            assertEquals("task-1", transport.lastSuggestionCall?.taskId)
            assertEquals("study-session", transport.lastSuggestionCall?.titleKey)
        }

    /** The contract's honesty threshold ("emitted only at `sample_size >= 2`, else null") is a
     * plain pass-through read, not a Failed — a sub-threshold answer is still [Loaded]. */
    @Test
    fun nextTimeSuggestionSubThresholdSampleSizeIsLoadedWithNullSuggestion() =
        runTest {
            val transport = FakeOutcomeTransport()
            transport.suggestionResult = NextTimeSuggestionDto(suggestedMinutes = null, sampleSize = 1, basis = null)
            val repository = SupabaseOutcomeRepository(transport)

            val result = repository.nextTimeSuggestion()

            assertTrue(result is NextTimeSuggestionResult.Loaded)
            val suggestion = (result as NextTimeSuggestionResult.Loaded).suggestion
            assertNull(suggestion.suggestedMinutes)
            assertEquals(1, suggestion.sampleSize)
            assertNull(suggestion.basis)
            assertFalse(suggestion.sampleSize >= 2)
        }

    @Test
    fun nextTimeSuggestionNetworkFailureSurfacesAsFailedRetryable() =
        runTest {
            val transport = FakeOutcomeTransport()
            transport.suggestionError = RuntimeException("boom")
            val repository = SupabaseOutcomeRepository(transport)

            val result = repository.nextTimeSuggestion()

            assertTrue(result is NextTimeSuggestionResult.Failed)
            val failed = result as NextTimeSuggestionResult.Failed
            assertEquals("boom", failed.reason)
            assertTrue(failed.retryable)
        }
}
