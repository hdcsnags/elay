package dev.elay.data.remote.dto

import dev.elay.domain.model.CommitmentState
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalState
import dev.elay.domain.model.ResponseKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** See [dev.elay.data.remote.dto.GoalDtoTest] for why `encodeDefaults = true` is required here. */
private val wireJson = Json { encodeDefaults = true }

/**
 * No `contracts/fixtures/proposal-*.json` exists in this seat's grant (A3 owns that path per lead
 * amendment 2) — these fixtures are hand-authored from council/stage2-timelock-sol.md §A.1-§A.2's
 * frozen field lists, the same way [dev.elay.data.remote.dto.PairDtosTest] was for Stage 1. The
 * lead diffs both against A3's real RPC output at merge; any mismatch is fixed on this seat's side
 * unless the SQL deviates from the contract.
 */
private const val CANDIDATE_FIXTURE = """
{
  "candidate_idx": 0,
  "starts_at_utc": "2026-09-20T23:00:00Z",
  "ends_at_utc": "2026-09-21T00:00:00Z",
  "duration_min": 60
}
"""

private const val REVISION_FIXTURE = """
{
  "revision_no": 1,
  "author_id": "00000000-0000-0000-0000-000000000001",
  "origin_tz": "America/Vancouver",
  "candidates": [
    {
      "candidate_idx": 0,
      "starts_at_utc": "2026-09-20T23:00:00Z",
      "ends_at_utc": "2026-09-21T00:00:00Z",
      "duration_min": 60
    },
    {
      "candidate_idx": 1,
      "starts_at_utc": "2026-09-21T23:00:00Z",
      "ends_at_utc": "2026-09-22T00:00:00Z",
      "duration_min": 60
    }
  ],
  "created_at": "2026-09-12T19:00:00Z"
}
"""

private const val RESPONSE_FIXTURE = """
{
  "id": "44444444-4444-4444-8444-444444444444",
  "proposal_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "revision_no": 1,
  "user_id": "00000000-0000-0000-0000-000000000002",
  "response": "accept",
  "candidate_idx": 0,
  "counter_revision": null,
  "responded_at": "2026-09-12T20:00:00Z"
}
"""

private const val COMMITMENT_FIXTURE = """
{
  "id": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
  "proposal_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "user_id": "00000000-0000-0000-0000-000000000002",
  "state": "active",
  "created_from_revision": 1,
  "candidate_idx": 0,
  "time_block_id": "55555555-5555-4555-8555-555555555555",
  "version": 1,
  "created_at": "2026-09-12T20:00:00Z",
  "updated_at": "2026-09-12T20:00:00Z",
  "withdrawn_at": null,
  "completed_at": null
}
"""

private const val ACCEPTED_PROPOSAL_FIXTURE = """
{
  "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "pair_id": "70000000-0000-4000-8000-000000000001",
  "creator_id": "00000000-0000-0000-0000-000000000001",
  "title": "Study session",
  "status": "accepted",
  "response_deadline": "2026-09-13T19:00:00Z",
  "origin_tz": "America/Vancouver",
  "current_revision": 1,
  "accepted_revision": 1,
  "accepted_candidate_idx": 0,
  "version": 2,
  "created_at": "2026-09-12T19:00:00Z",
  "updated_at": "2026-09-12T20:00:00Z",
  "revisions": [
    {
      "revision_no": 1,
      "author_id": "00000000-0000-0000-0000-000000000001",
      "origin_tz": "America/Vancouver",
      "candidates": [
        {
          "candidate_idx": 0,
          "starts_at_utc": "2026-09-20T23:00:00Z",
          "ends_at_utc": "2026-09-21T00:00:00Z",
          "duration_min": 60
        }
      ],
      "created_at": "2026-09-12T19:00:00Z"
    }
  ],
  "responses": [
    {
      "id": "44444444-4444-4444-8444-444444444444",
      "proposal_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      "revision_no": 1,
      "user_id": "00000000-0000-0000-0000-000000000002",
      "response": "accept",
      "candidate_idx": 0,
      "counter_revision": null,
      "responded_at": "2026-09-12T20:00:00Z"
    }
  ],
  "commitment": {
    "id": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    "proposal_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "user_id": "00000000-0000-0000-0000-000000000002",
    "state": "active",
    "created_from_revision": 1,
    "candidate_idx": 0,
    "time_block_id": "55555555-5555-4555-8555-555555555555",
    "version": 1,
    "created_at": "2026-09-12T20:00:00Z",
    "updated_at": "2026-09-12T20:00:00Z",
    "withdrawn_at": null,
    "completed_at": null
  }
}
"""

