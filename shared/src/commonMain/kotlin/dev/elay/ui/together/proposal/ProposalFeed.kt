@file:Suppress("TooManyFunctions") // one small composable per card state/element (idiomatic Compose decomposition)

package dev.elay.ui.together.proposal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.elay.domain.availability.Certainty
import dev.elay.domain.model.ProposalId
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * The Together Paired-state proposal feed (council/stage2-timelock-gemini.md §2): the "Propose a
 * time" entry point plus one card per active/history proposal, replacing the Stage-1 placeholder
 * text. Self-contained — collects [viewModel]'s own state — so [dev.elay.ui.together.TogetherStateViews.PairedCard]
 * only has to mount it.
 */
@Composable
fun ProposalFeedSection(viewModel: TogetherProposalViewModel) {
    val state by viewModel.state.collectAsState()
    // Recomputed every recomposition (not on a per-second ticker, §5.3) — natural recomposition
    // (list updates, user actions) is what refreshes countdown/DST text, exactly like
    // dev.elay.ui.together.InvitingCard's `expiresInLabel(now = Clock.System.now(), ...)`.
    val now = Clock.System.now()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = viewModel::openComposer,
            enabled = state.partner != null,
            modifier = Modifier.semantics { contentDescription = "Propose a time" },
        ) {
            Text("Propose a time")
        }

        state.actionError?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.partner == null) {
            Text("Shared time locks will appear here.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val cards = viewModel.cardsAt(now)
            if (cards.isEmpty()) {
                Text("No time locks yet — propose one above.", style = MaterialTheme.typography.bodyMedium)
            } else {
                cards.forEach { card ->
                    ProposalCard(card, viewModel, now, state.shareLink, state.cardHints[card.id.value].orEmpty())
                }
            }
        }
    }

    state.composer?.let { composer -> ProposalComposerSheet(composer, viewModel) }
    state.pendingDecline?.let {
        DeclineConfirmDialog(onConfirm = viewModel::confirmDecline, onDismiss = viewModel::cancelDeclineConfirmation)
    }
    state.pendingWithdraw?.let {
        WithdrawConfirmDialog(onConfirm = viewModel::confirmWithdraw, onDismiss = viewModel::cancelWithdrawConfirmation)
    }
    (state.shareLink as? ShareLinkUiState.Ready)?.let { ready ->
        ShareLinkSheet(ready, onDismiss = viewModel::dismissShareLink)
    }
}

@Suppress("LongParameterList") // one param per card-kind's own extra data (shareLink/cardHints) — house pattern
@Composable
private fun ProposalCard(
    card: ProposalCardUiModel,
    viewModel: TogetherProposalViewModel,
    now: Instant,
    shareLink: ShareLinkUiState?,
    cardHints: Map<Int, Certainty>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (card) {
                is IncomingProposalCard -> IncomingCardBody(card, viewModel, now, cardHints)
                is OutgoingProposalCard -> OutgoingCardBody(card, viewModel, now, shareLink)
                is AcceptedProposalCard -> AcceptedCardBody(card, viewModel)
                is ClosedProposalCard -> ClosedCardBody(card, viewModel)
            }
        }
    }
}

