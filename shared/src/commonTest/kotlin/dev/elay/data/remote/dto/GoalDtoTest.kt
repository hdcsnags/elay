package dev.elay.data.remote.dto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** kotlinx.serialization omits a property equal to its default (`encodeDefaults = false` by
 * default) — the fixture writes `household_id`/etc explicitly even when null, so re-encoding
 * for the byte-comparable check needs `encodeDefaults = true`. */
private val wireJson = Json { encodeDefaults = true }

/**
 * contracts/fixtures/goal.json embedded verbatim — commonTest cannot read files from
 * `contracts/` (outside any source set's resources), so the shared fixture text is copied
 * here byte-for-byte instead. Keep this in sync with the file by hand if seat A changes it.
 */
private const val GOAL_FIXTURE = """
{
  "id": "11111111-1111-4111-8111-111111111111",
  "owner_id": "00000000-0000-0000-0000-000000000001",
  "household_id": null,
  "visibility": "private",
  "title": "Ship ELAY Phase 1",
  "notes": "Ship goals, milestones, tasks, captures, and time blocks end to end.",
  "target_date": "2026-12-31",
  "status": "active",
  "version": 1,
  "created_at": "2026-09-11T19:00:00Z",
  "updated_at": "2026-09-11T19:00:00Z"
}
"""

class GoalDtoTest {
    @Test
    fun roundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(GoalDto.serializer(), GOAL_FIXTURE)
        val reencoded = wireJson.encodeToString(GoalDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(GOAL_FIXTURE), Json.parseToJsonElement(reencoded))
    }

    @Test
    fun roundTripsThroughDomain() {
        val decoded = Json.decodeFromString(GoalDto.serializer(), GOAL_FIXTURE)
        val domain = decoded.toDomain()
        assertEquals(decoded, domain.toDto())
    }
}
