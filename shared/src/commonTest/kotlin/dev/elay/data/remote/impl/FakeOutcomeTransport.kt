package dev.elay.data.remote.impl

import dev.elay.data.remote.dto.NextTimeSuggestionDto
import dev.elay.data.remote.dto.SessionOutcomeRpcEnvelopeDto

/**
 * Scripted, in-memory [OutcomeTransport] — no [io.github.jan.supabase.SupabaseClient], no
 * network. Like [FakeAvailabilityTransport] there is no queue-then-repeat-last read loop to
 * drive: every method here is a plain on-demand suspend call (contract: no refetch loop for this
 * surface), so each result/error is simply set once per test.
 */
internal class FakeOutcomeTransport : OutcomeTransport {
    var recordResult: SessionOutcomeRpcEnvelopeDto? = null
    var recordError: Throwable? = null
    var lastRecordCall: RecordCall? = null
        private set

    var suggestionResult: NextTimeSuggestionDto? = null
    var suggestionError: Throwable? = null
    var lastSuggestionCall: SuggestionCall? = null
        private set

    data class RecordCall(
        val operationId: String,
        val timeBlockId: String,
        val outcome: String,
        val actualMinutes: Int?,
    )

    data class SuggestionCall(
        val taskId: String?,
        val titleKey: String?,
    )

    override suspend fun recordOutcome(
        operationId: String,
        timeBlockId: String,
        outcome: String,
        actualMinutes: Int?,
    ): SessionOutcomeRpcEnvelopeDto {
        lastRecordCall = RecordCall(operationId, timeBlockId, outcome, actualMinutes)
        recordError?.let { throw it }
        return recordResult ?: error("recordResult not scripted for this test")
    }

    override suspend fun nextTimeSuggestion(
        taskId: String?,
        titleKey: String?,
    ): NextTimeSuggestionDto {
        lastSuggestionCall = SuggestionCall(taskId, titleKey)
        suggestionError?.let { throw it }
        return suggestionResult ?: error("suggestionResult not scripted for this test")
    }
}
