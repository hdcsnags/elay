package dev.elay.ui.together.proposal

import dev.elay.domain.model.MintRsvpResult
import dev.elay.domain.model.PairMember
import dev.elay.domain.model.PairState
import dev.elay.domain.model.ProposalId
import dev.elay.domain.model.ProposalResult
import dev.elay.domain.model.ProposalSummary
import dev.elay.domain.model.RespondProposal
import dev.elay.domain.model.UserId
import dev.elay.domain.repository.PairRepository
import dev.elay.domain.repository.ProposalRepository
import dev.elay.ui.together.networkFailureMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * Together's proposal-feed state (council/stage2-timelock-gemini.md; contracts/stage2-timelock.md
 * seat grant C3): the raw active/history [ProposalSummary] lists plus the current [partner] (from
 * [PairRepository], `null` outside [PairState.Paired] — the feed only renders when Paired anyway),
 * a per-proposal selected-candidate index, the composer sheet's state, and the two confirm steps
 * (decline/withdraw). Deliberately does **not** pre-format any time-dependent text (countdowns,
 * dual-time lines) into this state — [cardsAt] recomputes those from a caller-supplied `now` so
 * the feed's countdown text updates on ordinary recomposition without a per-second ticker (§5.3).
 */
data class TogetherProposalUiState(
    val partner: PairMember? = null,
    val activeProposals: List<ProposalSummary> = emptyList(),
    val historyProposals: List<ProposalSummary> = emptyList(),
    val selectedCandidate: Map<String, Int> = emptyMap(),
    val dismissedIds: Set<String> = emptySet(),
    val composer: ComposerUiState? = null,
    val pendingDecline: ProposalId? = null,
    val pendingWithdraw: ProposalId? = null,
    val shareLink: ShareLinkUiState? = null,
    val actionError: String? = null,
)

/**
 * Stage 3 web-RSVP "Share response link" affordance (contracts/stage3-web-rsvp.md;
 * council/stage3-web-rsvp-security-opus.md §1-§2) — lives only on [OutgoingProposalCard] (the
 * viewer is the current revision's author there, the only caller `rpc_mint_rsvp_token` accepts).
 * `null` on [TogetherProposalUiState.shareLink] means idle/dismissed (no sheet, no in-progress
 * row) — the same "absent = nothing pending" idiom as [TogetherProposalUiState.pendingDecline].
 * There is no `Failed` variant here: a mint failure clears [TogetherProposalUiState.shareLink]
 * back to `null` and surfaces the calm [TogetherProposalUiState.actionError] instead, matching
 * every other mutation in this ViewModel.
 */
sealed interface ShareLinkUiState {
    val proposalId: ProposalId

    /** Shown inline on the card as a quiet in-progress state — no sheet yet. */
    data class Minting(
        override val proposalId: ProposalId,
    ) : ShareLinkUiState

    /** Backs the share sheet: the presented token link, its expiry (`proposal.response_deadline`
     * at mint — §1 "TTL"), and the disclosure copy built from the server's own `discloses` list
     * (see [dev.elay.domain.model.RsvpToken]'s kdoc — never a hard-coded assumption). */
    data class Ready(
        override val proposalId: ProposalId,
        val link: String,
        val expiresAt: Instant,
        val disclosureCopy: String,
    ) : ShareLinkUiState
}

/**
 * The web-RSVP Edge Function's local origin (council/stage3-web-rsvp-security-opus.md §2 "Edge
 * Function"; `npx supabase functions serve rsvp` per the contract's lead amendment 6).
 * TODO(stage3-prod-config): swap for the deployed Edge Function's production base URL once one
 * exists — this constant is the only place that needs to change.
 */
const val RSVP_LINK_BASE: String = "http://127.0.0.1:54321/functions/v1/rsvp"

private fun rsvpLinkFor(token: String): String = "$RSVP_LINK_BASE/$token"

/** §1 "Leaked-link threat model" labels, matched to `rpc_mint_rsvp_token`'s `discloses` values
 * (["title","times","names"] per the contract). */
private val SHARE_LINK_DISCLOSURE_LABELS =
    mapOf(
        "title" to "the title",
        "times" to "the proposed times",
        "names" to "both of your names",
    )

