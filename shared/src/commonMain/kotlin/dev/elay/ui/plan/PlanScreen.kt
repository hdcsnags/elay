@file:Suppress("TooManyFunctions") // one small composable per sheet/row element — idiomatic Compose decomposition

package dev.elay.ui.plan

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.elay.ui.together.proposal.LocalAvailabilityRepository
import dev.elay.ui.together.proposal.buildDualTimeLines
import dev.elay.ui.together.proposal.dualTimeAccessibilityDescription
import dev.elay.ui.together.proposal.formatClockTime
import dev.elay.ui.together.proposal.formatDatePrefix
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val HOUR_HEIGHT: Dp = 56.dp
private val TIMELINE_HEIGHT: Dp = HOUR_HEIGHT * (PLAN_WINDOW_END_HOUR - PLAN_WINDOW_START_HOUR)

/** §B 2.3's 4-segment capacity track. */
private const val CAPACITY_GAUGE_SEGMENT_COUNT = 4

/** §B 2.3's "warm muted amber" stretched-overflow tone — never
 * `MaterialTheme.colorScheme.error`, in both themes (§B 1.4/2.3's "never red" rule). */
private val CAPACITY_OVERFLOW_COLOR = Color(0xFFFFB74D)

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
        onOpenManualBusySheet = viewModel::openManualBusySheet,
        onManualBusyStartTimeStep = viewModel::stepManualBusyStartTime,
        onManualBusyDurationPreset = viewModel::setManualBusyDuration,
        onManualBusyLabelChange = viewModel::updateManualBusyLabel,
        onSaveManualBusy = viewModel::saveManualBusy,
        onDismissManualBusySheet = viewModel::dismissManualBusySheet,
        onDeleteManualBusyEntry = viewModel::deleteManualBusy,
        modifier = modifier,
    )
}

// state + one event lambda per user action — idiomatic Compose (detekt.yml)
@Suppress("LongParameterList", "LongMethod")
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
    onOpenManualBusySheet: () -> Unit,
    onManualBusyStartTimeStep: (Int) -> Unit,
    onManualBusyDurationPreset: (Int) -> Unit,
    onManualBusyLabelChange: (String) -> Unit,
    onSaveManualBusy: () -> Unit,
    onDismissManualBusySheet: () -> Unit,
    onDeleteManualBusyEntry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlanHeader(onAddBlock = onAddBlock, onMarkBusy = onOpenManualBusySheet)
        DaySwitcher(state = state, onPreviousDay = onPreviousDay, onNextDay = onNextDay, onToday = onToday)
        // Capacity gauge (contracts/stage4-honest-availability.md; council/stage4-availability-gemini.md
        // §2.2 "gauge home… below the DaySwitcher") — this seat's grant §5.
        CapacityGaugeRow(gauge = state.capacity)

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

    state.manualBusySheet?.let { sheet ->
        ManualBusySheet(
            sheetState = sheet,
            entries = state.manualBusyEntries,
            onStartTimeStep = onManualBusyStartTimeStep,
            onDurationPreset = onManualBusyDurationPreset,
            onLabelChange = onManualBusyLabelChange,
            onSave = onSaveManualBusy,
            onDismiss = onDismissManualBusySheet,
            onDeleteEntry = onDeleteManualBusyEntry,
        )
    }
}

@Composable
private fun PlanHeader(
    onAddBlock: () -> Unit,
    onMarkBusy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Plan", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // §B 3.2's "[+ Busy]" auxiliary trigger, placed next to "[+ Add block]".
            OutlinedButton(
                onClick = onMarkBusy,
                modifier = Modifier.semantics { contentDescription = "Mark busy time" },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Busy")
            }
            Button(
                onClick = onAddBlock,
                modifier = Modifier.semantics { contentDescription = "Add a time block" },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Add block")
            }
        }
    }
}

/**
 * §B 2.3's four-segment capacity track + calm status line, mounted in Plan's top rail directly
 * below [DaySwitcher] (§B 2.2's exact gauge home). Color-independent (§B 5.4): every level also
 * carries its own [capacityGaugeCopy] text, never color alone.
 */
