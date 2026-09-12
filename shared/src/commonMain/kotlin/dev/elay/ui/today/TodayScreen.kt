// Stage 5 (contracts/stage5-retention-hardening.md) adds the wrap-up/next-time card composables
// alongside the existing Today surface — one small composable per card/chip/row (idiomatic
// Compose) pushes this file's top-level function count past TooManyFunctions' default.
@file:Suppress("TooManyFunctions")

package dev.elay.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.elay.di.LocalPlannerRepository
import dev.elay.domain.model.Task
import dev.elay.domain.model.TimeBlock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Minimum touch target for every wrap-up chip / next-time action (§B 4.1's a11y baseline:
 * "minimum bounding box of 48 × 48 dp, even when visual pill heights are 36 dp"). */
private val MIN_TOUCH_TARGET = 48.dp

/**
 * Today surface (brief §2): current/next block with a countdown, up to three
 * focus tasks, an inbox count, and the day's block list. Calm empty state —
 * one action, never a red banner (spec §2).
 */
@Composable
fun TodayScreen(
    onOpenPlan: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRescheduleBlock: (TimeBlock) -> Unit = {},
    onProposeNextTimeDuration: (TimeBlock, Int) -> Unit = { _, _ -> },
    onKeepStandardDuration: (TimeBlock, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = rememberTodayViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Next-Time suggestion (contracts/stage5-retention-hardening.md, this seat's grant: "call
    // nextTimeSuggestion(...) when rendering") — the upcoming/current highlighted block is the
    // natural anchor ("book more time for the next one like this"); re-fires only when that
    // anchor block actually changes, not on every recomposition (§B "inform, never nag").
    val suggestionAnchor = state.nextBlock ?: state.currentBlock
    LaunchedEffect(suggestionAnchor?.id) {
        suggestionAnchor?.let(viewModel::refreshNextTimeSuggestion)
    }

    TodayContent(
        state = state,
        onCompleteBlock = viewModel::completeBlock,
        onNotTodayBlock = viewModel::notTodayBlock,
        onCompleteTask = viewModel::completeTask,
        onNotTodayTask = viewModel::notTodayTask,
        onFinishedEarly = viewModel::recordFinishedEarly,
        onOnTime = viewModel::recordOnTime,
        onBeginRanLong = viewModel::beginRanLongAdjustment,
        onSelectRanLongDelta = viewModel::selectRanLongDelta,
        onConfirmRanLong = viewModel::confirmRanLongAdjustment,
        onDidntHappen = viewModel::recordDidntHappen,
        onReschedule = { block ->
            // Records the fact (Rescheduled is one of the four closed outcome kinds) but is
            // otherwise a LABEL + navigation nudge — never an auto-proposal (contract's binding
            // §A failure mode 2).
            viewModel.recordReschedule(block)
            onRescheduleBlock(block)
        },
        onDismissWrapUp = viewModel::dismissWrapUp,
        onProposeNextTime = { block, card ->
            onProposeNextTimeDuration(block, card.suggestedMinutes)
            viewModel.dismissNextTimeCard(card.blockId)
        },
        onKeepStandard = { block, card ->
            onKeepStandardDuration(block, card.standardMinutes)
            viewModel.dismissNextTimeCard(card.blockId)
        },
        onSkipNextTime = { card -> viewModel.dismissNextTimeCard(card.blockId) },
        onOpenPlan = onOpenPlan,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

// state + one event lambda per user action — idiomatic Compose (detekt.yml ignores both for @Composable)
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun TodayContent(
    state: TodayUiState,
    onCompleteBlock: (TimeBlock) -> Unit,
    onNotTodayBlock: (TimeBlock) -> Unit,
    onCompleteTask: (Task) -> Unit,
    onNotTodayTask: (Task) -> Unit,
    onFinishedEarly: (TimeBlock) -> Unit,
    onOnTime: (TimeBlock) -> Unit,
    onBeginRanLong: (TimeBlock) -> Unit,
    onSelectRanLongDelta: (TimeBlock, Int) -> Unit,
    onConfirmRanLong: (TimeBlock) -> Unit,
    onDidntHappen: (TimeBlock) -> Unit,
    onReschedule: (TimeBlock) -> Unit,
    onDismissWrapUp: (TimeBlock) -> Unit,
    onProposeNextTime: (TimeBlock, NextTimeCardUiState) -> Unit,
    onKeepStandard: (TimeBlock, NextTimeCardUiState) -> Unit,
    onSkipNextTime: (NextTimeCardUiState) -> Unit,
    onOpenPlan: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TodayHeader(inboxCount = state.inboxCount, onOpenSettings = onOpenSettings) }

        if (state.isEmpty) {
            item { TodayEmptyState(onOpenPlan = onOpenPlan) }
            return@LazyColumn
        }

        // The wrap-up card takes the top highlight slot (§B 1.1: "above Focus today") — rendered
        // either for the currently-eligible block, or (post-recording) for the block whose brief
        // "Noted for next time." confirmation is still transiently showing.
        val wrapUpBlock = state.wrapUpBlock ?: state.blocks.find { it.id.value == state.wrapUpConfirmationBlockId }
        if (wrapUpBlock != null) {
            item {
                WrapUpCard(
                    block = wrapUpBlock,
                    isConfirming = state.wrapUpConfirmationBlockId == wrapUpBlock.id.value,
                    pendingAdjustment = state.pendingRanLongAdjustment?.takeIf { it.blockId == wrapUpBlock.id.value },
                    onFinishedEarly = { onFinishedEarly(wrapUpBlock) },
                    onOnTime = { onOnTime(wrapUpBlock) },
                    onBeginRanLong = { onBeginRanLong(wrapUpBlock) },
                    onSelectRanLongDelta = { delta -> onSelectRanLongDelta(wrapUpBlock, delta) },
                    onConfirmRanLong = { onConfirmRanLong(wrapUpBlock) },
                    onDidntHappen = { onDidntHappen(wrapUpBlock) },
                    onReschedule = { onReschedule(wrapUpBlock) },
                    onDismiss = { onDismissWrapUp(wrapUpBlock) },
                )
            }
        }

        val nextTimeCard = state.nextTimeCard
        if (nextTimeCard != null) {
            val nextTimeBlock = state.blocks.find { it.id.value == nextTimeCard.blockId }
            item {
                NextTimeCard(
                    card = nextTimeCard,
                    onPropose = { nextTimeBlock?.let { onProposeNextTime(it, nextTimeCard) } },
                    onKeepStandard = { nextTimeBlock?.let { onKeepStandard(it, nextTimeCard) } },
                    onSkip = { onSkipNextTime(nextTimeCard) },
                )
            }
        }

        val highlighted = state.currentBlock ?: state.nextBlock
        if (highlighted != null && state.now != null) {
            item {
                HighlightedBlockCard(
                    block = highlighted,
                    now = state.now,
                    isInProgress = state.currentBlock != null,
                    onComplete = { onCompleteBlock(highlighted) },
                    onNotToday = { onNotTodayBlock(highlighted) },
                )
            }
        }

        if (state.focusTasks.isNotEmpty()) {
            item {
                Text(
                    text = "Focus today",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.focusTasks, key = { it.id.value }) { task ->
                FocusTaskRow(
                    task = task,
                    onComplete = { onCompleteTask(task) },
                    onNotToday = { onNotTodayTask(task) },
                )
            }
        }

        if (state.blocks.isNotEmpty()) {
            item {
                Text(
                    text = "Today's schedule",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.blocks, key = { it.id.value }) { block ->
                BlockRow(block = block, zone = state.zone)
            }
        }
    }
}

@Composable
private fun TodayHeader(
    inboxCount: Int,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Today", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = "Inbox · $inboxCount",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { contentDescription = "Open Settings" },
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null)
            }
        }
    }
}

