@file:Suppress("TooManyFunctions") // one small composable per sheet/row element (idiomatic Compose decomposition)

package dev.elay.ui.together.proposal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private val DURATION_PRESETS = listOf(30, 45, 60, 90)

/**
 * The Proposal Composer (council/stage2-timelock-gemini.md §1): an M3 [ModalBottomSheet], same
 * house pattern as [dev.elay.ui.plan.PlanScreen]'s add-block sheet — title, 1-3 candidate rows
 * (date/start/duration steppers + a live dual-time preview), a deadline choice, Send. No conflict
 * hints (§4) — `rpc_proposal_conflict_hints` is outside this round's frozen surface, see this
 * seat's report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalComposerSheet(
    state: ComposerUiState,
    viewModel: TogetherProposalViewModel,
) {
    val now = Clock.System.now()
    ModalBottomSheet(onDismissRequest = viewModel::dismissComposer) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (state.counterProposalId != null) "Suggest different times" else "Propose a time lock",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "With ${state.partnerDisplayName} (${state.partnerZone.id})",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::updateComposerTitle,
                label = { Text("What are you doing? (optional)") },
                placeholder = { Text("e.g., Deep work session, Weekly check-in") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Proposal title" },
            )

            state.candidates.forEachIndexed { index, candidate ->
                CandidateSlot(index = index, candidate = candidate, state = state, now = now, viewModel = viewModel)
            }
            if (state.candidates.size < MAX_PROPOSAL_CANDIDATES) {
                TextButton(
                    onClick = viewModel::addComposerCandidate,
                    modifier = Modifier.semantics { contentDescription = "Add alternative time" },
                ) {
                    Text("+ Add alternative time (up to 3)")
                }
            }

            DeadlineSection(state = state, viewModel = viewModel, now = now)

            state.errorMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = viewModel::dismissComposer) { Text("Cancel") }
                Button(
                    onClick = viewModel::submitComposer,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.semantics { contentDescription = "Send proposal" },
                ) {
                    Text(if (state.isSubmitting) "Sending proposal…" else "Send proposal")
                }
            }
        }
    }
}

@Composable
private fun CandidateSlot(
    index: Int,
    candidate: ComposerCandidate,
    state: ComposerUiState,
    now: Instant,
    viewModel: TogetherProposalViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CandidateHeaderRow(index = index, onRemove = { viewModel.removeComposerCandidate(index) })
            StepperRow(
                label = formatDatePrefix(candidate.date),
                onDecrement = { viewModel.stepComposerCandidateDay(index, -1) },
                onIncrement = { viewModel.stepComposerCandidateDay(index, 1) },
                decrementDescription = "Earlier date for option ${index + 1}",
                incrementDescription = "Later date for option ${index + 1}",
            )
            StepperRow(
                label = "Start ${formatClockTime(candidate.startTime)}",
                onDecrement = { viewModel.stepComposerCandidateStart(index, -COMPOSER_TIME_STEP_MINUTES) },
                onIncrement = { viewModel.stepComposerCandidateStart(index, COMPOSER_TIME_STEP_MINUTES) },
                decrementDescription = "Earlier start time for option ${index + 1}",
                incrementDescription = "Later start time for option ${index + 1}",
            )
            DurationPresetsRow(index = index, candidate = candidate, viewModel = viewModel)
            StepperRow(
                label = "Duration ${candidate.durationMinutes} min",
                onDecrement = { viewModel.stepComposerCandidateDuration(index, -COMPOSER_TIME_STEP_MINUTES) },
                onIncrement = { viewModel.stepComposerCandidateDuration(index, COMPOSER_TIME_STEP_MINUTES) },
                decrementDescription = "Shorter duration for option ${index + 1}",
                incrementDescription = "Longer duration for option ${index + 1}",
            )
            CandidatePreview(index = index, candidate = candidate, state = state, now = now)
        }
    }
}

@Composable
private fun CandidateHeaderRow(
    index: Int,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Option ${index + 1}", style = MaterialTheme.typography.titleSmall)
        if (index > 0) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "Remove option ${index + 1}" },
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = null)
            }
        }
    }
}

@Composable
private fun DurationPresetsRow(
    index: Int,
    candidate: ComposerCandidate,
    viewModel: TogetherProposalViewModel,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DURATION_PRESETS.forEach { minutes ->
            FilterChip(
                selected = candidate.durationMinutes == minutes,
                onClick = { viewModel.setComposerCandidateDuration(index, minutes) },
                label = { Text("${minutes}m") },
                modifier = Modifier.semantics { contentDescription = "$minutes minutes for option ${index + 1}" },
            )
        }
    }
}

@Composable
private fun CandidatePreview(
    index: Int,
    candidate: ComposerCandidate,
    state: ComposerUiState,
    now: Instant,
) {
    val start = candidate.date.atTime(candidate.startTime).toInstant(state.viewerZone)
    val lines =
        buildDualTimeLines(
            startsAt = start,
            endsAt = start + candidate.durationMinutes.minutes,
            viewerZone = state.viewerZone,
            partnerZone = state.partnerZone,
            partnerDisplayName = state.partnerDisplayName,
            now = now,
            includeDate = false,
        )
    DualTimeText(
        lines,
        modifier =
            Modifier.semantics(mergeDescendants = true) {
                contentDescription =
                    "Option ${index + 1} preview: ${lines.viewerLine}, ${lines.partnerLine}" +
                    (lines.dstCaption?.let { ". $it" } ?: "")
            },
    )
}

@Composable
private fun StepperRow(
    label: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementDescription: String,
    incrementDescription: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = onDecrement,
                modifier = Modifier.semantics { contentDescription = decrementDescription },
            ) {
                Text("−")
            }
            OutlinedButton(
                onClick = onIncrement,
                modifier = Modifier.semantics { contentDescription = incrementDescription },
            ) {
                Text("+")
            }
        }
    }
}

@Composable
private fun DeadlineSection(
    state: ComposerUiState,
    viewModel: TogetherProposalViewModel,
    now: Instant,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Response deadline", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            deadlineOptionChoices().forEach { option ->
                FilterChip(
                    selected = state.deadlineOption == option,
                    onClick = { viewModel.selectComposerDeadlineOption(option) },
                    label = { Text(option.label()) },
                    modifier = Modifier.semantics { contentDescription = option.label() },
                )
            }
        }
        val deadline = resolveDeadline(state)
        Text(
            "Deadline: ${deadlineWallClockLabel(now, deadline, state.viewerZone)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "If ${state.partnerDisplayName} hasn't responded by then, this proposal will quietly expire.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The three relative-deadline choices exposed this round — [DeadlineOption.Custom] has no
 * date/time-picker UI yet (no such dependency exists in this codebase; see this file's kdoc). */
private fun deadlineOptionChoices(): List<DeadlineOption> =
    listOf(DeadlineOption.TwelveHoursBefore, DeadlineOption.TwentyFourHoursBefore, DeadlineOption.TwoHoursBefore)

private fun DeadlineOption.label(): String =
    when (this) {
        DeadlineOption.TwelveHoursBefore -> "12 hours before"
        DeadlineOption.TwentyFourHoursBefore -> "24 hours before"
        DeadlineOption.TwoHoursBefore -> "2 hours before"
        DeadlineOption.Custom -> "Custom"
    }
