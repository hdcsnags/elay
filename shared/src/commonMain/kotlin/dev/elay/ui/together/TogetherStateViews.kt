package dev.elay.ui.together

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.elay.domain.model.PairFailure
import dev.elay.domain.model.PairMember
import dev.elay.domain.model.PairState
import dev.elay.ui.together.proposal.ProposalFeedSection
import dev.elay.ui.together.proposal.TogetherProposalViewModel
import kotlin.time.Clock

/**
 * The per-[PairState] cards + the leave/cancel confirm dialog rendered by
 * [TogetherContent] — split out of `TogetherScreen.kt` to keep that file's function
 * count under detekt's `TooManyFunctions` threshold. `internal`, not `private`: these are
 * still implementation details of this package, just not of that one file.
 */

@Composable
internal fun ActionErrorNotice(
    message: String,
    onDismiss: () -> Unit,
) {
    // Calm inline notice, not a red banner (spec §2) — secondaryContainer, not error colors.
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Dismiss message" },
            ) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
internal fun LoadingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Loading pairing status" })
            Text(text = "Checking your pairing status…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun UnpairedCard(
    redeemCodeText: String,
    isSubmitting: Boolean,
    onRedeemCodeChanged: (String) -> Unit,
    onCreateInvite: () -> Unit,
    onRedeemInvite: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Pair up with someone", style = MaterialTheme.typography.titleMedium)
            Text(
                text =
                    "Share a private space with one other person — plans and blocks you choose to " +
                        "share start showing up here once you're paired.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onCreateInvite,
                enabled = !isSubmitting,
                modifier = Modifier.semantics { contentDescription = "Create invite code" },
            ) {
                Text("Create invite code")
            }
            Text(text = "Have a code?", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = redeemCodeText,
                onValueChange = onRedeemCodeChanged,
                label = { Text("Enter invite code") },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Invite code" },
            )
            OutlinedButton(
                onClick = onRedeemInvite,
                enabled = !isSubmitting && redeemCodeText.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Redeem invite code" },
            ) {
                Text("Redeem")
            }
        }
    }
}

@Composable
internal fun InvitingCard(
    pairState: PairState.Inviting,
    isSubmitting: Boolean,
    onCreateNewInvite: () -> Unit,
    onCancelInvite: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Waiting for them to join", style = MaterialTheme.typography.titleMedium)
            val code = pairState.code
            if (code != null) {
                Text(
                    text = formatInviteCode(code),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { contentDescription = "Invite code ${formatInviteCode(code)}" },
                )
                Text(
                    text = expiresInLabel(now = Clock.System.now(), expiresAt = pairState.expiresAt),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = "Code shown when created — create a new invite to get another.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "Changed your mind? Cancelling this invite means leaving this pair.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCreateNewInvite,
                    enabled = !isSubmitting,
                    modifier = Modifier.semantics { contentDescription = "Create a new invite" },
                ) {
                    Text("Create a new invite")
                }
                TextButton(
                    onClick = onCancelInvite,
                    enabled = !isSubmitting,
                    modifier = Modifier.semantics { contentDescription = "Cancel invite" },
                ) {
                    Text("Cancel invite")
                }
            }
        }
    }
}

@Composable
internal fun PairedCard(
    pairState: PairState.Paired,
    isSubmitting: Boolean,
    onLeave: () -> Unit,
    proposalViewModel: TogetherProposalViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "You're paired", style = MaterialTheme.typography.titleMedium)
            pairState.snapshot.members.forEach { member -> MemberRow(member) }
            ProposalFeedSection(viewModel = proposalViewModel)
            OutlinedButton(
                onClick = onLeave,
                enabled = !isSubmitting,
                modifier = Modifier.semantics { contentDescription = "Leave this pair" },
            ) {
                Text("Leave")
            }
        }
    }
}

@Composable
private fun MemberRow(member: PairMember) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = member.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(text = member.homeTz, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun FailedCard(
    failure: PairFailure,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Couldn't load your pairing status", style = MaterialTheme.typography.titleMedium)
            Text(
                text =
                    when (failure) {
                        is PairFailure.Domain -> failure.error.calmMessage()
                        is PairFailure.Network -> networkFailureMessage(failure.retryable)
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.semantics { contentDescription = "Try again" },
            ) {
                Text("Try again")
            }
        }
    }
}

@Composable
internal fun LeaveConfirmDialog(
    isCancelOnly: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCancelOnly) "Cancel this invite?" else "Leave this pair?") },
        text = {
            Text(
                if (isCancelOnly) {
                    "This revokes the invite code so it can no longer be redeemed."
                } else {
                    "The other person will no longer see anything you've shared. You can pair again later."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "Confirm leave" },
            ) {
                Text(if (isCancelOnly) "Cancel invite" else "Leave")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Keep it" },
            ) {
                Text("Never mind")
            }
        },
    )
}
