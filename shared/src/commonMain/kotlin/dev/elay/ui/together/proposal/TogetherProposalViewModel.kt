package dev.elay.ui.together.proposal

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
    val actionError: String? = null,
)

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
