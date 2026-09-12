package dev.elay.ui.together

import dev.elay.domain.model.InviteResult
import dev.elay.domain.model.LeaveResult
import dev.elay.domain.model.PairState
import dev.elay.domain.model.RedeemResult
import dev.elay.domain.repository.PairRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Together surface state (contracts/stage1-pairing.md; council/stage1-pairing-contract-sol.md
 * §4): [pairState] drives which lifecycle view renders, the rest is local UI-only state for the
 * redeem-code field, a pending submit spinner, a calm inline error for the last action, and the
 * leave/cancel-invite confirm step.
 */
data class TogetherUiState(
    val pairState: PairState = PairState.Loading,
    val redeemCodeText: String = "",
    val isSubmitting: Boolean = false,
    /** Calm, non-leaking copy for the last create/redeem/leave failure — never a raw error code
     * or reason string (spec §2, ADR on [dev.elay.domain.model.PairError]). */
    val actionError: String? = null,
    /** Non-null while the leave/cancel-invite confirm step is open. */
    val leaveConfirmPending: Boolean = false,
)

/**
 * Plain, testable ViewModel (no android.lifecycle dependency, house pattern) for Together:
 * observes [PairRepository.observePair] and drives the three pairing mutations, each behind a
 * confirm step for leave/cancel (contract: "Paired ... Leave with a confirm step"). Renders the
 * pairing lifecycle ONLY (contract §Adopted-verbatim) — no goal/task/block data.
 */
class TogetherViewModel(
    private val repository: PairRepository,
    private val scope: CoroutineScope,
    private val newOperationId: () -> String = { defaultOperationId() },
) {
    private val _state = MutableStateFlow(TogetherUiState())
    val state: StateFlow<TogetherUiState> = _state.asStateFlow()

    init {
        repository
            .observePair()
            .onEach { pairState -> _state.update { it.copy(pairState = pairState) } }
            .launchIn(scope)
    }

    fun onRedeemCodeChanged(text: String) {
        _state.update { it.copy(redeemCodeText = text, actionError = null) }
    }

    /** "Create invite code" (Unpaired) — also "Create a new invite" (Inviting, incl. the
     * code == null case where this process never learned the live invite's code). */
    fun createInvite() {
        scope.launch {
            _state.update { it.copy(isSubmitting = true, actionError = null) }
            val result = repository.createInvite(newOperationId())
            _state.update { current ->
                when (result) {
                    is InviteResult.Applied -> current.copy(isSubmitting = false)
                    is InviteResult.DomainError ->
                        current.copy(isSubmitting = false, actionError = result.error.calmMessage())
                    is InviteResult.NetworkError ->
                        current.copy(isSubmitting = false, actionError = networkFailureMessage(result.retryable))
                }
            }
        }
    }

    /** "Have a code? Redeem" (Unpaired only). */
    fun redeemInvite() {
        val code = _state.value.redeemCodeText.trim()
        if (code.isEmpty()) return
        scope.launch {
            _state.update { it.copy(isSubmitting = true, actionError = null) }
            val result = repository.redeemInvite(newOperationId(), code)
            _state.update { current ->
                when (result) {
                    is RedeemResult.Applied -> current.copy(isSubmitting = false, redeemCodeText = "")
                    is RedeemResult.DomainError ->
                        current.copy(isSubmitting = false, actionError = result.error.calmMessage())
                    is RedeemResult.NetworkError ->
                        current.copy(isSubmitting = false, actionError = networkFailureMessage(result.retryable))
                }
            }
        }
    }

    /** Opens the confirm step for Paired's "Leave" and Inviting's "Cancel invite" (both call
     * [PairRepository.leave] — an Inviting pair has exactly one member, so leaving it revokes the
     * live invite and ends the solo pair, which is the whole of "cancel"). */
    fun requestLeaveConfirmation() {
        _state.update { it.copy(leaveConfirmPending = true) }
    }

    fun cancelLeaveConfirmation() {
        _state.update { it.copy(leaveConfirmPending = false) }
    }

    fun confirmLeave() {
        scope.launch {
            _state.update { it.copy(isSubmitting = true, actionError = null, leaveConfirmPending = false) }
            val result = repository.leave(newOperationId())
            _state.update { current ->
                when (result) {
                    is LeaveResult.Applied -> current.copy(isSubmitting = false)
                    is LeaveResult.DomainError ->
                        current.copy(isSubmitting = false, actionError = result.error.calmMessage())
                    is LeaveResult.NetworkError ->
                        current.copy(isSubmitting = false, actionError = networkFailureMessage(result.retryable))
                }
            }
        }
    }

    fun dismissActionError() {
        _state.update { it.copy(actionError = null) }
    }

    /**
     * "Failed (calm retry)". The frozen [PairRepository] surface (contracts/stage1-pairing.md
     * §4) exposes no dedicated refresh/reconnect call — recovery from a [PairState.Failed] is
     * driven by the repository's own background resync loop on token refresh, realtime
     * reconnect, or the next mutation (council/stage1-pairing-contract-sol.md §3). Calling one
     * of the three mutations here on the user's behalf would risk an unintended side effect
     * (e.g. silently creating an invite while merely trying to reconnect), so this simply clears
     * any stale action error and lets the calm "Try again" affordance re-render whatever
     * [PairRepository.observePair] currently reports — honest rather than pretending a refresh
     * happened that the surface can't actually perform.
     */
    fun retry() {
        _state.update { it.copy(actionError = null) }
    }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun defaultOperationId(): String =
    kotlin.uuid.Uuid
        .random()
        .toString()
