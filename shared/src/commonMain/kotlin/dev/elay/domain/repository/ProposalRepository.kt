package dev.elay.domain.repository

import dev.elay.domain.model.CreateProposal
import dev.elay.domain.model.MintRsvpResult
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalResult
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.RespondProposal
import kotlinx.coroutines.flow.Flow

/**
 * Frozen Stage 2 time-lock repository surface (council/stage2-timelock-sol.md §4 "Frozen Kotlin
 * surface", copied verbatim). Implementations are remote+memory, same as
 * [dev.elay.domain.repository.PairRepository] — no Room cache (mirrors stage 1's lead amendment
 * 4; the negotiation state is small, pair-scoped, and always freshly authoritative from the
 * server).
 *
 * [AutoCloseable]: tears down the background refetch loop — never leaves it running past the
 * session that owns it (ADR-009), matching [dev.elay.domain.repository.PairRepository].
 *
 * [observeActive]/[observeHistory] never format an [dev.elay.domain.model.ProposalRevision]'s
 * candidate [kotlinx.datetime.Instant]s or a proposal's response deadline — the server returns
 * UTC instants and an origin zone id only (council/stage2-timelock-sol.md §4: "the server returns
 * no formatted time or fixed offset"). Rendering (dual-time per Gemini's §B) combines that
 * [kotlinx.datetime.Instant] with the origin zone id, the viewer's zone id, and the other pair
 * member's zone id entirely in the UI layer.
 */
interface ProposalRepository : AutoCloseable {
    /** Proposals in `proposed`/`countered` state for the caller's active pair (contract
     * `rpc_list_proposals(scope='active')`). */
    fun observeActive(): Flow<List<ProposalSummary>>

    /** Proposals in `accepted`/`declined`/`expired`/`cancelled`/`completed` state (contract
     * `rpc_list_proposals(scope='history')`). */
    fun observeHistory(): Flow<List<ProposalSummary>>

    suspend fun create(command: CreateProposal): ProposalResult

    suspend fun respond(command: RespondProposal): ProposalResult

    suspend fun cancel(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalResult

    suspend fun complete(
        operationId: String,
        proposalId: ProposalId,
    ): ProposalResult

    /**
     * Stage 3 frozen-surface amendment (contracts/stage3-web-rsvp.md lead amendment; the ONLY
     * permitted change to this otherwise-frozen interface): `rpc_mint_rsvp_token`
     * (council/stage3-web-rsvp-security-opus.md §2). Mints (or re-mints, revoking the predecessor
     * — §1 "Re-mint") the caller's one live web-RSVP capability token for [proposalId] at its
     * current revision. The caller must be the current revision's author; the recipient is
     * derived server-side as the other active pair member — never supplied here. Does not affect
     * [observeActive]/[observeHistory] — minting changes no proposal state.
     */
    suspend fun mintRsvpToken(
        operationId: String,
        proposalId: ProposalId,
    ): MintRsvpResult
}