@Composable
private fun TodayEmptyState(onOpenPlan: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Nothing on the books today",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "A clear day is a fine plan too. Add a block whenever you're ready.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onOpenPlan,
                modifier = Modifier.semantics { contentDescription = "Open Plan to add a block" },
            ) {
                Text("Open Plan")
            }
        }
    }
}

@Composable
private fun HighlightedBlockCard(
    block: TimeBlock,
    now: Instant,
    isInProgress: Boolean,
    onComplete: () -> Unit,
    onNotToday: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isInProgress) "Happening now" else "Up next",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(text = block.title ?: "Untitled block", style = MaterialTheme.typography.titleLarge)
            Text(
                text = startInLabel(now = now, startsAt = block.startsAt, endsAt = block.endsAt),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.semantics { contentDescription = "Complete ${block.title ?: "block"}" },
                ) {
                    Text("Complete")
                }
                OutlinedButton(
                    onClick = onNotToday,
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Move ${block.title ?: "block"} to tomorrow"
                        },
                ) {
                    Text("Not today")
                }
            }
        }
    }
}

@Composable
private fun FocusTaskRow(
    task: Task,
    onComplete: () -> Unit,
    onNotToday: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // weight keeps long titles from crushing the buttons into tall slivers
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = onComplete,
                    modifier = Modifier.semantics { contentDescription = "Complete ${task.title}" },
                ) {
                    Text("Done")
                }
                OutlinedButton(
                    onClick = onNotToday,
                    modifier = Modifier.semantics { contentDescription = "Move ${task.title} to tomorrow" },
                ) {
                    Text("Not today")
                }
            }
        }
    }
}

