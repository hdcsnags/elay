// Stage 3 has exactly one DTO so far (RsvpMintEnvelopeDto); the file is still named RsvpDtos.kt
// per this seat's grant (contracts/stage3-web-rsvp.md) to match the house's plural per-feature DTO
// file convention (ProposalDtos.kt, PairDtos.kt) rather than ktlint's/detekt's single-class
// filename rules.
@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package dev.elay.data.remote.dto

import dev.elay.domain.model.RsvpToken
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `rpc_mint_rsvp_token` wire envelope (council/stage3-web-rsvp-security-opus.md §2 "Minting";
 * the REAL wire fixture `contracts/fixtures/rsvp-mint.json`, round-tripped byte-comparably in
 * `RsvpDtosTest`). The live RPC's applied shape is exactly:
 * `{"outcome":"applied","action":"mint_rsvp_token","token","token_id","discloses":[...],
 * "expires_at"}` — no `proposal`/`current_revision`/`status` keys, unlike
 * [ProposalRpcEnvelopeDto] (contract §2: minting has no conflict shape).
 *
 * [token]/[tokenId]/[discloses]/[expiresAt] are nullable-with-defaults: the fixture is the only
 * observed (applied) shape, but a non-`"applied"` outcome — an authorization/validation failure
 * the server chooses to surface as an envelope rather than a raised exception — must still decode
 * instead of throwing (same F12-adjacent tolerance as [ProposalRpcEnvelopeDto]'s nullable
 * `action`/`proposal`).
 */
@Serializable
data class RsvpMintEnvelopeDto(
    val outcome: String,
    val action: String? = null,
    val token: String? = null,
    @SerialName("token_id") val tokenId: String? = null,
    val discloses: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
)

/**
 * Decodes the applied fields into [RsvpToken]. Callers only invoke this once `outcome == "applied"`
 * has already been checked — see `RsvpMintEnvelopeDto.toMintResult()` in
 * [dev.elay.data.remote.impl.SupabaseProposalRepository], which performs that outcome branching
 * and runs this mapping INSIDE the transport's `runCatchingSuspend` catch (the F12 lesson: a
 * missing/malformed applied field must degrade to `MintRsvpResult.Failed`, never escape and crash
 * the app).
 */
fun RsvpMintEnvelopeDto.toDomain(): RsvpToken =
    RsvpToken(
        token = requireNotNull(token) { "mint envelope: missing token" },
        tokenId = requireNotNull(tokenId) { "mint envelope: missing token_id" },
        expiresAt = Instant.parse(requireNotNull(expiresAt) { "mint envelope: missing expires_at" }),
        discloses = discloses,
    )
