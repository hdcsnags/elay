package dev.elay.domain.model

import kotlinx.datetime.Instant

/**
 * Stage 3 web RSVP mint result (contracts/stage3-web-rsvp.md;
 * council/stage3-web-rsvp-security-opus.md §2 "Minting" / §1 "Token design"). [token] is the
 * opaque presented capability string (`token_id.mac`, ~250 bits — §1 "Shape"); it is minted
 * server-side and never re-derivable client-side. [discloses] echoes the server's disclosure list
 * verbatim (§1 "Leaked-link threat model": title exposure is a deliberate, bounded trade that
 * "must be disclosed at mint" via `rpc_mint_rsvp_token`'s `discloses` field) so the share UI
 * prints exactly what the server asserts rather than hard-coding an assumption. [expiresAt] is
 * `proposal.response_deadline` at mint time, exactly (§1 "TTL") — never independently
 * configurable client-side.
 */
data class RsvpToken(
    val token: String,
    val tokenId: String,
    val expiresAt: Instant,
    val discloses: List<String>,
)

/**
 * Result of `rpc_mint_rsvp_token` (contract §2; council/stage3-web-rsvp-security-opus.md §2).
 * Mirrors [ProposalResult]'s applied/failed shape, but deliberately has **no `Conflict`** variant:
 * minting is not a revision-guarded mutation the caller retries against a live revision the way
 * `rpc_respond_proposal` is — every non-applied outcome (bad status, non-author caller, expired
 * proposal, malformed envelope, network failure) is a terminal [Failed] here.
 */
sealed interface MintRsvpResult {
    data class Applied(
        val token: RsvpToken,
    ) : MintRsvpResult

    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : MintRsvpResult
}