@Composable
private fun Eyebrow(
    label: String,
    trailing: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        trailing?.let {
            // Polite, not assertive: no per-tick spam, only meaningful on the rare recomposition
            // that changes this text (§5.3).
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun IncomingCardBody(
    card: IncomingProposalCard,
    viewModel: TogetherProposalViewModel,
    now: Instant,
    cardHints: Map<Int, Certainty>,
) {
    // Responder certainty (this seat's grant §3; council/stage4-availability-gemini.md §1.2
    // "Proposal Feed Response Chips"): fetched once per rendered card (and again if a counter
    // changes its live revision) — the actual `rpc_self_conflict_hints` call and state update live
    // in the ViewModel (testable without Compose); [cardHints] itself flows back in through the
    // observed [TogetherProposalUiState.cardHints] (via [ProposalFeedSection]'s `collectAsState`),
    // not a direct re-read of the ViewModel here, so recomposition actually picks it up.
    LaunchedEffect(card.id, card.revisionNo) { viewModel.refreshCardHints(card.id) }

    Eyebrow(
        label = if (card.isCountered) "TIME LOCK PROPOSAL · Countered" else "TIME LOCK PROPOSAL · Incoming",
        trailing = deadlineCountdownLabel(now, card.deadline),
    )
    Text(card.title, style = MaterialTheme.typography.titleMedium)
    if (card.isCountered) {
        Text(
            "${card.partnerDisplayName} suggested different times:",
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        val countWord = if (card.candidates.size == 1) "1 option" else "${card.candidates.size} options"
        Text("${card.partnerDisplayName} proposed $countWord:", style = MaterialTheme.typography.bodyMedium)
    }

    val selected = viewModel.selectedCandidateFor(card.id)
    card.candidates.forEach { chip ->
        CandidateChipRow(
            chip = chip,
            total = card.candidates.size,
            selected = chip.index == selected,
            certainty = cardHints[chip.index],
            onClick = { viewModel.selectCandidate(card.id, chip.index) },
        )
    }

    if (card.isCountered && card.previousCandidates.isNotEmpty()) {
        PreviousRevisionDisclosure(card.previousCandidates, card.revisionNo - 1)
    }

    // FlowRow, not Row (§C3 fix — a phone-width Row squeezed "Can't" to one letter per line):
    // three whole buttons wrap onto a second line together at narrow widths instead of any one
    // button being crushed to fit.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { viewModel.acceptCandidate(card.id) },
            modifier = Modifier.semantics { contentDescription = "Accept option ${selected + 1}" },
        ) {
            Text("Accept Option ${selected + 1}")
        }
        OutlinedButton(
            onClick = { viewModel.openCounterComposer(card.id) },
            modifier = Modifier.semantics { contentDescription = "Suggest different times" },
        ) {
            Text("Suggest different")
        }
        TextButton(
            onClick = { viewModel.requestDecline(card.id) },
            modifier = Modifier.semantics { contentDescription = "Can't make these times" },
        ) {
            Text("Can't")
        }
    }
}

@Composable
private fun PreviousRevisionDisclosure(
    previous: List<CandidateChipUiModel>,
    previousRevisionNo: Int,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.semantics { contentDescription = "Previous times, revision $previousRevisionNo" },
    ) {
        Text("Previous times (Revision $previousRevisionNo)")
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            previous.forEach { chip ->
                Text(
                    chip.lines.viewerLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OutgoingCardBody(
    card: OutgoingProposalCard,
    viewModel: TogetherProposalViewModel,
    now: Instant,
    shareLink: ShareLinkUiState?,
) {
    Eyebrow(label = "TIME LOCK PROPOSAL · Sent", trailing = "Waiting for ${card.partnerDisplayName}")
    Text(card.title, style = MaterialTheme.typography.titleMedium)
    card.candidates.forEach { chip ->
        DualTimeText(
            chip.lines,
            modifier =
                Modifier.semantics(
                    mergeDescendants = true,
                ) { contentDescription = chip.accessibilityDescription },
        )
    }
    // [OutgoingProposalCard] pre-builds its chips' dual-time text but not the deadline's wall-clock
    // day label, which only needs the device's own zone — recovered here the same way every other
    // Together/Plan surface does, rather than threading a TimeZone through the card model.
    Text(
        responseDeadlineLine(now, card.deadline, TimeZone.currentSystemDefault()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = { viewModel.requestWithdraw(card.id) },
            modifier = Modifier.semantics { contentDescription = "Withdraw proposal" },
        ) {
            Text("Withdraw proposal")
        }
        ShareLinkAffordance(
            proposalId = card.id,
            shareLink = shareLink,
            onRequestShareLink = { viewModel.requestShareLink(card.id) },
        )
    }
}

/** "Share response link" (this seat's grant, §1): appears only on [OutgoingProposalCard] — the
 * viewer authored the live revision there, the only caller `rpc_mint_rsvp_token` accepts
 * (council/stage3-web-rsvp-security-opus.md §2 "Minting"). While minting, a quiet inline state
 * replaces the button rather than opening a sheet for an as-yet-nonexistent link; once ready, the
 * sheet itself is rendered by [ShareLinkSheet] at the feed's top level (mirrors the composer/decline
 * dialogs). */
@Composable
private fun ShareLinkAffordance(
    proposalId: ProposalId,
    shareLink: ShareLinkUiState?,
    onRequestShareLink: () -> Unit,
) {
    val isMintingThisCard = shareLink is ShareLinkUiState.Minting && shareLink.proposalId == proposalId
    if (isMintingThisCard) {
        Text(
            "Preparing your link…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Preparing your share link" },
        )
    } else {
        TextButton(
            onClick = onRequestShareLink,
            modifier = Modifier.semantics { contentDescription = "Share response link" },
        ) {
            Text("Share response link")
        }
    }
}

/**
 * The share sheet (this seat's grant, §1): the presented token link, an expiry line in the
 * viewer's own zone (matching every other wall-clock label in this file), the §A disclosure copy,
 * a Copy button (commonMain [LocalClipboardManager] — no platform share intent), and Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareLinkSheet(
    shareLink: ShareLinkUiState.Ready,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val now = Clock.System.now()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Share response link", style = MaterialTheme.typography.titleMedium)
            Text(
                shareLink.link,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = "Response link: ${shareLink.link}" },
            )
            Text(
                "Link works until ${deadlineWallClockLabel(now, shareLink.expiresAt, TimeZone.currentSystemDefault())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                shareLink.disclosureCopy,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(shareLink.link)) },
                    modifier = Modifier.semantics { contentDescription = "Copy link" },
                ) {
                    Text("Copy")
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.semantics { contentDescription = "Done" },
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun AcceptedCardBody(
    card: AcceptedProposalCard,
    viewModel: TogetherProposalViewModel,
) {
    Eyebrow(label = "TIME LOCK CONFIRMED")
    Text(card.title, style = MaterialTheme.typography.titleMedium)
    DualTimeText(
        card.winning.lines,
        modifier =
            Modifier.semantics(mergeDescendants = true) {
                contentDescription =
                    card.winning.accessibilityDescription
            },
    )
    Text("On both plans · Time lock active", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {}, modifier = Modifier.semantics { contentDescription = "View in Plan" }) {
            Text("View in Plan")
        }
        OutlinedButton(
            onClick = { viewModel.openRescheduleComposer(card.id) },
            modifier = Modifier.semantics { contentDescription = "Reschedule" },
        ) {
            Text("Reschedule")
        }
    }
}

@Composable
private fun ClosedCardBody(
    card: ClosedProposalCard,
    viewModel: TogetherProposalViewModel,
) {
    val eyebrow =
        when (card.reason) {
            ClosedProposalReason.Declined -> "PROPOSAL CLOSED"
            ClosedProposalReason.Expired -> "PROPOSAL EXPIRED"
            ClosedProposalReason.Cancelled -> "PROPOSAL WITHDRAWN"
        }
    val body =
        when (card.reason) {
            ClosedProposalReason.Declined ->
                if (card.byMe) {
                    "You declined this proposal."
                } else {
                    "${card.partnerDisplayName} couldn't make these times work."
                }
            ClosedProposalReason.Expired -> "This proposal reached its response deadline without an answer."
            ClosedProposalReason.Cancelled ->
                if (card.byMe) "You withdrew this proposal." else "${card.partnerDisplayName} withdrew this proposal."
        }
    val actionLabel =
        when (card.reason) {
            ClosedProposalReason.Declined -> "Propose another time"
            ClosedProposalReason.Expired -> "Propose again"
            ClosedProposalReason.Cancelled -> "Propose new time"
        }
    Eyebrow(label = eyebrow)
    Text(card.title, style = MaterialTheme.typography.titleMedium)
    Text(body, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = viewModel::openComposer,
            modifier = Modifier.semantics { contentDescription = actionLabel },
        ) {
            Text(actionLabel)
        }
        TextButton(
            onClick = { viewModel.dismissClosedProposal(card.id) },
            modifier = Modifier.semantics { contentDescription = "Dismiss" },
        ) {
            Text("Dismiss")
        }
    }
}

/** §3.1's typography rule: viewer time primary (SemiBold, 15sp, onSurface), partner time
 * secondary (Regular, 13sp, onSurfaceVariant), optional DST caption beneath. */
@Composable
internal fun DualTimeText(
    lines: DualTimeLines,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            lines.viewerLine,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            lines.partnerLine,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lines.dstCaption?.let { caption ->
            Text(
                "* $caption",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** §5.2: `Role.RadioButton` semantics + the "Selected: Option N of M" state description, 48dp
 * minimum touch target. */
@Composable
private fun CandidateChipRow(
    chip: CandidateChipUiModel,
    total: Int,
    selected: Boolean,
    certainty: Certainty?,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .semantics {
                    contentDescription = chip.accessibilityDescription
                    stateDescription = candidateStateDescription(chip.index, total, selected)
                }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            DualTimeText(chip.lines)
            // Responder certainty (this seat's grant §3): additive under the dual-time lines,
            // never replacing them — absent (no badge) until the hint resolves.
            certainty?.let { CertaintyBadge(it, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun DeclineConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Decline this proposal?") },
        text = {
            Text(
                "Alex will be notified that you can't make this time work. " +
                    "You can propose a new time whenever you're ready.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.semantics { contentDescription = "Decline proposal" }) {
                Text("Decline proposal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Text("Back")
            }
        },
    )
}

@Composable
private fun WithdrawConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Withdraw this proposal?") },
        text = { Text("Alex won't be able to accept it anymore.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.semantics { contentDescription = "Withdraw" }) {
                Text("Withdraw")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Keep open" }) {
                Text("Keep open")
            }
        },
    )
}
