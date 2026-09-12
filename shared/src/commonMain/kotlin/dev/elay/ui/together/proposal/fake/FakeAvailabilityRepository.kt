package dev.elay.ui.together.proposal.fake

import dev.elay.domain.availability.AvailabilityRepository
import dev.elay.domain.availability.AvailabilitySourcesResult
import dev.elay.domain.availability.ExternalBusyResult
import dev.elay.domain.availability.SelfConflictHintsResult
import dev.elay.domain.model.Candidate
import kotlinx.datetime.Instant

/** One recorded [AvailabilityRepository.upsertManualBusy] call (test assertion helper). */
data class UpsertManualBusyCall(
    val operationId: String,
    val busyId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val originZoneId: String,
    val label: String? = null,
)

/** One recorded [AvailabilityRepository.deleteManualBusy] call. */
data class DeleteManualBusyCall(
    val operationId: String,
    val busyId: String,
)

/**
 * In-memory [AvailabilityRepository] for Stage-4 previews/tests (mirrors
 * [dev.elay.ui.together.proposal.fake.FakeProposalRepository]'s role). Records every call
 * ([upsertCalls]/[deleteCalls]/[hintsCalls]/[sourcesCallCount]) so a ViewModel test can assert e.g.
 * "selfConflictHints called with these 2 candidates", plus test hooks ([nextUpsertResult] and
 * friends, consumed on use) to script a specific result — including
 * [ExternalBusyResult.Failed]/[SelfConflictHintsResult.Failed] — for the next call of each kind.
 *
 * Unscripted reads ([mySources]/[selfConflictHints]) default to an empty-but-successful `Loaded`
 * result rather than a `not_scripted` failure — a bare preview/composer-open shouldn't render a
 * calm error just because nothing was seeded, mirroring how [FakeProposalRepository]'s
 * `observeActive`/`observeHistory` default to empty lists rather than an error state. Unscripted
 * *writes* ([upsertManualBusy]/[deleteManualBusy]) do default to a `not_scripted` [ExternalBusyResult.Failed]
 * — the same "a mutation must be explicitly scripted" convention [FakeProposalRepository.create] uses
 * — since a write is always a deliberate user action (never fired just by mounting a screen).
 */
class FakeAvailabilityRepository : AvailabilityRepository {
    var nextSourcesResult: AvailabilitySourcesResult? = null
    var nextUpsertResult: ExternalBusyResult? = null
    var nextDeleteResult: ExternalBusyResult? = null
    var nextHintsResult: SelfConflictHintsResult? = null

    /** Scripts more than one [selfConflictHints] call in the same test without an interleaved
     * `runCurrent()` between them (e.g. asserting a stale in-flight response is discarded when two
     * edits fire in quick succession) — consumed front-to-back, ahead of [nextHintsResult], which
     * stays the simple single-call case every other test uses. */
    val hintsResultQueue: MutableList<SelfConflictHintsResult> = mutableListOf()

    val upsertCalls: MutableList<UpsertManualBusyCall> = mutableListOf()
    val deleteCalls: MutableList<DeleteManualBusyCall> = mutableListOf()
    val hintsCalls: MutableList<List<Candidate>> = mutableListOf()

    var sourcesCallCount: Int = 0
        private set

    override suspend fun mySources(): AvailabilitySourcesResult {
        sourcesCallCount++
        return nextSourcesResult.also { nextSourcesResult = null } ?: AvailabilitySourcesResult.Loaded(emptyList())
    }

    override suspend fun upsertManualBusy(
        operationId: String,
        busyId: String,
        startsAt: Instant,
        endsAt: Instant,
        originZoneId: String,
        label: String?,
    ): ExternalBusyResult {
        upsertCalls += UpsertManualBusyCall(operationId, busyId, startsAt, endsAt, originZoneId, label)
        return nextUpsertResult.also { nextUpsertResult = null } ?: notScriptedBusyResult()
    }

    override suspend fun deleteManualBusy(
        operationId: String,
        busyId: String,
    ): ExternalBusyResult {
        deleteCalls += DeleteManualBusyCall(operationId, busyId)
        return nextDeleteResult.also { nextDeleteResult = null } ?: notScriptedBusyResult()
    }

    override suspend fun selfConflictHints(candidates: List<Candidate>): SelfConflictHintsResult {
        hintsCalls += candidates
        if (hintsResultQueue.isNotEmpty()) return hintsResultQueue.removeAt(0)
        return nextHintsResult.also { nextHintsResult = null } ?: SelfConflictHintsResult.Loaded(emptyList())
    }

    private fun notScriptedBusyResult(): ExternalBusyResult.Failed =
        ExternalBusyResult.Failed("not_scripted", retryable = false)
}

/** Single shared instance so a bare composer/Plan preview has stable, calm default data (no
 * sources, no conflicts) — matches [dev.elay.ui.together.proposal.fake.sharedFakeProposalRepository]'s role. */
val sharedFakeAvailabilityRepository: AvailabilityRepository by lazy { FakeAvailabilityRepository() }
