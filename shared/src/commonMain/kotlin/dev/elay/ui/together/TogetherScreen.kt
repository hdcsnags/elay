package dev.elay.ui.together

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.elay.domain.model.PairState

/**
 * Together surface (contracts/stage1-pairing.md; council/stage1-pairing-contract-sol.md §4):
 * renders the pairing lifecycle ONLY — Unpaired invite/redeem, Inviting waiting card, Paired
 * member identity + a placeholder for Stage 2's shared time locks, Failed calm retry, Loading.
 * Calm empty/error states throughout (spec §2) — no red banners, no goal/task/block data.
 */
@Composable
fun TogetherScreen(
    modifier: Modifier = Modifier,
    viewModel: TogetherViewModel = rememberTogetherViewModel(),
) {
    val state by viewModel.state.collectAsState()
    TogetherContent(
        state = state,
        onRedeemCodeChanged = viewModel::onRedeemCodeChanged,
        onCreateInvite = viewModel::createInvite,
        onRedeemInvite = viewModel::redeemInvite,
        onRequestLeave = viewModel::requestLeaveConfirmation,
        onCancelLeaveConfirmation = viewModel::cancelLeaveConfirmation,
        onConfirmLeave = viewModel::confirmLeave,
        onDismissError = viewModel::dismissActionError,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

@Suppress("LongParameterList") // state + one event lambda per user action — idiomatic Compose (detekt.yml)
@Composable
private fun TogetherContent(
    state: TogetherUiState,
    onRedeemCodeChanged: (String) -> Unit,
    onCreateInvite: () -> Unit,
    onRedeemInvite: () -> Unit,
    onRequestLeave: () -> Unit,
    onCancelLeaveConfirmation: () -> Unit,
    onConfirmLeave: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(text = "Together", style = MaterialTheme.typography.headlineSmall) }

        state.actionError?.let { message ->
            item { ActionErrorNotice(message = message, onDismiss = onDismissError) }
        }

        when (val pairState = state.pairState) {
            PairState.Loading -> item { LoadingCard() }
            PairState.Unpaired ->
                item {
                    UnpairedCard(
                        redeemCodeText = state.redeemCodeText,
                        isSubmitting = state.isSubmitting,
                        onRedeemCodeChanged = onRedeemCodeChanged,
                        onCreateInvite = onCreateInvite,
                        onRedeemInvite = onRedeemInvite,
                    )
                }
            is PairState.Inviting ->
                item {
                    InvitingCard(
                        pairState = pairState,
                        isSubmitting = state.isSubmitting,
                        onCreateNewInvite = onCreateInvite,
                        onCancelInvite = onRequestLeave,
                    )
                }
            is PairState.Paired ->
                item {
                    PairedCard(
                        pairState = pairState,
                        isSubmitting = state.isSubmitting,
                        onLeave = onRequestLeave,
                    )
                }
            is PairState.Failed -> item { FailedCard(failure = pairState.failure, onRetry = onRetry) }
        }
    }

    if (state.leaveConfirmPending) {
        LeaveConfirmDialog(
            isCancelOnly = state.pairState is PairState.Inviting,
            onConfirm = onConfirmLeave,
            onDismiss = onCancelLeaveConfirmation,
        )
    }
}

/** Wires the real per-account [dev.elay.domain.repository.PairRepository] once the lead provides
 * [LocalPairRepository] (falls back to the shared fake, matching every other `remember*ViewModel`
 * factory in `ui/today|inbox|plan`). */
@Composable
private fun rememberTogetherViewModel(): TogetherViewModel {
    val scope = rememberCoroutineScope()
    val repository = LocalPairRepository.current
    return remember(repository) { TogetherViewModel(repository, scope) }
}
