package dev.elay.data.remote.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** See [dev.elay.data.remote.dto.GoalDtoTest] for why `encodeDefaults = true` is required here. */
private val wireJson = Json { encodeDefaults = true }

/**
 * `contracts/fixtures/rsvp-mint.json` — the REAL wire fixture from A4's `rpc_mint_rsvp_token`
 * (contracts/stage3-web-rsvp.md; council/stage3-web-rsvp-security-opus.md §2). Copied verbatim,
 * not hand-invented (the Stage-2 lesson this seat's grant explicitly calls out).
 */
private const val MINT_APPLIED_FIXTURE = """
{
  "outcome": "applied",
  "action": "mint_rsvp_token",
  "token_id": "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e",
  "token": "d1jxfqz8k3n5vwrtha6c72eqzm.f0kx9j2wrq5tvbc341hzse8pmn",
  "expires_at": "2026-09-16T19:00:00Z",
  "discloses": ["title", "times", "names"]
}
"""

class RsvpDtosTest {
    @Test
    fun mintAppliedEnvelopeRoundTripsTheRealFixtureByteComparably() {
        val decoded = Json.decodeFromString(RsvpMintEnvelopeDto.serializer(), MINT_APPLIED_FIXTURE)
        val reencoded = wireJson.encodeToString(RsvpMintEnvelopeDto.serializer(), decoded)
        assertEquals(Json.parseToJsonElement(MINT_APPLIED_FIXTURE), Json.parseToJsonElement(reencoded))

        assertEquals("applied", decoded.outcome)
        assertEquals("mint_rsvp_token", decoded.action)
        assertEquals("b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e", decoded.tokenId)
        assertEquals("d1jxfqz8k3n5vwrtha6c72eqzm.f0kx9j2wrq5tvbc341hzse8pmn", decoded.token)
        assertEquals(listOf("title", "times", "names"), decoded.discloses)

        val domain = decoded.toDomain()
        assertEquals("b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e", domain.tokenId)
        assertEquals("d1jxfqz8k3n5vwrtha6c72eqzm.f0kx9j2wrq5tvbc341hzse8pmn", domain.token)
        assertEquals(Instant.parse("2026-09-16T19:00:00Z"), domain.expiresAt)
        assertEquals(listOf("title", "times", "names"), domain.discloses)
    }

    /** Contract §2: minting has no conflict shape — a hypothetical non-`applied` outcome must
     * still decode (nullable/defaulted fields), not throw on the DTO layer. [toDomain] is only
     * ever called after the outcome check (see
     * [dev.elay.data.remote.impl.SupabaseProposalRepository]'s `toMintResult`), so it is entitled
     * to require the applied fields once called — this pins that it still fails loudly (for the
     * repository's catch to convert) rather than silently, if ever misused directly. */
    @Test
    fun nonAppliedOutcomeDecodesWithNullDefaultedFields() {
        val decoded =
            Json.decodeFromString(
                RsvpMintEnvelopeDto.serializer(),
                """{"outcome":"invalid_or_unavailable"}""",
            )
        assertEquals("invalid_or_unavailable", decoded.outcome)
        assertNull(decoded.action)
        assertNull(decoded.token)
        assertNull(decoded.tokenId)
        assertNull(decoded.expiresAt)
        assertEquals(emptyList(), decoded.discloses)

        assertFailsWith<IllegalArgumentException> { decoded.toDomain() }
    }
}