/** A freshly-created, un-responded proposal — no accepted fields, no responses, no commitment. */
private const val PROPOSED_PROPOSAL_FIXTURE = """
{
  "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "pair_id": "70000000-0000-4000-8000-000000000001",
  "creator_id": "00000000-0000-0000-0000-000000000001",
  "title": "Study session",
  "status": "proposed",
  "response_deadline": "2026-09-13T19:00:00Z",
  "origin_tz": "America/Vancouver",
  "current_revision": 1,
  "accepted_revision": null,
  "accepted_candidate_idx": null,
  "version": 1,
  "created_at": "2026-09-12T19:00:00Z",
  "updated_at": "2026-09-12T19:00:00Z",
  "revisions": [
    {
      "revision_no": 1,
      "author_id": "00000000-0000-0000-0000-000000000001",
      "origin_tz": "America/Vancouver",
      "candidates": [
        {
          "candidate_idx": 0,
          "starts_at_utc": "2026-09-20T23:00:00Z",
          "ends_at_utc": "2026-09-21T00:00:00Z",
          "duration_min": 60
        }
      ],
      "created_at": "2026-09-12T19:00:00Z"
    }
  ],
  "responses": [],
  "commitment": null
}
"""

private const val CREATE_ENVELOPE_FIXTURE = """
{
  "outcome": "applied",
  "action": "create_proposal",
  "proposal": $PROPOSED_PROPOSAL_FIXTURE,
  "current_revision": null,
  "status": null
}
"""

private const val CONFLICT_ENVELOPE_FIXTURE = """
{
  "outcome": "conflict",
  "action": "respond_proposal",
  "proposal": null,
  "current_revision": 3,
  "status": "countered"
}
"""

class ProposalDtosTest {
    /**
     * Strips JsonNull members before comparing: the real RPC output carries explicit
     * nulls for transition timestamps (fixture diff 2026-09-12), our decode-only
     * production path never re-encodes, and null-presence is not semantic here.
     */
    private fun stripNulls(e: JsonElement): JsonElement =
        when (e) {
            is JsonObject -> JsonObject(e.filterValues { it != JsonNull }.mapValues { (_, v) -> stripNulls(v) })
            is JsonArray -> JsonArray(e.map { stripNulls(it) })
            else -> e
        }

