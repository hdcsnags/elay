package dev.elay.data.remote.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** See [dev.elay.data.remote.dto.GoalDtoTest] for why `encodeDefaults = true` is required here. */
private val wireJson = Json { encodeDefaults = true }

/** contracts/fixtures/time_block.json embedded verbatim (see [GoalDtoTest] for why; `household_id`
 * -> `pair_id` per contracts/stage1-pairing.md amendment 1). */
private const val TIME_BLOCK_FIXTURE = """
{
  "id": "55555555-5555-4555-8555-555555555555",
  "owner_id": "00000000-0000-0000-0000-000000000001",
  "pair_id": null,
  "visibility": "private",
  "task_id": "33333333-3333-4333-8333-333333333333",
  "title": "Focus: write goals migration",
  "starts_at_utc": "2026-09-11T19:00:00Z",
  "ends_at_utc": "2026-09-11T20:30:00Z",
  "origin_tz": "America/Toronto",
  "type": "focus",
  "status": "scheduled",
  "recurrence_rule": null,
  "all_day": false,
  "version": 1,
  "created_at": "2026-09-11T19:00:00Z",
  "updated_at": "2026-09-11T19:00:00Z"
}
"""

class TimeBlockDtoTest {
    @Test
    fun roundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(TimeBlockDto.serializer(), TIME_BLOCK_FIXTURE)
        val reencoded = wireJson.encodeToString(TimeBlockDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(TIME_BLOCK_FIXTURE), Json.parseToJsonElement(reencoded))
    }

    @Test
    fun roundTripsThroughDomain() {
        val decoded = Json.decodeFromString(TimeBlockDto.serializer(), TIME_BLOCK_FIXTURE)
        val domain = decoded.toDomain()
        val ts = Instant.parse("2026-09-11T19:00:00Z")
        assertEquals(decoded, domain.toDto(ts, ts))
    }
}
