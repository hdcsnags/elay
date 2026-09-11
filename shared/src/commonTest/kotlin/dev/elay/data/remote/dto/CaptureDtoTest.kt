package dev.elay.data.remote.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** See [dev.elay.data.remote.dto.GoalDtoTest] for why `encodeDefaults = true` is required here. */
private val wireJson = Json { encodeDefaults = true }

/** contracts/fixtures/capture.json embedded verbatim (see [GoalDtoTest] for why). */
private const val CAPTURE_FIXTURE = """
{
  "id": "44444444-4444-4444-8444-444444444444",
  "owner_id": "00000000-0000-0000-0000-000000000001",
  "body": "Call the dentist about next week's appointment.",
  "source": "quick",
  "ai_parse_status": "unparsed",
  "captured_at": "2026-09-11T19:00:00Z",
  "clarified_task_id": null,
  "version": 1,
  "created_at": "2026-09-11T19:00:00Z",
  "updated_at": "2026-09-11T19:00:00Z"
}
"""

class CaptureDtoTest {
    @Test
    fun roundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(CaptureDto.serializer(), CAPTURE_FIXTURE)
        val reencoded = wireJson.encodeToString(CaptureDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(CAPTURE_FIXTURE), Json.parseToJsonElement(reencoded))
    }

    @Test
    fun roundTripsThroughDomain() {
        val decoded = Json.decodeFromString(CaptureDto.serializer(), CAPTURE_FIXTURE)
        val domain = decoded.toDomain()
        val ts = Instant.parse("2026-09-11T19:00:00Z")
        assertEquals(decoded, domain.toDto(ts, ts))
    }
}
