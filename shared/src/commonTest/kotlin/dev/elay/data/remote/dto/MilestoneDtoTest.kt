package dev.elay.data.remote.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** contracts/fixtures/milestone.json embedded verbatim (see [GoalDtoTest] for why). */
private const val MILESTONE_FIXTURE = """
{
  "id": "22222222-2222-4222-8222-222222222222",
  "goal_id": "11111111-1111-4111-8111-111111111111",
  "title": "Schema and RPC contract frozen",
  "target_date": "2026-09-30",
  "sort_order": 0,
  "status": "pending",
  "version": 1,
  "created_at": "2026-09-11T19:00:00Z",
  "updated_at": "2026-09-11T19:00:00Z"
}
"""

/** See [dev.elay.data.remote.dto.GoalDtoTest] for why `encodeDefaults = true` is required here. */
private val wireJson = Json { encodeDefaults = true }

class MilestoneDtoTest {
    @Test
    fun roundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(MilestoneDto.serializer(), MILESTONE_FIXTURE)
        val reencoded = wireJson.encodeToString(MilestoneDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(MILESTONE_FIXTURE), Json.parseToJsonElement(reencoded))
    }

    @Test
    fun roundTripsThroughDomain() {
        val decoded = Json.decodeFromString(MilestoneDto.serializer(), MILESTONE_FIXTURE)
        val domain = decoded.toDomain()
        val ts = Instant.parse("2026-09-11T19:00:00Z")
        assertEquals(decoded, domain.toDto(ts, ts))
    }
}