@Composable
private fun CapacityGaugeRow(
    gauge: CapacityGaugeState,
    modifier: Modifier = Modifier,
) {
    val filledSegments =
        when (gauge.level) {
            CapacityLevel.Comfortable -> 1
            CapacityLevel.Balanced -> 2
            CapacityLevel.Full -> 3
            CapacityLevel.Stretched -> CAPACITY_GAUGE_SEGMENT_COUNT
        }
    val fillColor =
        when (gauge.level) {
            CapacityLevel.Comfortable -> MaterialTheme.colorScheme.tertiary
            CapacityLevel.Balanced -> MaterialTheme.colorScheme.primary
            CapacityLevel.Full, CapacityLevel.Stretched -> MaterialTheme.colorScheme.secondary
        }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier =
            modifier.fillMaxWidth().semantics {
                progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        current = gauge.plannedMinutes.toFloat(),
                        range = 0f..gauge.budgetMinutes.toFloat(),
                    )
                stateDescription = capacityGaugeAccessibilityDescription(gauge)
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            // A plain `for` loop, not `repeat`/`forEach` — keeps every `Box` a direct child
            // expression of this `RowScope` lambda so `Modifier.weight` resolves unambiguously
            // (a nested plain lambda broke `RowScope.weight` resolution on the iOS/Native targets).
            for (segmentIndex in 0 until CAPACITY_GAUGE_SEGMENT_COUNT) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(
                                color = if (segmentIndex < filledSegments) fillColor else trackColor,
                                shape = RoundedCornerShape(3.dp),
                            ),
                )
            }
        }
        if (gauge.level == CapacityLevel.Stretched) {
            // §2.3's "quiet trailing dash extension in warm muted amber" — never red, never flashing.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(color = CAPACITY_OVERFLOW_COLOR, shape = RoundedCornerShape(1.5.dp)),
            )
        }
        Text(
            capacityGaugeCopy(gauge),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * The manual "I'm busy then" sheet (contracts/stage4-honest-availability.md;
 * council/stage4-availability-gemini.md §3.2): date (fixed to the Plan day it was opened from,
 * §3.2), a start-time stepper + 30/60/90m duration presets, and an optional label — "the lightest-
 * possible UI surface", zero category/priority/goal-linkage requirement. [entries] is this-session's
 * optimistic echo only (UNVERIFIED gap — see [ManualBusyEntry]'s kdoc): a row this process itself
 * successfully upserted, each removable via [onDeleteEntry].
 *
 * (One param per §3.2 field + entry list, mirroring [AddBlockSheet]'s shape — hence the
 * `LongParameterList`/`LongMethod` suppressions below.)
 */
@Suppress("LongParameterList", "LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualBusySheet(
    sheetState: ManualBusyDraftState,
    entries: List<ManualBusyEntry>,
    onStartTimeStep: (Int) -> Unit,
    onDurationPreset: (Int) -> Unit,
    onLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onDeleteEntry: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Mark external busy time", style = MaterialTheme.typography.titleMedium)
            Text("Date: ${formatDatePrefix(sheetState.date)}", style = MaterialTheme.typography.bodyMedium)
            StepperRow(
                label = "Start ${formatClockTime(sheetState.startTime)}",
                onDecrement = { onStartTimeStep(-MANUAL_BUSY_TIME_STEP_MINUTES) },
                onIncrement = { onStartTimeStep(MANUAL_BUSY_TIME_STEP_MINUTES) },
                decrementDescription = "Earlier start time",
                incrementDescription = "Later start time",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MANUAL_BUSY_DURATION_PRESETS.forEach { minutes ->
                    FilterChip(
                        selected = sheetState.durationMinutes == minutes,
                        onClick = { onDurationPreset(minutes) },
                        label = { Text("${minutes}m") },
                        modifier = Modifier.semantics { contentDescription = "$minutes minutes" },
                    )
                }
            }
            OutlinedTextField(
                value = sheetState.label,
                onValueChange = onLabelChange,
                label = { Text("Note (optional)") },
                placeholder = { Text("e.g., Doctor, Dentist, Flight") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Busy note" },
            )
            sheetState.errorMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entries.isNotEmpty()) {
                Text("Added this session", style = MaterialTheme.typography.titleSmall)
                entries.forEach { entry ->
                    ManualBusyEntryRow(
                        entry = entry,
                        zone = sheetState.zone,
                        onDelete = { onDeleteEntry(entry.busyId) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = onSave,
                    enabled = !sheetState.isSaving,
                    modifier = Modifier.semantics { contentDescription = "Mark busy" },
                ) {
                    Text(if (sheetState.isSaving) "Marking busy…" else "Mark busy")
                }
            }
        }
    }
}

@Composable
private fun ManualBusyEntryRow(
    entry: ManualBusyEntry,
    zone: TimeZone,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val start = entry.startsAt.toLocalDateTime(zone).time
        val end = entry.endsAt.toLocalDateTime(zone).time
        Text(
            "${formatClockTime(start)} – ${formatClockTime(end)}" + (entry.label?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "Remove busy time" }) {
            Text("Remove")
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
 * (falls back to the shared fake/local owner outside it). Also threads
 * [LocalAvailabilityRepository] (contracts/stage4-honest-availability.md, this seat's grant) for
 * the manual busy sheet + capacity gauge — same fallback-to-fake pattern as the planner repository. */
@Composable
private fun rememberPlanViewModel(): PlanViewModel {
    val scope = rememberCoroutineScope()
    val repository = LocalPlannerRepository.current
    val ownerId = LocalCurrentUserId.current
    val availabilityRepository = LocalAvailabilityRepository.current
    return remember(repository, ownerId, availabilityRepository) {
        PlanViewModel(repository, scope, ownerId = ownerId, availabilityRepository = availabilityRepository)
    }
}
