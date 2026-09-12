package dev.elay.data.remote.dto

import dev.elay.domain.availability.Certainty
import dev.elay.domain.availability.SourceTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** See [dev.elay.data.remote.dto.GoalDtoTest] for why `encodeDefaults = true` is required here. */
private val wireJson = Json { encodeDefaults = true }

/**
 * No `contracts/fixtures/availability-*.json` exists in this seat's grant (A6 owns that path —
 * contracts/stage4-honest-availability.md seat grants table) — these fixtures are hand-authored
 * from the freeze's §A key sets the same way [ProposalDtosTest]'s were for Stage 2. The lead diffs
 * both against A6's real RPC output at merge; any mismatch is fixed on this seat's side unless the
 * SQL deviates from the contract.
 */
private const val MANUAL_SOURCE_FIXTURE = """
{
  "source_tag": "manual",
  "status": "connected",
  "last_synced_at": "2026-09-12T19:00:00Z",
  "freshness_ttl_minutes": 43200,
  "window_start": "2026-09-11T00:00:00Z",
  "window_end": "2026-10-17T00:00:00Z"
}
"""

/** A source that has never synced (contract: the deferred Google adapter's row before its first
 * `fn_sync_external_busy` run) — all three of last_synced_at/window_start/window_end absent. */
private const val NEVER_SYNCED_GOOGLE_SOURCE_FIXTURE = """
{
  "source_tag": "google",
  "status": "not_connected",
  "last_synced_at": null,
  "freshness_ttl_minutes": 360,
  "window_start": null,
  "window_end": null
}
"""

private const val BUSY_WINDOW_FIXTURE = """
{
  "starts_at_utc": "2026-09-20T22:00:00Z",
  "ends_at_utc": "2026-09-20T23:30:00Z"
}
"""

private const val BUSY_HINT_FIXTURE = """
{
  "candidate_idx": 0,
  "has_conflict": true,
  "busy_windows": [
    {
      "starts_at_utc": "2026-09-20T22:00:00Z",
      "ends_at_utc": "2026-09-20T23:30:00Z"
    }
  ],
  "certainty": "busy"
}
"""

private const val FREE_PER_ELAY_HINT_FIXTURE = """
{
  "candidate_idx": 1,
  "has_conflict": false,
  "busy_windows": [],
  "certainty": "free_per_elay"
}
"""

/** An unrecognized wire certainty — either genuine ladder drift or the contract's rate-limited
 * "opaque `certainty='unknown'`" degradation surfacing under a string this build has never seen.
 * Must decode and fall back to [Certainty.Unknown], never throw. */
private const val DRIFTED_CERTAINTY_HINT_FIXTURE = """
{
  "candidate_idx": 2,
  "has_conflict": false,
  "busy_windows": [],
  "certainty": "quantum_maybe"
}
"""

private const val UPSERT_APPLIED_ENVELOPE_FIXTURE = """
{
  "outcome": "applied",
  "action": "upsert_external_busy"
}
"""

private const val DELETE_APPLIED_ENVELOPE_FIXTURE = """
{
  "outcome": "applied",
  "action": "delete_external_busy"
}
"""

/** UNVERIFIED shape (this seat never observed a live cross-action conflict envelope for either
 * manual-busy RPC) — modeled on [ProposalRpcEnvelopeDto]'s conflict envelope having no `action`
 * key, per the same pre-gate finding that fixture reproduces. */
private const val CROSS_ACTION_CONFLICT_ENVELOPE_FIXTURE = """
{
  "outcome": "conflict"
}
"""

class AvailabilityDtosTest {
    /** Strips JsonNull members before comparing — mirrors [ProposalDtosTest]'s helper: the real
     * RPC output may carry explicit nulls for never-synced bookkeeping fields, our decode-only
     * production path never re-encodes, and null-presence is not semantic here. */
    private fun stripNulls(e: JsonElement): JsonElement =
        when (e) {
            is JsonObject -> JsonObject(e.filterValues { it != JsonNull }.mapValues { (_, v) -> stripNulls(v) })
            is JsonArray -> JsonArray(e.map { stripNulls(it) })
            else -> e
        }

    @Test
    fun manualSourceRoundTripsFixtureByteComparablyAndMapsToDomain() {
        val decoded = Json.decodeFromString(AvailabilitySourceDto.serializer(), MANUAL_SOURCE_FIXTURE)
        val reencoded = wireJson.encodeToString(AvailabilitySourceDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(MANUAL_SOURCE_FIXTURE), Json.parseToJsonElement(reencoded))

        val domain = decoded.toDomain()
        assertEquals(SourceTag.Manual, domain.sourceTag)
        assertEquals(43200, domain.freshnessTtlMinutes)
        assertEquals("2026-09-12T19:00:00Z", domain.lastSyncedAt.toString())
        assertEquals("2026-09-11T00:00:00Z", domain.windowStart.toString())
        assertEquals("2026-10-17T00:00:00Z", domain.windowEnd.toString())
    }