/**
 * §A's required disclosure line, e.g. "Anyone with this link can see the title, the proposed
 * times, and both of your names." — built from the server's own [discloses] list (never a
 * hard-coded assumption, per [dev.elay.domain.model.RsvpToken]'s kdoc) so the copy always matches
 * exactly what the token actually reveals.
 */
fun shareLinkDisclosureCopy(discloses: List<String>): String {
    val labels = discloses.mapNotNull { SHARE_LINK_DISCLOSURE_LABELS[it] }
    if (labels.isEmpty()) return "Anyone with this link can see limited proposal details."
    return "Anyone with this link can see ${labels.joinAsCalmList()}."
}

private fun List<String>.joinAsCalmList(): String =
    when (size) {
        1 -> this[0]
        2 -> "${this[0]} and ${this[1]}"
        else -> "${dropLast(1).joinToString(", ")}, and ${last()}"
    }

/**
 * Plain, testable ViewModel (no android.lifecycle dependency, house pattern) over
 * [ProposalRepository] + [PairRepository]'s state — the two frozen data surfaces this feed
 * consumes. Conflict-hints (spec §4) are **not** implemented: `rpc_proposal_conflict_hints` is not
 * part of the frozen client surface this round (contracts/stage2-timelock.md), so the composer and
 * response flows below ship with no self/partner busy-conflict hinting — see this seat's report.
 */
