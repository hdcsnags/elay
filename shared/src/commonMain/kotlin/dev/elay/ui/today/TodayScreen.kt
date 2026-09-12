package dev.elay.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.elay.di.LocalPlannerRepository
import dev.elay.domain.model.Task
import dev.elay.domain.model.TimeBlock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Today surface (brief §2): current/next block with a countdown, up to three
 * focus tasks, an inbox count, and the day's block list. Calm empty state —
 * one action, never a red banner (spec §2).
 */
@Composable
fun TodayScreen(
    onOpenPlan: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = rememberTodayViewModel(),
) {
    val state by viewModel.state.collectAsState()
    TodayContent(
        state = state,
        onCompleteBlock = viewModel::completeBlock,
        onNotTodayBlock = viewModel::notTodayBlock,
        onCompleteTask = viewModel::completeTask,
        onNotTodayTask = viewModel::notTodayTask,
        onOpenPlan = onOpenPlan,
        modifier = modifier,
    )
}

@Composable
private fun TodayContent(
    state: TodayUiState,
    onCompleteBlock: (TimeBlock) -> Unit,
    onNotTodayBlock: (TimeBlock) -> Unit,
    onCompleteTask: (Task) -> Unit,
    onNotTodayTask: (Task) -> Unit,
    onOpenPlan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TodayHeader(inboxCount = state.inboxCount) }

        if (state.isEmpty) {
            item { TodayEmptyState(onOpenPlan = onOpenPlan) }
            return@LazyColumn
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
private fun TodayHeader(inboxCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "Today", style = MaterialTheme.typography.headlineSmall)
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

/** Wires the real per-account repository from [dev.elay.di.AppGraph] (falls back to the shared fake outside it). */
@Composable
private fun rememberTodayViewModel(): TodayViewModel {
    val scope = rememberCoroutineScope()
    val repository = LocalPlannerRepository.current
    return remember(repository) { TodayViewModel(repository, scope) }
}
