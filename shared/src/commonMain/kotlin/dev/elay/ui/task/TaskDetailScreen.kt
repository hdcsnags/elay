package dev.elay.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.elay.di.LocalPlannerRepository
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TimeBlock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Task detail surface (Gate-1 deferred item): title, notes, status, priority, due window,
 * its linked block list, and complete / not-today / delete actions through
 * [dev.elay.domain.repository.PlannerRepository]. A goal link (when the task has one) opens
 * [dev.elay.ui.goal.GoalDetailScreen] via [onOpenGoal].
 */
@Composable
fun TaskDetailScreen(
    taskId: TaskId,
    onBack: () -> Unit = {},
    onOpenGoal: (GoalId) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TaskDetailViewModel = rememberTaskDetailViewModel(taskId),
) {
    val state by viewModel.state.collectAsState()
    var deleteConfirmPending by remember { mutableStateOf(false) }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    TaskDetailContent(
        state = state,
        deleteConfirmPending = deleteConfirmPending,
        onBack = onBack,
        onOpenGoal = onOpenGoal,
        onComplete = viewModel::complete,
        onNotToday = viewModel::notToday,
        onRequestDelete = { deleteConfirmPending = true },
        onCancelDelete = { deleteConfirmPending = false },
        onConfirmDelete = {
            deleteConfirmPending = false
            viewModel.delete()
        },
        modifier = modifier,
    )
}

@Suppress("LongParameterList") // state + one event lambda per user action — idiomatic Compose (detekt.yml)
@Composable
private fun TaskDetailContent(
    state: TaskDetailUiState,
    deleteConfirmPending: Boolean,
    onBack: () -> Unit,
    onOpenGoal: (GoalId) -> Unit,
    onComplete: () -> Unit,
    onNotToday: () -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackRow(onBack) }

        val task = state.task
        when {
            state.isLoading -> item { Text("Loading…", style = MaterialTheme.typography.bodyMedium) }
            state.notFound ->
                item {
                    Text(
                        text = "This task is no longer here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            task != null -> {
                item {
                    TaskSummaryCard(
                        task = task,
                        onOpenGoal = onOpenGoal,
                        onComplete = onComplete,
                        onNotToday = onNotToday,
                        onRequestDelete = onRequestDelete,
                    )
                }
                item { Text(text = "Linked time blocks", style = MaterialTheme.typography.titleMedium) }
                if (state.linkedBlocks.isEmpty()) {
                    item {
                        Text(
                            "No time blocks scheduled for this task yet.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    items(state.linkedBlocks, key = { it.id.value }) { block -> LinkedBlockRow(block) }
                }
            }
        }
    }

    if (deleteConfirmPending) {
        DeleteConfirmDialog(onConfirm = onConfirmDelete, onDismiss = onCancelDelete)
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    TextButton(
        onClick = onBack,
        modifier = Modifier.semantics { contentDescription = "Back" },
    ) {
        Text("← Back")
    }
}

@Composable
private fun TaskSummaryCard(
    task: Task,
    onOpenGoal: (GoalId) -> Unit,
    onComplete: () -> Unit,
    onNotToday: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = task.title, style = MaterialTheme.typography.titleLarge)
            task.notes?.let { notes -> Text(text = notes, style = MaterialTheme.typography.bodyMedium) }
            Text(
                text = "Status: ${task.status.wire} · Priority: ${task.priority.value}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(text = dueWindowLabel(task), style = MaterialTheme.typography.bodyMedium)
            val goalId = task.goalId
            if (goalId != null) {
                TextButton(
                    onClick = { onOpenGoal(goalId) },
                    modifier = Modifier.semantics { contentDescription = "View linked goal" },
                ) {
                    Text("View goal")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.semantics { contentDescription = "Complete ${task.title}" },
                ) {
                    Text("Complete")
                }
                OutlinedButton(
                    onClick = onNotToday,
                    modifier = Modifier.semantics { contentDescription = "Move ${task.title} to tomorrow" },
                ) {
                    Text("Not today")
                }
                TextButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.semantics { contentDescription = "Delete ${task.title}" },
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

private fun dueWindowLabel(task: Task): String {
    val start = task.dueStart
    val end = task.dueEnd
    val zone = TimeZone.currentSystemDefault()
    return when {
        start == null && end == null -> "No due date"
        start != null && end != null ->
            "Due ${start.toLocalDateTime(zone).date} – ${end.toLocalDateTime(zone).date}"
        start != null -> "Due ${start.toLocalDateTime(zone).date}"
        else -> "Due by ${end?.toLocalDateTime(zone)?.date}"
    }
}

@Composable
private fun LinkedBlockRow(block: TimeBlock) {
    val zone = TimeZone.currentSystemDefault()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = block.title ?: "Untitled block", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "${block.startsAt.toLocalDateTime(zone).time} · ${block.status.wire}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this task?") },
        text = { Text("This can't be undone. Its linked time blocks stay on your schedule, unlinked.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "Confirm delete" },
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Keep task" },
            ) {
                Text("Keep it")
            }
        },
    )
}

/** Wires the real per-account repository from [dev.elay.di.AppGraph] (falls back to the shared
 * fake outside it), matching the `ui/today|inbox|plan` house pattern. */
@Composable
private fun rememberTaskDetailViewModel(taskId: TaskId): TaskDetailViewModel {
    val scope = rememberCoroutineScope()
    val repository = LocalPlannerRepository.current
    return remember(taskId, repository) { TaskDetailViewModel(taskId, repository, scope) }
}
