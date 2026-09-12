package dev.elay.data.remote.dto

import dev.elay.domain.model.SessionOutcomeKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** See [dev.elay.data.remote.dto.GoalDtoTest] for why `encodeDefaults = true` is required here. */
private val wireJson = Json { encodeDefaults = true }

/**
 * No `contracts/fixtures/session-outcome-*.json` exists in this seat's grant (A7 owns that path —
 * contracts/stage5-retention-hardening.md seat grants table) — these fixtures are hand-authored
 * from the frozen contract's wire-shape prose the same way [AvailabilityDtosTest]'s were for
 * Stage 4. The lead diffs both against A7's real RPC output at merge; any mismatch is fixed on
 * this seat's side unless the SQL deviates from the contract.
 */
private const val RAN_LONG_OUTCOME_ROW_FIXTURE = """
{
  "id": "so-1",
  "time_block_id": "tb-1",
  "outcome": "ran_long",
  "planned_minutes": 60,
  "actual_minutes": 80,
  "delta_minutes": 20,
  "version": 1
}
"""

/** `didnt_happen`/`rescheduled` rows carry neither `actual_minutes` nor `delta_minutes` (contract's
 * adopted schema: "`actual_minutes` null-iff rule"). */
private const val DIDNT_HAPPEN_OUTCOME_ROW_FIXTURE = """
{
  "id": "so-2",
  "time_block_id": "tb-2",
  "outcome": "didnt_happen",
  "planned_minutes": 45,
  "actual_minutes": null,
  "delta_minutes": null,
  "version": 1
}
"""

private const val APPLIED_RECORD_ENVELOPE_FIXTURE = """
{
  "outcome": "applied",
  "action": "record_session_outcome",
  "session_outcome": {
    "id": "so-1",
    "time_block_id": "tb-1",
    "outcome": "ran_long",
    "planned_minutes": 60,
    "actual_minutes": 80,
    "delta_minutes": 20,
    "version": 1
  }
}
"""

/** UNVERIFIED shape (this seat never observed a live elapsed-guard/cross-action envelope for this
 * RPC — see [SessionOutcomeRpcEnvelopeDto]'s kdoc) — modeled on [ProposalRpcEnvelopeDto]'s conflict
 * envelope having no `action`/payload key, per that same pre-gate finding. */
private const val NOT_ELAPSED_GUARD_ENVELOPE_FIXTURE = """
{
  "outcome": "not_elapsed"
}
"""

private const val LOADED_SUGGESTION_FIXTURE = """
{
  "suggested_minutes": 80,
  "sample_size": 5,
  "basis": "median_last_5"
}
"""

/** Below the contract's honesty threshold ("emitted only at `sample_size >= 2`, else null"). */
private const val SUB_THRESHOLD_SUGGESTION_FIXTURE = """
{
  "suggested_minutes": null,
  "sample_size": 1,
  "basis": null
}
"""

class SessionOutcomeDtosTest {
    /** Strips JsonNull members before comparing — mirrors [AvailabilityDtosTest]'s helper: the real
     * RPC output may carry explicit nulls for a `didnt_happen`/`rescheduled` row's absent
     * actual/delta minutes, our decode-only production path never re-encodes, and null-presence is
     * not semantic here. */
    private fun stripNulls(e: JsonElement): JsonElement =
        when (e) {
            is JsonObject -> JsonObject(e.filterValues { it != JsonNull }.mapValues { (_, v) -> stripNulls(v) })
            is JsonArray -> JsonArray(e.map { stripNulls(it) })
            else -> e
        }

    @Test
    fun ranLongOutcomeRowRoundTripsFixtureByteComparablyAndMapsToDomain() {
        val decoded = Json.decodeFromString(SessionOutcomeDto.serializer(), RAN_LONG_OUTCOME_ROW_FIXTURE)
        val reencoded = wireJson.encodeToString(SessionOutcomeDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(RAN_LONG_OUTCOME_ROW_FIXTURE), Json.parseToJsonElement(reencoded))

        val domain = decoded.toDomain()
        assertEquals("so-1", domain.id.value)
        assertEquals("tb-1", domain.timeBlockId.value)
        assertEquals(SessionOutcomeKind.RanLong, domain.outcome)
        assertEquals(60, domain.plannedMinutes)
        assertEquals(80, domain.actualMinutes)
        assertEquals(20, domain.deltaMinutes)
        assertEquals(1L, domain.version)
    }

