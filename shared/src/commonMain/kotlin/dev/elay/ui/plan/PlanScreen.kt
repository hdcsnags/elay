@file:Suppress("TooManyFunctions") // one small composable per sheet/row element — idiomatic Compose decomposition

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.elay.di.LocalCurrentUserId
import dev.elay.di.LocalPlannerRepository
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.PairState
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TimeBlock
import dev.elay.ui.together.LocalPairRepository
import dev.elay.ui.together.proposal.buildDualTimeLines
import dev.elay.ui.together.proposal.dualTimeAccessibilityDescription
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

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
        onAddBlock = { viewModel.openAddBlockSheet() },
        onScheduleTask = viewModel::openAddBlockSheet,
        onSheetTitleChange = viewModel::updateSheetTitle,
        onSheetStartTimeStep = viewModel::stepSheetStartTime,
        onSheetDurationStep = viewModel::stepSheetDuration,
        onSheetLinkTask = viewModel::linkSheetTask,
        onSaveScheduledBlock = viewModel::saveScheduledBlock,
        onDismissAddBlockSheet = viewModel::dismissAddBlockSheet,
        modifier = modifier,
    )
}

@Suppress("LongParameterList") // state + one event lambda per user action — idiomatic Compose (detekt.yml)
@Composable
private fun PlanContent(
    state: PlanUiState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onSelectBlock: (TimeBlock) -> Unit,
    onDismissDetail: () -> Unit,
    onAddBlock: () -> Unit,
    onScheduleTask: (Task) -> Unit,
    onSheetTitleChange: (String) -> Unit,
    onSheetStartTimeStep: (Int) -> Unit,
    onSheetDurationStep: (Int) -> Unit,
    onSheetLinkTask: (Task?) -> Unit,
    onSaveScheduledBlock: () -> Unit,
    onDismissAddBlockSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlanHeader(onAddBlock = onAddBlock)
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

        UnscheduledRail(state = state, onScheduleTask = onScheduleTask)
    }

    state.selectedBlock?.let { block ->
        BlockDetailDialog(block = block, zone = state.zone, onDismiss = onDismissDetail)
    }

    state.addBlockSheet?.let { sheet ->
        AddBlockSheet(
            sheetState = sheet,
            unscheduledTasks = state.unscheduledTasks,
            onTitleChange = onSheetTitleChange,
            onStartTimeStep = onSheetStartTimeStep,
            onDurationStep = onSheetDurationStep,
            onLinkTask = onSheetLinkTask,
            onSave = onSaveScheduledBlock,
            onDismiss = onDismissAddBlockSheet,
        )
    }
}

@Composable
private fun PlanHeader(onAddBlock: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Plan", style = MaterialTheme.typography.headlineSmall)
        Button(
            onClick = onAddBlock,
            modifier = Modifier.semantics { contentDescription = "Add a time block" },
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(" Add block")
        }
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
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = block.title ?: "Untitled block",
                        modifier =
                            Modifier.semantics { contentDescription = "Open detail for ${block.title ?: "block"}" },
                    )
                    if (block.type == BlockType.SharedLock) {
                        SharedLockMarker(block = block, viewerZone = state.zone)
                    }
                }
            }
        }
    }
}

/**
 * The "together" affordance + dual-time line for a [BlockType.SharedLock] block
 * (contracts/stage2-timelock.md lead amendment 1; council/stage2-timelock-gemini.md §3's dual-time
 * rule). `null`-safe: renders nothing if this process isn't currently Paired (a shared-lock block
 * shouldn't exist without a partner, but a stale/edge-case snapshot must not crash Plan).
 */
