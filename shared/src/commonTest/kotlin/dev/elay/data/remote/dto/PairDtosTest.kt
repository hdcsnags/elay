package dev.elay.data.remote.dto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** See [dev.elay.data.remote.dto.GoalDtoTest] for why `encodeDefaults = true` is required here. */
private val wireJson = Json { encodeDefaults = true }

/**
 * council/stage1-pairing-contract-sol.md §4: `PairSnapshotDto(pair_id,status,version,
 * channel_topic,members,active_invite_expires_at)`. No `contracts/fixtures/pair-*.json` exists in
 * B3's grant (A2 owns that path) — this fixture is hand-authored from the frozen field list, kept
 * byte-for-byte stable the same way the Phase 1 planner DTO tests are.
 */
private const val PAIR_SNAPSHOT_FIXTURE = """
{
  "pair_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "status": "active",
  "version": 2,
  "channel_topic": "pair:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "members": [
    {
      "user_id": "00000000-0000-0000-0000-000000000001",
      "display_name": "Alex",
      "home_tz": "America/Toronto",
      "joined_at": "2026-09-11T19:00:00Z"
    },
    {
      "user_id": "00000000-0000-0000-0000-000000000002",
      "display_name": "Sam",
      "home_tz": "America/Toronto",
      "joined_at": "2026-09-12T09:30:00Z"
    }
  ],
  "active_invite_expires_at": null
}
"""

private const val INVITE_CODE_FIXTURE = """
{
  "invite_id": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
  "code": "ABCDE-FGHJK",
  "expires_at": "2026-09-13T19:00:00Z"
}
"""

private const val CREATE_INVITE_ENVELOPE_FIXTURE = """
{
  "outcome": "applied",
  "action": "create_pair_invite",
  "pair": {
    "pair_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "status": "active",
    "version": 1,
    "channel_topic": "pair:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    "members": [
      {
        "user_id": "00000000-0000-0000-0000-000000000001",
        "display_name": "Alex",
        "home_tz": "America/Toronto",
        "joined_at": "2026-09-11T19:00:00Z"
      }
    ],
    "active_invite_expires_at": "2026-09-13T19:00:00Z"
  },
  "invite": {
    "invite_id": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    "code": "ABCDE-FGHJK",
    "expires_at": "2026-09-13T19:00:00Z"
  },
  "left_pair_id": null
}
"""

private const val LEAVE_ENVELOPE_FIXTURE = """
{
  "outcome": "applied",
  "action": "leave_pair",
  "pair": null,
  "invite": null,
  "left_pair_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
}
"""

private const val REDEEM_INVALID_ENVELOPE_FIXTURE = """
{
  "outcome": "invalid_or_unavailable",
  "action": "redeem_pair_invite",
  "pair": null,
  "invite": null,
  "left_pair_id": null
}
"""

class PairDtosTest {
    @Test
    fun pairSnapshotRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(PairSnapshotDto.serializer(), PAIR_SNAPSHOT_FIXTURE)
        val reencoded = wireJson.encodeToString(PairSnapshotDto.serializer(), decoded)
        assertEquals(
            Json.parseToJsonElement(PAIR_SNAPSHOT_FIXTURE),
            Json.parseToJsonElement(reencoded),
        )
    }

    @Test
    fun pairSnapshotMapsCleanlyToDomain() {
        val decoded = Json.decodeFromString(PairSnapshotDto.serializer(), PAIR_SNAPSHOT_FIXTURE)
        val domain = decoded.toDomain()

        assertEquals(decoded.pairId, domain.id.value)
        assertEquals(2, domain.members.size)
        assertEquals(decoded.members.map { it.userId }, domain.members.map { it.userId.value })
        assertEquals(null, domain.activeInviteExpiresAt)
    }

    @Test
    fun inviteCodeRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(InviteCodeDto.serializer(), INVITE_CODE_FIXTURE)
        val reencoded = wireJson.encodeToString(InviteCodeDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(INVITE_CODE_FIXTURE), Json.parseToJsonElement(reencoded))
    }

    @Test
    fun createInviteEnvelopeRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(PairRpcEnvelopeDto.serializer(), CREATE_INVITE_ENVELOPE_FIXTURE)
        val reencoded = wireJson.encodeToString(PairRpcEnvelopeDto.serializer(), decoded)
        assertEquals(
            Json.parseToJsonElement(CREATE_INVITE_ENVELOPE_FIXTURE),
            Json.parseToJsonElement(reencoded),
        )
        assertEquals("applied", decoded.outcome)
        assertEquals("ABCDE-FGHJK", decoded.invite?.code)
    }

    @Test
    fun leaveEnvelopeRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(PairRpcEnvelopeDto.serializer(), LEAVE_ENVELOPE_FIXTURE)
        val reencoded = wireJson.encodeToString(PairRpcEnvelopeDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(LEAVE_ENVELOPE_FIXTURE), Json.parseToJsonElement(reencoded))
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", decoded.leftPairId)
    }

    @Test
    fun redeemInvalidOrUnavailableEnvelopeRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(PairRpcEnvelopeDto.serializer(), REDEEM_INVALID_ENVELOPE_FIXTURE)
        val reencoded = wireJson.encodeToString(PairRpcEnvelopeDto.serializer(), decoded)
        assertEquals(
            Json.parseToJsonElement(REDEEM_INVALID_ENVELOPE_FIXTURE),
            Json.parseToJsonElement(reencoded),
        )
        assertEquals("invalid_or_unavailable", decoded.outcome)
    }
}