@Composable
private fun BlockRow(
    block: TimeBlock,
    zone: TimeZone,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = block.title ?: "Untitled block", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = if (block.allDay) "All day" else block.timeRangeLabel(zone),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun TimeBlock.timeRangeLabel(zone: TimeZone): String {
    val start = startsAt.toLocalDateTime(zone).time
    val end = endsAt.toLocalDateTime(zone).time
    return "$start – $end"
}

/**
 * The post-session feedback moment (council/stage5-retention-gemini.md §B §1) — "Session
 * wrap-up" / "{Title} · Scheduled for {duration}m" / "How did the time go?" verbatim, one row of
 * calm one-tap chips, a quiet "×" dismiss, collapsing into "Noted for next time." once recorded.
 * One lambda per chip (idiomatic Compose) plus the pill-selector/dismiss/confirmation branches
 * (§B 1.2-1.4) push this past LongParameterList/LongMethod's defaults.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun WrapUpCard(
    block: TimeBlock,
    isConfirming: Boolean,
    pendingAdjustment: PendingRanLongAdjustmentUiState?,
    onFinishedEarly: () -> Unit,
    onOnTime: () -> Unit,
    onBeginRanLong: () -> Unit,
    onSelectRanLongDelta: (Int) -> Unit,
    onConfirmRanLong: () -> Unit,
    onDidntHappen: () -> Unit,
    onReschedule: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier.padding(16.dp).semantics(mergeDescendants = true) {
                    contentDescription = wrapUpAccessibilityDescription(block)
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Session wrap-up", style = MaterialTheme.typography.labelLarge)
                // §B 1.4 "Explicit Dismissal" — hidden once the row has already collapsed into its
                // confirmation label, since there is nothing left to skip.
                if (!isConfirming) {
                    IconButton(
                        onClick = onDismiss,
                        modifier =
                            Modifier.heightIn(min = MIN_TOUCH_TARGET).semantics {
                                contentDescription =
                                    "Skip feedback"
                            },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
            }
            Text(wrapUpSubtitle(block), style = MaterialTheme.typography.bodyMedium)
            if (isConfirming) {
                // §B 1.3 point 2 — the single calm confirmation label; a real fade is the host
                // app's animation concern, this row just renders the collapsed state.
                Text("Noted for next time.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("How did the time go?", style = MaterialTheme.typography.bodyMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WrapUpChip(
                        label = "Finished early",
                        subtext = "-${MIN_ADJUSTMENT_DISPLAY_MINUTES}m",
                        icon = Icons.Filled.ArrowBack,
                        description = "Finished early",
                        onClick = onFinishedEarly,
                    )
                    WrapUpChip(
                        label = "Right on time",
                        subtext = "${block.plannedMinutes()}m",
                        icon = null,
                        description = "Right on time",
                        onClick = onOnTime,
                    )
                    WrapUpChip(
                        label = "Ran long",
                        subtext = "+${MIN_ADJUSTMENT_DISPLAY_MINUTES}m",
                        icon = Icons.Filled.ArrowForward,
                        description = "Ran long",
                        onClick = onBeginRanLong,
                    )
                    WrapUpChip(
                        label = "Didn't happen",
                        subtext = null,
                        icon = null,
                        description = "Didn't happen",
                        onClick = onDidntHappen,
                    )
                    WrapUpChip(
                        label = "Reschedule",
                        subtext = null,
                        icon = Icons.Filled.DateRange,
                        description = "Reschedule",
                        onClick = onReschedule,
                    )
                }
                if (pendingAdjustment != null) {
                    RanLongAdjustmentRow(
                        pendingAdjustment = pendingAdjustment,
                        onSelectDelta = onSelectRanLongDelta,
                        onConfirm = onConfirmRanLong,
                    )
                }
            }
        }
    }
}

/** §B 1.2's illustrative "-15m"/"+20m" subtexts as this house's actual, always-accurate presets:
 * "Finished early" records planned-15m (this seat's default); "Ran long"'s pill selector opens
 * pre-selected at +15m — both shown here as the same round number for a stable label whether or
 * not the row has ever been tapped. */
private const val MIN_ADJUSTMENT_DISPLAY_MINUTES = 15

@Composable
private fun WrapUpChip(
    label: String,
    subtext: String?,
    icon: ImageVector?,
    description: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET).semantics { contentDescription = description },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        label = {
            Column {
                Text(label)
                if (subtext != null) {
                    Text(subtext, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
    )
}

/** The Ran-long pill selector (§B 1.3 point 4): +15m/+30m/+45m, pre-selected pill highlighted, a
 * second tap on any pill (including the one already selected) confirms immediately — the
 * auto-save-after-3s path needs no UI of its own, [TodayViewModel] already schedules it. */
@Composable
private fun RanLongAdjustmentRow(
    pendingAdjustment: PendingRanLongAdjustmentUiState,
    onSelectDelta: (Int) -> Unit,
    onConfirm: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RAN_LONG_DELTA_OPTIONS_MINUTES.forEach { delta ->
            val selected = pendingAdjustment.selectedDeltaMinutes == delta
            FilterChip(
                selected = selected,
                onClick = { if (selected) onConfirm() else onSelectDelta(delta) },
                label = { Text("+${delta}m") },
                modifier =
                    Modifier.heightIn(min = MIN_TOUCH_TARGET).semantics {
                        contentDescription = "Ran long by $delta minutes"
                    },
            )
        }
        TextButton(
            onClick = onConfirm,
            modifier =
                Modifier
                    .heightIn(
                        min = MIN_TOUCH_TARGET,
                    ).semantics { contentDescription = "Save ran-long time" },
        ) {
            Text("Save")
        }
    }
}

/**
 * The Next-Time surface (council/stage5-retention-gemini.md §B §2) — shown only when
 * [NextTimeCardUiState] exists, i.e. only when the server's `sample_size >= 2` honesty threshold
 * produced a non-null, non-matching suggestion (see [TodayViewModel.refreshNextTimeSuggestion]).
 */
@Composable
private fun NextTimeCard(
    card: NextTimeCardUiState,
    onPropose: () -> Unit,
    onKeepStandard: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier.padding(16.dp).semantics(mergeDescendants = true) {
                    contentDescription = nextTimeAccessibilityDescription(card)
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (card.isSharedLock) "NEXT TIME TOGETHER" else "NEXT TIME",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(card.title, style = MaterialTheme.typography.titleMedium)
            Text(
                nextTimeBodyCopy(card.standardMinutes, card.suggestedMinutes),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPropose,
                    modifier =
                        Modifier.heightIn(min = MIN_TOUCH_TARGET).semantics {
                            contentDescription = "Propose ${card.suggestedMinutes} minutes"
                        },
                ) {
                    Text("Propose ${card.suggestedMinutes}m")
                }
                OutlinedButton(
                    onClick = onKeepStandard,
                    modifier =
                        Modifier.heightIn(min = MIN_TOUCH_TARGET).semantics {
                            contentDescription = "Keep ${card.standardMinutes} minutes"
                        },
                ) {
                    Text("Keep ${card.standardMinutes}m")
                }
                TextButton(
                    onClick = onSkip,
                    modifier =
                        Modifier.heightIn(min = MIN_TOUCH_TARGET).semantics {
                            contentDescription =
                                "Skip suggestion"
                        },
                ) {
                    Text("Skip")
                }
            }
        }
    }
}

/** Wires the real per-account repository from [dev.elay.di.AppGraph] (falls back to the shared fake outside it).
 * Also threads [LocalOutcomeRepository] (contracts/stage5-retention-hardening.md, this seat's grant) for the
 * wrap-up/next-time surfaces — same fallback-to-fake pattern as
 * [dev.elay.ui.together.TogetherScreen]'s [dev.elay.ui.together.proposal.LocalAvailabilityRepository] threading. */
@Composable
private fun rememberTodayViewModel(): TodayViewModel {
    val scope = rememberCoroutineScope()
    val repository = LocalPlannerRepository.current
    val outcomeRepository = LocalOutcomeRepository.current
    return remember(repository, outcomeRepository) {
        TodayViewModel(repository, scope, outcomeRepository = outcomeRepository)
    }
}