@Suppress(
    "TooManyFunctions", // one small action per composer/response affordance (house pattern, e.g. PlanViewModel)
    "LongParameterList", // both repositories + scope/identity + zone/clock/id-factory test seams
)
class TogetherProposalViewModel(
    private val repository: ProposalRepository,
    private val pairRepository: PairRepository,
    private val scope: CoroutineScope,
    private val selfId: UserId,
    private val viewerZone: TimeZone = TimeZone.currentSystemDefault(),
    private val clock: Clock = Clock.System,
    private val newOperationId: () -> String = { defaultProposalOperationId() },
) {
    private val _state = MutableStateFlow(TogetherProposalUiState())
    val state: StateFlow<TogetherProposalUiState> = _state.asStateFlow()

    init {
        combine(
            repository.observeActive(),
            repository.observeHistory(),
            pairRepository.observePair(),
        ) { active, history, pairState ->
            Triple(active, history, pairState)
        }.onEach { (active, history, pairState) ->
            val partner = (pairState as? PairState.Paired)?.snapshot?.members?.firstOrNull { it.userId != selfId }
            _state.update { it.copy(activeProposals = active, historyProposals = history, partner = partner) }
        }.launchIn(scope)
    }

    /** The Together feed's cards at [now] (§2) — call with `kotlin.time.Clock.System.now()` from
     * the composable so it recomputes on every ordinary recomposition. Empty until [partner] is
     * known (unpaired/loading), same as the rest of the Paired-only feed. */
    fun cardsAt(now: Instant): List<ProposalCardUiModel> {
        val partner = _state.value.partner ?: return emptyList()
        val partnerZone = TimeZone.of(partner.homeTz)
        return (_state.value.activeProposals + _state.value.historyProposals)
            .filterNot { it.id.value in _state.value.dismissedIds }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { it.toCardUiModel(selfId, partner.displayName, viewerZone, partnerZone, now) }
    }

    fun selectedCandidateFor(proposalId: ProposalId): Int = _state.value.selectedCandidate[proposalId.value] ?: 0

    fun selectCandidate(
        proposalId: ProposalId,
        index: Int,
    ) {
        _state.update { it.copy(selectedCandidate = it.selectedCandidate + (proposalId.value to index)) }
    }

    /** "Propose a time" (Together's entry point, §1.1) / "Propose another time"/"Propose
     * again"/"Propose new time" (§2.5's closed-card actions) — always a fresh composer. */
    fun openComposer() {
        val partner = _state.value.partner ?: return
        _state.update {
            it.copy(
                composer = newComposerState(clock.now(), viewerZone, TimeZone.of(partner.homeTz), partner.displayName),
            )
        }
    }

    /** "Suggest different" (§2.1/§2.3) — opens the composer prefilled as a counter to [proposalId]. */
    fun openCounterComposer(proposalId: ProposalId) {
        val partner = _state.value.partner ?: return
        val proposal = findProposal(proposalId) ?: return
        _state.update {
            it.copy(
                composer = counterComposerState(proposal, viewerZone, TimeZone.of(partner.homeTz), partner.displayName),
            )
        }
    }

    /** "Reschedule" (§2.4) — opens the composer prefilled with the accepted lock's own time. */
    fun openRescheduleComposer(proposalId: ProposalId) {
        val partner = _state.value.partner ?: return
        val proposal = findProposal(proposalId) ?: return
        _state.update {
            it.copy(
                composer =
                    rescheduleComposerState(
                        proposal,
                        viewerZone,
                        TimeZone.of(partner.homeTz),
                        partner.displayName,
                        clock.now(),
                    ),
            )
        }
    }

    fun dismissComposer() {
        _state.update { it.copy(composer = null) }
    }

    fun updateComposerTitle(text: String) {
        updateComposer { it.withTitle(text) }
    }

    fun stepComposerCandidateStart(
        index: Int,
        deltaMinutes: Int,
    ) {
        updateComposer { it.stepCandidateStart(index, deltaMinutes) }
    }

    fun stepComposerCandidateDay(
        index: Int,
        deltaDays: Int,
    ) {
        updateComposer { it.stepCandidateDay(index, deltaDays) }
    }

    fun stepComposerCandidateDuration(
        index: Int,
        deltaMinutes: Int,
    ) {
        updateComposer { it.stepCandidateDuration(index, deltaMinutes) }
    }

    fun setComposerCandidateDuration(
        index: Int,
        minutes: Int,
    ) {
        updateComposer { it.setCandidateDuration(index, minutes) }
    }

    fun addComposerCandidate() {
        updateComposer { it.withAddedCandidate() }
    }

    fun removeComposerCandidate(index: Int) {
        updateComposer { it.withRemovedCandidate(index) }
    }

    fun selectComposerDeadlineOption(option: DeadlineOption) {
        updateComposer { it.withDeadlineOption(option) }
    }

    /** "Send proposal" (create) / the counter composer's implicit send — validates first (§1.6's
     * calm error copy); on success, a create closes the composer while a counter also does (both
     * flows end the composer's job once applied). */
    fun submitComposer() {
        val composer = _state.value.composer ?: return
        val now = clock.now()
        when (val validation = validateComposer(composer, now)) {
            is ComposerValidation.Invalid -> updateComposer { it.copy(errorMessage = validation.message) }
            ComposerValidation.Valid -> {
                updateComposer { it.copy(isSubmitting = true, errorMessage = null) }
                scope.launch {
                    val result =
                        if (composer.counterProposalId != null) {
                            repository.respond(buildCounterResponse(composer, newOperationId()))
                        } else {
                            repository.create(buildCreateProposal(composer, newOperationId()))
                        }
                    applyMutationResult(result, closesComposerOnSuccess = true)
                }
            }
        }
    }

    /** "Can't" (§2.1) — opens the quiet confirm step; the actual decline waits for [confirmDecline]. */
    fun requestDecline(proposalId: ProposalId) {
        _state.update { it.copy(pendingDecline = proposalId) }
    }

    fun cancelDeclineConfirmation() {
        _state.update { it.copy(pendingDecline = null) }
    }

    fun confirmDecline() {
        val proposalId = _state.value.pendingDecline ?: return
        val proposal = findProposal(proposalId) ?: return
        _state.update { it.copy(pendingDecline = null) }
        scope.launch {
            val result =
                repository.respond(
                    RespondProposal.Decline(newOperationId(), proposalId, proposal.currentRevision),
                )
            applyMutationResult(result, closesComposerOnSuccess = false)
        }
    }

    /** "Accept Option N" (§2.1/§2.3) — accepts whatever [selectCandidate] most recently chose
     * (default candidate 0). */
    fun acceptCandidate(proposalId: ProposalId) {
        val proposal = findProposal(proposalId) ?: return
        val candidateIdx = selectedCandidateFor(proposalId)
        scope.launch {
            val result =
                repository.respond(
                    RespondProposal.Accept(newOperationId(), proposalId, proposal.currentRevision, candidateIdx),
                )
            applyMutationResult(result, closesComposerOnSuccess = false)
        }
    }

    /** "Withdraw proposal" (§2.2) — opens the quiet confirm step. */
    fun requestWithdraw(proposalId: ProposalId) {
        _state.update { it.copy(pendingWithdraw = proposalId) }
    }

    fun cancelWithdrawConfirmation() {
        _state.update { it.copy(pendingWithdraw = null) }
    }

    fun confirmWithdraw() {
        val proposalId = _state.value.pendingWithdraw ?: return
        _state.update { it.copy(pendingWithdraw = null) }
        scope.launch {
            val result = repository.cancel(newOperationId(), proposalId)
            applyMutationResult(result, closesComposerOnSuccess = false)
        }
    }

    /** "Dismiss" (§2.5) — local-only: the frozen surface has no archive/delete RPC for a closed
     * proposal, so this just hides the card from [cardsAt] rather than mutating the server. */
    fun dismissClosedProposal(proposalId: ProposalId) {
        _state.update { it.copy(dismissedIds = it.dismissedIds + proposalId.value) }
    }

    fun dismissActionError() {
        _state.update { it.copy(actionError = null) }
    }

    /** "Share response link" (§1 of this seat's grant) — the affordance only ever appears on an
     * [OutgoingProposalCard], i.e. [proposalId]'s live revision was authored by [selfId], mirroring
     * `rpc_mint_rsvp_token`'s server-side author check (council/stage3-web-rsvp-security-opus.md
     * §2 "Minting"). Re-mintable: calling this again (e.g. after the sheet was dismissed) mints a
     * fresh token, revoking the predecessor server-side (§1 "Re-mint") — there is no client-side
     * caching of a previous link. */
    fun requestShareLink(proposalId: ProposalId) {
        _state.update { it.copy(shareLink = ShareLinkUiState.Minting(proposalId)) }
        scope.launch {
            when (val result = repository.mintRsvpToken(newOperationId(), proposalId)) {
                is MintRsvpResult.Applied -> {
                    val token = result.token
                    _state.update {
                        it.copy(
                            shareLink =
                                ShareLinkUiState.Ready(
                                    proposalId = proposalId,
                                    link = rsvpLinkFor(token.token),
                                    expiresAt = token.expiresAt,
                                    disclosureCopy = shareLinkDisclosureCopy(token.discloses),
                                ),
                        )
                    }
                }
                is MintRsvpResult.Failed ->
                    _state.update {
                        it.copy(shareLink = null, actionError = networkFailureMessage(result.retryable))
                    }
            }
        }
    }

    /** Closes the share sheet ("Done") without touching the minted token server-side — the token
     * stays live until its own TTL/re-mint/revocation (§1); dismissing the sheet is purely local UI
     * state, same as [dismissComposer]. */
    fun dismissShareLink() {
        _state.update { it.copy(shareLink = null) }
    }

    private fun findProposal(id: ProposalId): ProposalSummary? =
        (_state.value.activeProposals + _state.value.historyProposals).find { it.id == id }

    private fun updateComposer(transform: (ComposerUiState) -> ComposerUiState) {
        _state.update { it.copy(composer = it.composer?.let(transform)) }
    }

    private fun applyMutationResult(
        result: ProposalResult,
        closesComposerOnSuccess: Boolean,
    ) {
        _state.update { current ->
            when (result) {
                is ProposalResult.Applied ->
                    current.copy(
                        composer = if (closesComposerOnSuccess) null else current.composer?.copy(isSubmitting = false),
                    )
                is ProposalResult.Conflict ->
                    current.copy(
                        composer = current.composer?.copy(isSubmitting = false),
                        actionError = "Someone already responded — this proposal has moved on to a newer version.",
                    )
                is ProposalResult.Failed ->
                    current.copy(
                        composer = current.composer?.copy(isSubmitting = false),
                        actionError = networkFailureMessage(result.retryable),
                    )
            }
        }
    }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun defaultProposalOperationId(): String =
    kotlin.uuid.Uuid
        .random()
        .toString()
