package dev.elay.ui.goal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import dev.elay.di.LocalPlannerRepository
import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.GoalStatus
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.Task

/**
 * Goal detail surface (Gate-1 deferred item): goal summary, milestones, and its linked tasks as
 * a read-only list, plus a status-change action. No nav-bar entry per the pivot — reached from a
 * task detail's goal link (registered as a route regardless, per the frozen contract).
 */
@Composable
fun GoalDetailScreen(
    goalId: GoalId,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: GoalDetailViewModel = rememberGoalDetailViewModel(goalId),
) {
    val state by viewModel.state.collectAsState()
    GoalDetailContent(
        state = state,
        onBack = onBack,
        onSetStatus = viewModel::setStatus,
        modifier = modifier,
    )
}

@Composable
private fun GoalDetailContent(
    state: GoalDetailUiState,
    onBack: () -> Unit,
    onSetStatus: (GoalStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackRow(onBack) }

        val goal = state.goal
        when {
            state.isLoading -> item { Text("Loading…", style = MaterialTheme.typography.bodyMedium) }
            state.notFound ->
                item {
                    Text(
                        text = "This goal is no longer here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            goal != null -> {
                item { GoalSummaryCard(goal = goal, onSetStatus = onSetStatus) }
                item { Text(text = "Milestones", style = MaterialTheme.typography.titleMedium) }
                if (state.milestones.isEmpty()) {
                    item { Text("No milestones yet.", style = MaterialTheme.typography.bodyMedium) }
                } else {
                    items(state.milestones, key = { it.id.value }) { milestone -> MilestoneRow(milestone) }
                }
                item { Text(text = "Linked tasks", style = MaterialTheme.typography.titleMedium) }
                if (state.tasks.isEmpty()) {
                    item { Text("No tasks linked to this goal yet.", style = MaterialTheme.typography.bodyMedium) }
                } else {
                    items(state.tasks, key = { it.id.value }) { task -> LinkedTaskRow(task) }
                }
            }
        }
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
private fun GoalSummaryCard(
    goal: Goal,
    onSetStatus: (GoalStatus) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = goal.title, style = MaterialTheme.typography.titleLarge)
            goal.notes?.let { notes -> Text(text = notes, style = MaterialTheme.typography.bodyMedium) }
            Text(
                text = goal.targetDate?.let { "Target: $it" } ?: "No target date",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Status: ${goal.status.wire}",
                style = MaterialTheme.typography.labelLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalStatus.entries.filter { it != goal.status }.forEach { status ->
                    OutlinedButton(
                        onClick = { onSetStatus(status) },
                        modifier = Modifier.semantics { contentDescription = "Mark goal as ${status.wire}" },
                    ) {
                        Text("Mark ${status.wire}")
                    }
                }
            }
        }
    }
}

@Composable
private fun MilestoneRow(milestone: Milestone) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = milestone.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = milestone.targetDate?.let { "${milestone.status.wire} · $it" } ?: milestone.status.wire,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LinkedTaskRow(task: Task) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = task.title, style = MaterialTheme.typography.bodyLarge)
        Text(text = task.status.wire, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Wires the real per-account repository from [dev.elay.di.AppGraph] (falls back to the shared
 * fake outside it), matching the `ui/today|inbox|plan` house pattern. */
@Composable
private fun rememberGoalDetailViewModel(goalId: GoalId): GoalDetailViewModel {
    val scope = rememberCoroutineScope()
    val repository = LocalPlannerRepository.current
    return remember(goalId, repository) { GoalDetailViewModel(goalId, repository, scope) }
}