@Composable
private fun SharedLockMarker(
    block: TimeBlock,
    viewerZone: TimeZone,
) {
    val pairState by LocalPairRepository.current.observePair().collectAsState()
    val selfId = LocalCurrentUserId.current
    val partner = (pairState as? PairState.Paired)?.snapshot?.members?.firstOrNull { it.userId != selfId } ?: return
    val partnerZone = TimeZone.of(partner.homeTz)
    val now = Clock.System.now()
    val lines =
        buildDualTimeLines(
            startsAt = block.startsAt,
            endsAt = block.endsAt,
            viewerZone = viewerZone,
            partnerZone = partnerZone,
            partnerDisplayName = partner.displayName,
            now = now,
            includeDate = false,
        )
    val description =
        "Together block. " +
            dualTimeAccessibilityDescription(block.startsAt, block.endsAt, viewerZone, partnerZone, partner.displayName)
    Column(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(imageVector = Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(12.dp))
            Text("Together", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            lines.viewerLine,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        )
        Text(
            lines.partnerLine,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnscheduledRail(
    state: PlanUiState,
    onScheduleTask: (Task) -> Unit,
) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.title,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        )
                        TextButton(
                            onClick = { onScheduleTask(task) },
                            modifier = Modifier.semantics { contentDescription = "Schedule ${task.title}" },
                        ) {
                            Text("Schedule")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Plan "Add block" sheet (brief §1): optional task link, title, a fixed date (always the
 * Plan day it was opened from), and a start-time/duration stepper — "simple pickers" over an M3
 * `TimePicker`, which needs its own extra `@OptIn` for no behavioral gain here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBlockSheet(
    sheetState: ScheduleSheetState,
    unscheduledTasks: List<Task>,
    onTitleChange: (String) -> Unit,
    onStartTimeStep: (Int) -> Unit,
    onDurationStep: (Int) -> Unit,
    onLinkTask: (Task?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add a block for ${sheetState.date}", style = MaterialTheme.typography.titleMedium)
            TaskLinkField(
                unscheduledTasks = unscheduledTasks,
                linkedTaskId = sheetState.linkedTaskId,
                onLinkTask = onLinkTask,
            )
            OutlinedTextField(
                value = sheetState.title,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Block title" },
            )
            StepperRow(
                label = "Start ${sheetState.startTime}",
                onDecrement = { onStartTimeStep(-SCHEDULE_TIME_STEP_MINUTES) },
                onIncrement = { onStartTimeStep(SCHEDULE_TIME_STEP_MINUTES) },
                decrementDescription = "Earlier start time",
                incrementDescription = "Later start time",
            )
            StepperRow(
                label = "Duration ${sheetState.durationMinutes} min",
                onDecrement = { onDurationStep(-SCHEDULE_TIME_STEP_MINUTES) },
                onIncrement = { onDurationStep(SCHEDULE_TIME_STEP_MINUTES) },
                decrementDescription = "Shorter duration",
                incrementDescription = "Longer duration",
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = onSave,
                    modifier = Modifier.semantics { contentDescription = "Save block" },
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun TaskLinkField(
    unscheduledTasks: List<Task>,
    linkedTaskId: TaskId?,
    onLinkTask: (Task?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = unscheduledTasks.find { it.id == linkedTaskId }?.title ?: "No task"
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Link a task: $selectedLabel" },
        ) {
            Text("Task: $selectedLabel")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("No task") },
                onClick = {
                    onLinkTask(null)
                    expanded = false
                },
            )
            unscheduledTasks.forEach { task ->
                DropdownMenuItem(
                    text = { Text(task.title) },
                    onClick = {
                        onLinkTask(task)
                        expanded = false
                    },
                )
            }
        }
    }
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
            Column {
                val start = block.startsAt.toLocalDateTime(zone).time
                val end = block.endsAt.toLocalDateTime(zone).time
                Text(if (block.allDay) "All day" else "$start – $end")
                if (block.type == BlockType.SharedLock) {
                    SharedLockMarker(block = block, viewerZone = zone)
                }
            }
        },
    )
}

/** Wires the real per-account repository + signed-in owner id from [dev.elay.di.AppGraph]
 * (falls back to the shared fake/local owner outside it). */
@Composable
private fun rememberPlanViewModel(): PlanViewModel {
    val scope = rememberCoroutineScope()
    val repository = LocalPlannerRepository.current
    val ownerId = LocalCurrentUserId.current
    return remember(repository, ownerId) { PlanViewModel(repository, scope, ownerId = ownerId) }
}