    @Test
    fun didntHappenOutcomeRowDecodesWithNullActualAndDeltaMinutes() {
        val decoded = Json.decodeFromString(SessionOutcomeDto.serializer(), DIDNT_HAPPEN_OUTCOME_ROW_FIXTURE)
        val reencoded = wireJson.encodeToString(SessionOutcomeDto.serializer(), decoded)
        assertEquals(
            stripNulls(Json.parseToJsonElement(DIDNT_HAPPEN_OUTCOME_ROW_FIXTURE)),
            stripNulls(Json.parseToJsonElement(reencoded)),
        )

        val domain = decoded.toDomain()
        assertEquals(SessionOutcomeKind.DidntHappen, domain.outcome)
        assertNull(domain.actualMinutes)
        assertNull(domain.deltaMinutes)
    }

    /** The central drift guardrail for this seat's grant: an unrecognized `outcome` wire string
     * must NOT silently coerce to some default — [SessionOutcomeDto.toDomain] throws, so a caller
     * that fails to run it inside a catch (as [dev.elay.data.remote.impl.SupabaseOutcomeRepository]
     * does) finds out immediately rather than persisting a wrong domain value. */
    @Test
    fun unrecognizedOutcomeStringThrowsRatherThanSilentlyDefaulting() {
        val drifted = RAN_LONG_OUTCOME_ROW_FIXTURE.replace("\"ran_long\"", "\"interrupted\"")
        val decoded = Json.decodeFromString(SessionOutcomeDto.serializer(), drifted)
        assertFailsWith<IllegalArgumentException> { decoded.toDomain() }
    }

    @Test
    fun everySessionOutcomeKindRoundTripsItsWireString() {
        for (kind in SessionOutcomeKind.entries) {
            assertEquals(kind, SessionOutcomeKind.fromWire(kind.wire))
        }
    }

    @Test
    fun appliedRecordEnvelopeRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(SessionOutcomeRpcEnvelopeDto.serializer(), APPLIED_RECORD_ENVELOPE_FIXTURE)
        val reencoded = wireJson.encodeToString(SessionOutcomeRpcEnvelopeDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(APPLIED_RECORD_ENVELOPE_FIXTURE), Json.parseToJsonElement(reencoded))
        assertEquals("applied", decoded.outcome)
        assertEquals("record_session_outcome", decoded.action)
        assertEquals("so-1", decoded.sessionOutcome?.id)
    }

    @Test
    fun notElapsedGuardEnvelopeDecodesWithoutActionOrPayload() {
        val decoded =
            Json.decodeFromString(
                SessionOutcomeRpcEnvelopeDto.serializer(),
                NOT_ELAPSED_GUARD_ENVELOPE_FIXTURE,
            )
        assertEquals("not_elapsed", decoded.outcome)
        assertNull(decoded.action)
        assertNull(decoded.sessionOutcome)
    }

    @Test
    fun loadedSuggestionRoundTripsFixtureByteComparablyAndMapsToDomain() {
        val decoded = Json.decodeFromString(NextTimeSuggestionDto.serializer(), LOADED_SUGGESTION_FIXTURE)
        val reencoded = wireJson.encodeToString(NextTimeSuggestionDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(LOADED_SUGGESTION_FIXTURE), Json.parseToJsonElement(reencoded))

        val domain = decoded.toDomain()
        assertEquals(80, domain.suggestedMinutes)
        assertEquals(5, domain.sampleSize)
        assertEquals("median_last_5", domain.basis)
    }

    @Test
    fun subThresholdSuggestionDecodesWithNullSuggestedMinutesAndBasis() {
        val decoded = Json.decodeFromString(NextTimeSuggestionDto.serializer(), SUB_THRESHOLD_SUGGESTION_FIXTURE)
        val reencoded = wireJson.encodeToString(NextTimeSuggestionDto.serializer(), decoded)
        assertEquals(
            stripNulls(Json.parseToJsonElement(SUB_THRESHOLD_SUGGESTION_FIXTURE)),
            stripNulls(Json.parseToJsonElement(reencoded)),
        )

        val domain = decoded.toDomain()
        assertNull(domain.suggestedMinutes)
        assertEquals(1, domain.sampleSize)
        assertNull(domain.basis)
    }
}
