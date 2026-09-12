package dev.elay.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.elay.di.LocalPlannerRepository
import dev.elay.domain.model.TimeBlock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val HOUR_HEIGHT: Dp = 56.dp
private val TIMELINE_HEIGHT: Dp = HOUR_HEIGHT * (PLAN_WINDOW_END_HOUR - PLAN_WINDOW_START_HOUR)

/**
 * Plan surface (brief §4): a day timeline (06:00-24:00) with prev/today/next
 * navigation, blocks positioned by start/duration, an unscheduled-task rail,
 * and a tap-to-detail placeholder (goal/task detail screens land with C2).
 */
@Composable
fun PlanScreen(
    modifier: Modifier = Modifier,
    viewModel: PlanViewModel = rememberPlanViewModel(),
) {
    val state by viewModel.state.collectAsState()
    PlanContent(
        state = state,
        onPreviousDay = viewModel::selectPreviousDay,
        onNextDay = viewModel::selectNextDay,
        onToday = viewModel::selectToday,
        onSelectBlock = viewModel::selectBlock,
        onDismissDetail = viewModel::dismissBlockDetail,
        modifier = modifier,
    )
}

@Composable
private fun PlanContent(
    state: PlanUiState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onSelectBlock: (TimeBlock) -> Unit,
    onDismissDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DaySwitcher(state = state, onPreviousDay = onPreviousDay, onNextDay = onNextDay, onToday = onToday)

        if (state.allDayBlocks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.allDayBlocks.forEach { block ->
                    AssistChip(
                        onClick = { onSelectBlock(block) },
                        label = { Text(block.title ?: "All day") },
                        modifier =
                            Modifier.semantics {
                                contentDescription = "All-day block: ${block.title ?: "Untitled"}"
                            },
                    )
                }
            }
        }

        if (state.timelineBlocks.isEmpty() && state.allDayBlocks.isEmpty()) {
            PlanEmptyState(isToday = state.isToday, onToday = onToday)
        } else {
            DayTimeline(state = state, onSelectBlock = onSelectBlock)
        }

        UnscheduledRail(state = state)
    }

    state.selectedBlock?.let { block ->
        BlockDetailDialog(block = block, zone = state.zone, onDismiss = onDismissDetail)
    }
}

@Composable
private fun DaySwitcher(
    state: PlanUiState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(
            onClick = onPreviousDay,
            modifier = Modifier.semantics { contentDescription = "Previous day" },
        ) {
            Text("Prev")
        }
        TextButton(
            onClick = onToday,
            modifier = Modifier.semantics { contentDescription = "Jump to today" },
        ) {
            Text(if (state.isToday) "Today" else state.date.toString())
        }
        OutlinedButton(
            onClick = onNextDay,
            modifier = Modifier.semantics { contentDescription = "Next day" },
        ) {
            Text("Next")
        }
    }
}

@Composable
private fun PlanEmptyState(
    isToday: Boolean,
    onToday: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nothing on the calendar for this day", style = MaterialTheme.typography.titleMedium)
            if (!isToday) {
                OutlinedButton(
                    onClick = onToday,
                    modifier = Modifier.semantics { contentDescription = "Back to today" },
                ) {
                    Text("Back to today")
                }
            }
        }
    }
}

@Composable
private fun DayTimeline(
    state: PlanUiState,
    onSelectBlock: (TimeBlock) -> Unit,
) {
    val window = planWindow(state.date, state.zone)
    Box(modifier = Modifier.fillMaxWidth().height(TIMELINE_HEIGHT)) {
        state.timelineBlocks.forEach { block ->
            val position = blockPosition(block, window.start, window.endExclusive) ?: return@forEach
            val top = TIMELINE_HEIGHT * position.topFraction
            val height = TIMELINE_HEIGHT * position.heightFraction
            Card(
                onClick = { onSelectBlock(block) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp)
                        .offset(y = top)
                        .height(height),
            ) {
                Text(
                    text = block.title ?: "Untitled block",
                    modifier =
                        Modifier
                            .padding(8.dp)
                            .semantics { contentDescription = "Open detail for ${block.title ?: "block"}" },
                )
            }
        }
    }
}

@Composable
private fun UnscheduledRail(state: PlanUiState) {
    if (state.unscheduledTasks.isEmpty()) return
    Column {
        Text("Unscheduled", style = MaterialTheme.typography.titleMedium)
        LazyRow(
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.unscheduledTasks, key = { it.id.value }) { task ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(text = task.title, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun BlockDetailDialog(
    block: TimeBlock,
    zone: TimeZone,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Close block detail" },
            ) {
                Text("Close")
            }
        },
        title = { Text(block.title ?: "Untitled block") },
        text = {
            val start = block.startsAt.toLocalDateTime(zone).time
            val end = block.endsAt.toLocalDateTime(zone).time
            Text(if (block.allDay) "All day" else "$start – $end")
        },
    )
}

/** Wires the real per-account repository from [dev.elay.di.AppGraph] (falls back to the shared fake outside it). */
@Composable
private fun rememberPlanViewModel(): PlanViewModel {
    val scope = rememberCoroutineScope()
    val repository = LocalPlannerRepository.current
    return remember(repository) { PlanViewModel(repository, scope) }
}
