package dev.elay.ui.today.fake

import dev.elay.domain.model.NextTimeSuggestion
import dev.elay.domain.model.NextTimeSuggestionResult
import dev.elay.domain.model.RecordOutcomeResult
import dev.elay.domain.model.SessionOutcomeKind
import dev.elay.domain.repository.OutcomeRepository

/** One recorded [OutcomeRepository.recordOutcome] call (test assertion helper). */
data class RecordOutcomeCall(
    val operationId: String,
    val timeBlockId: String,
    val outcome: SessionOutcomeKind,
    val actualMinutes: Int?,
)

/** One recorded [OutcomeRepository.nextTimeSuggestion] call. */
data class NextTimeSuggestionCall(
    val taskId: String?,
    val titleKey: String?,
)

/**
 * In-memory [OutcomeRepository] for [dev.elay.ui.today.TodayViewModel] previews/tests (mirrors
 * [dev.elay.ui.together.proposal.fake.FakeProposalRepository]/
 * [dev.elay.ui.together.proposal.fake.FakeAvailabilityRepository]'s role). Records every call
 * ([recordCalls]/[suggestionCalls]) so a ViewModel test can assert e.g. "recordOutcome called with
 * ran_long and actualMinutes = planned+15" directly, plus test hooks ([nextRecordResult]/
 * [nextSuggestionResult], consumed on use) to script a specific result — including
 * [RecordOutcomeResult.Failed] — for the next call of each kind.
 *
 * Unscripted [nextTimeSuggestion] defaults to a `Loaded` result with `suggestedMinutes = null,
 * sampleSize = 0` (the server's own honest "not enough data yet" answer — contract: "emitted only
 * at `sample_size >= 2`, else null") rather than a `not_scripted` failure, mirroring
 * [dev.elay.ui.together.proposal.fake.FakeAvailabilityRepository]'s "a bare
 * preview/composer-open shouldn't render a calm error just because nothing was seeded" convention.
 * Unscripted [recordOutcome] (a deliberate user action, never fired just by mounting a screen)
 * defaults to a `not_scripted` [RecordOutcomeResult.Failed], the same "a write must be explicitly
 * scripted" convention every other fake in the house uses.
 */
class FakeOutcomeRepository : OutcomeRepository {
    var nextRecordResult: RecordOutcomeResult? = null
    var nextSuggestionResult: NextTimeSuggestionResult? = null

    val recordCalls: MutableList<RecordOutcomeCall> = mutableListOf()
    val suggestionCalls: MutableList<NextTimeSuggestionCall> = mutableListOf()

    override suspend fun recordOutcome(
        operationId: String,
        timeBlockId: String,
        outcome: SessionOutcomeKind,
        actualMinutes: Int?,
    ): RecordOutcomeResult {
        recordCalls += RecordOutcomeCall(operationId, timeBlockId, outcome, actualMinutes)
        return nextRecordResult.also { nextRecordResult = null } ?: notScripted()
    }

    override suspend fun nextTimeSuggestion(
        taskId: String?,
        titleKey: String?,
    ): NextTimeSuggestionResult {
        suggestionCalls += NextTimeSuggestionCall(taskId, titleKey)
        return nextSuggestionResult.also { nextSuggestionResult = null }
            ?: NextTimeSuggestionResult.Loaded(
                NextTimeSuggestion(suggestedMinutes = null, sampleSize = 0, basis = null),
            )
    }

    private fun notScripted(): RecordOutcomeResult.Failed =
        RecordOutcomeResult.Failed("not_scripted", retryable = false)
}

/** Single shared instance so a bare Today preview/screen has stable, calm default data (no
 * recorded outcomes, no suggestion) — matches
 * [dev.elay.ui.together.proposal.fake.sharedFakeProposalRepository]'s role. */
val sharedFakeOutcomeRepository: OutcomeRepository by lazy { FakeOutcomeRepository() }
