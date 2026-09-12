package dev.elay.data.remote.dto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** See [dev.elay.data.remote.dto.GoalDtoTest] for why `encodeDefaults = true` is required here. */
private val wireJson = Json { encodeDefaults = true }

/** contracts/fixtures/task.json embedded verbatim (see [GoalDtoTest] for why; `household_id` ->
 * `pair_id` per contracts/stage1-pairing.md amendment 1). */
private const val TASK_FIXTURE = """
{
  "id": "33333333-3333-4333-8333-333333333333",
  "owner_id": "00000000-0000-0000-0000-000000000001",
  "pair_id": null,
  "visibility": "private",
  "goal_id": "11111111-1111-4111-8111-111111111111",
  "milestone_id": "22222222-2222-4222-8222-222222222222",
  "title": "Write goals migration",
  "notes": "Include CHECK constraints and RLS.",
  "status": "todo",
  "priority": 1,
  "effort": 3,
  "estimate_min": 90,
  "due_start_utc": "2026-09-11T19:00:00Z",
  "due_end_utc": "2026-09-11T20:30:00Z",
  "recurrence_rule": null,
  "tags": ["schema", "phase1"],
  "version": 1,
  "created_at": "2026-09-11T19:00:00Z",
  "updated_at": "2026-09-11T19:00:00Z"
}
"""

class TaskDtoTest {
    @Test
    fun roundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(TaskDto.serializer(), TASK_FIXTURE)
        val reencoded = wireJson.encodeToString(TaskDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(TASK_FIXTURE), Json.parseToJsonElement(reencoded))
    }

    @Test
    fun roundTripsThroughDomain() {
        val decoded = Json.decodeFromString(TaskDto.serializer(), TASK_FIXTURE)
        val domain = decoded.toDomain()
        assertEquals(decoded, domain.toDto())
    }
}