    @Test
    fun candidateRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(CandidateDto.serializer(), CANDIDATE_FIXTURE)
        val reencoded = wireJson.encodeToString(CandidateDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(CANDIDATE_FIXTURE), Json.parseToJsonElement(reencoded))
        val domain = decoded.toDomain()
        assertEquals(0, domain.index)
        assertEquals(60, domain.durationMinutes)
        assertEquals(decoded, domain.toDto())
    }

    @Test
    fun revisionRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(ProposalRevisionDto.serializer(), REVISION_FIXTURE)
        val reencoded = wireJson.encodeToString(ProposalRevisionDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(REVISION_FIXTURE), Json.parseToJsonElement(reencoded))
        val domain = decoded.toDomain()
        assertEquals(2, domain.candidates.size)
        assertEquals(listOf(0, 1), domain.candidates.map { it.index })
        assertEquals(decoded, domain.toDto())
    }

    @Test
    fun responseRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(ProposalResponseDto.serializer(), RESPONSE_FIXTURE)
        val reencoded = wireJson.encodeToString(ProposalResponseDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(RESPONSE_FIXTURE), Json.parseToJsonElement(reencoded))
        val domain = decoded.toDomain()
        assertEquals(ResponseKind.Accept, domain.response)
        assertEquals(0, domain.candidateIdx)
        assertNull(domain.counterRevision)
    }

    @Test
    fun commitmentRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(CommitmentDto.serializer(), COMMITMENT_FIXTURE)
        val reencoded = wireJson.encodeToString(CommitmentDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(COMMITMENT_FIXTURE), Json.parseToJsonElement(reencoded))
        val domain = decoded.toDomain()
        assertEquals(CommitmentState.Active, domain.state)
        assertNull(domain.withdrawnAt)
        assertNull(domain.completedAt)
    }

    @Test
    fun acceptedProposalRoundTripsFixtureByteComparablyWithFullHistory() {
        val decoded = Json.decodeFromString(ProposalSummaryDto.serializer(), ACCEPTED_PROPOSAL_FIXTURE)
        val reencoded = wireJson.encodeToString(ProposalSummaryDto.serializer(), decoded)
        assertEquals(
            stripNulls(Json.parseToJsonElement(ACCEPTED_PROPOSAL_FIXTURE)),
            stripNulls(Json.parseToJsonElement(reencoded)),
        )

        val domain = decoded.toDomain()
        assertEquals(ProposalState.Accepted, domain.state)
        assertEquals(1, domain.acceptedRevision)
        assertEquals(0, domain.acceptedCandidateIdx)
        assertEquals(1, domain.revisions.size)
        assertEquals(1, domain.responses.size)
        assertEquals("America/Vancouver", domain.originZoneId)
        val commitment = domain.commitment
        assertEquals(ProposalId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), commitment?.proposalId)
    }

    @Test
    fun proposedProposalHasNoAcceptedFieldsNoResponsesNoCommitment() {
        val decoded = Json.decodeFromString(ProposalSummaryDto.serializer(), PROPOSED_PROPOSAL_FIXTURE)
        val reencoded = wireJson.encodeToString(ProposalSummaryDto.serializer(), decoded)
        assertEquals(
            stripNulls(Json.parseToJsonElement(PROPOSED_PROPOSAL_FIXTURE)),
            stripNulls(Json.parseToJsonElement(reencoded)),
        )

        val domain = decoded.toDomain()
        assertEquals(ProposalState.Proposed, domain.state)
        assertNull(domain.acceptedRevision)
        assertNull(domain.acceptedCandidateIdx)
        assertEquals(emptyList(), domain.responses)
        assertNull(domain.commitment)
    }

    @Test
    fun createEnvelopeRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(ProposalRpcEnvelopeDto.serializer(), CREATE_ENVELOPE_FIXTURE)
        val reencoded = wireJson.encodeToString(ProposalRpcEnvelopeDto.serializer(), decoded)
        assertEquals(
            stripNulls(Json.parseToJsonElement(CREATE_ENVELOPE_FIXTURE)),
            stripNulls(Json.parseToJsonElement(reencoded)),
        )
        assertEquals("applied", decoded.outcome)
        assertEquals("create_proposal", decoded.action)
        assertEquals(ProposalState.Proposed, decoded.proposal?.toDomain()?.state)
    }

    @Test
    fun conflictEnvelopeRoundTripsFixtureByteComparably() {
        val decoded = Json.decodeFromString(ProposalRpcEnvelopeDto.serializer(), CONFLICT_ENVELOPE_FIXTURE)
        val reencoded = wireJson.encodeToString(ProposalRpcEnvelopeDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(CONFLICT_ENVELOPE_FIXTURE), Json.parseToJsonElement(reencoded))
        assertEquals("conflict", decoded.outcome)
        assertEquals(3, decoded.currentRevision)
        assertEquals("countered", decoded.status)
        assertNull(decoded.proposal)
    }

    /** council/stage2-timelock-sol.md §4: "[ProposalState] matching all eight server strings" —
     * every entry must round-trip through [ProposalSummaryDto.status] with its exact wire value. */
    @Test
    fun everyProposalStateRoundTripsItsWireString() {
        for (state in ProposalState.entries) {
            val dto = minimalProposalDto(status = state.wire)
            val json = wireJson.encodeToString(ProposalSummaryDto.serializer(), dto)
            val decoded = Json.decodeFromString(ProposalSummaryDto.serializer(), json)
            assertEquals(state.wire, decoded.status)
            assertEquals(state, decoded.toDomain().state)
        }
    }

    @Test
    fun everyResponseKindRoundTripsItsWireString() {
        for (kind in ResponseKind.entries) {
            val dto =
                ProposalResponseDto(
                    id = "id-1",
                    proposalId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    revisionNo = 1,
                    userId = "00000000-0000-0000-0000-000000000002",
                    response = kind.wire,
                    respondedAt = "2026-09-12T20:00:00Z",
                )
            val json = wireJson.encodeToString(ProposalResponseDto.serializer(), dto)
            val decoded = Json.decodeFromString(ProposalResponseDto.serializer(), json)
            assertEquals(kind, decoded.toDomain().response)
        }
    }

    @Test
    fun everyCommitmentStateRoundTripsItsWireString() {
        for (state in CommitmentState.entries) {
            val dto =
                CommitmentDto(
                    id = "commitment-1",
                    proposalId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    userId = "00000000-0000-0000-0000-000000000002",
                    state = state.wire,
                    createdFromRevision = 1,
                    candidateIdx = 0,
                    timeBlockId = "55555555-5555-4555-8555-555555555555",
                    version = 1,
                    createdAt = "2026-09-12T20:00:00Z",
                    updatedAt = "2026-09-12T20:00:00Z",
                )
            val json = wireJson.encodeToString(CommitmentDto.serializer(), dto)
            val decoded = Json.decodeFromString(CommitmentDto.serializer(), json)
            assertEquals(state, decoded.toDomain().state)
        }
    }

    private fun minimalProposalDto(status: String) =
        ProposalSummaryDto(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            pairId = "70000000-0000-4000-8000-000000000001",
            creatorId = "00000000-0000-0000-0000-000000000001",
            title = "Study session",
            status = status,
            responseDeadline = "2026-09-13T19:00:00Z",
            originTz = "America/Vancouver",
            currentRevision = 1,
            version = 1,
            createdAt = "2026-09-12T19:00:00Z",
            updatedAt = "2026-09-12T19:00:00Z",
        )
}