    @Test
    fun neverSyncedGoogleSourceDecodesWithNullBookkeepingFields() {
        val decoded = Json.decodeFromString(AvailabilitySourceDto.serializer(), NEVER_SYNCED_GOOGLE_SOURCE_FIXTURE)
        val reencoded = wireJson.encodeToString(AvailabilitySourceDto.serializer(), decoded)
        assertEquals(
            stripNulls(Json.parseToJsonElement(NEVER_SYNCED_GOOGLE_SOURCE_FIXTURE)),
            stripNulls(Json.parseToJsonElement(reencoded)),
        )

        val domain = decoded.toDomain()
        assertEquals(SourceTag.Google, domain.sourceTag)
        assertEquals(360, domain.freshnessTtlMinutes)
        assertNull(domain.lastSyncedAt)
        assertNull(domain.windowStart)
        assertNull(domain.windowEnd)
    }

    @Test
    fun sourceTagIsUnknownTolerantForAnUnrecognizedWireString() {
        val decoded =
            Json.decodeFromString(
                AvailabilitySourceDto.serializer(),
                MANUAL_SOURCE_FIXTURE.replace("\"manual\"", "\"outlook\""),
            )
        val domain = decoded.toDomain()
        assertEquals("outlook", domain.sourceTag.wire)
        assertFalse(domain.sourceTag.isKnown)
    }

    @Test
    fun busyWindowRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(BusyWindowDto.serializer(), BUSY_WINDOW_FIXTURE)
        val reencoded = wireJson.encodeToString(BusyWindowDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(BUSY_WINDOW_FIXTURE), Json.parseToJsonElement(reencoded))

        val domain = decoded.toDomain()
        assertEquals("2026-09-20T22:00:00Z", domain.startsAt.toString())
        assertEquals(decoded, domain.toDto())
    }

    @Test
    fun busyConflictHintRoundTripsFixtureByteComparablyAndMapsToDomain() {
        val decoded = Json.decodeFromString(ConflictHintDto.serializer(), BUSY_HINT_FIXTURE)
        val reencoded = wireJson.encodeToString(ConflictHintDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(BUSY_HINT_FIXTURE), Json.parseToJsonElement(reencoded))

        val domain = decoded.toDomain()
        assertEquals(0, domain.candidateIdx)
        assertTrue(domain.hasConflict)
        assertEquals(Certainty.Busy, domain.certainty)
        assertEquals(1, domain.busyWindows.size)
    }

    @Test
    fun freePerElayHintHasNoBusyWindows() {
        val decoded = Json.decodeFromString(ConflictHintDto.serializer(), FREE_PER_ELAY_HINT_FIXTURE)
        val domain = decoded.toDomain()
        assertEquals(Certainty.FreePerElay, domain.certainty)
        assertFalse(domain.hasConflict)
        assertEquals(emptyList(), domain.busyWindows)
    }

    /** The central drift guardrail for this seat's grant: an unrecognized `certainty` string must
     * decode and fall back to [Certainty.Unknown] — never throw and crash the composer/responder
     * (contract's rate-limit degradation, or any future ladder rung this build predates). */
    @Test
    fun unrecognizedCertaintyStringMapsToUnknownRatherThanThrowing() {
        val decoded = Json.decodeFromString(ConflictHintDto.serializer(), DRIFTED_CERTAINTY_HINT_FIXTURE)
        val domain = decoded.toDomain()
        assertEquals(Certainty.Unknown, domain.certainty)
    }

    @Test
    fun everyCertaintyRoundTripsItsWireString() {
        for (certainty in Certainty.entries) {
            val dto =
                ConflictHintDto(
                    candidateIdx = 0,
                    hasConflict = false,
                    busyWindows = emptyList(),
                    certainty = certainty.wire,
                )
            val json = wireJson.encodeToString(ConflictHintDto.serializer(), dto)
            val decoded = Json.decodeFromString(ConflictHintDto.serializer(), json)
            assertEquals(certainty, decoded.toDomain().certainty)
        }
    }

    @Test
    fun upsertAppliedEnvelopeRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(ExternalBusyRpcEnvelopeDto.serializer(), UPSERT_APPLIED_ENVELOPE_FIXTURE)
        val reencoded = wireJson.encodeToString(ExternalBusyRpcEnvelopeDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(UPSERT_APPLIED_ENVELOPE_FIXTURE), Json.parseToJsonElement(reencoded))
        assertEquals("applied", decoded.outcome)
        assertEquals("upsert_external_busy", decoded.action)
    }

    @Test
    fun deleteAppliedEnvelopeRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(ExternalBusyRpcEnvelopeDto.serializer(), DELETE_APPLIED_ENVELOPE_FIXTURE)
        val reencoded = wireJson.encodeToString(ExternalBusyRpcEnvelopeDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(DELETE_APPLIED_ENVELOPE_FIXTURE), Json.parseToJsonElement(reencoded))
        assertEquals("applied", decoded.outcome)
        assertEquals("delete_external_busy", decoded.action)
    }

    @Test
    fun crossActionConflictEnvelopeDecodesWithoutAnActionKey() {
        val decoded =
            Json.decodeFromString(ExternalBusyRpcEnvelopeDto.serializer(), CROSS_ACTION_CONFLICT_ENVELOPE_FIXTURE)
        assertEquals("conflict", decoded.outcome)
        assertNull(decoded.action)
    }
}
