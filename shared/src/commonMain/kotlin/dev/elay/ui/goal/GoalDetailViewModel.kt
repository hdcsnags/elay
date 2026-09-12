package dev.elay.ui.goal

import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.GoalStatus
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.Task
import dev.elay.domain.repository.PlannerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Goal detail surface state (Gate-1 deferred item, brief: "reached later from task detail's goal
 * link"): the goal itself, its milestones, and its linked tasks as a read-only list — this
 * screen renders no task detail of its own, matching contracts/stage1-pairing.md's disjoint-seat
 * spirit (task actions live in `ui/task`, not duplicated here).
 */
data class GoalDetailUiState(
    val goal: Goal? = null,
    val milestones: List<Milestone> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
) {
    /** The goal id resolved to nothing — e.g. it was deleted from another surface. */
    val notFound: Boolean get() = !isLoading && goal == null
}

/**
 * Plain, testable ViewModel (house pattern) for Goal detail: loads the goal once, observes its
 * milestones ([PlannerRepository.observeMilestones]) and linked tasks
 * ([PlannerRepository.observeTasksForGoal]), and exposes the one action this screen owns — a
 * goal status change.
 */
class GoalDetailViewModel(
    private val goalId: GoalId,
    private val repository: PlannerRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val _state = MutableStateFlow(GoalDetailUiState())
    val state: StateFlow<GoalDetailUiState> = _state.asStateFlow()

    init {
        scope.launch {
            val goal = repository.goal(goalId)
            _state.update { it.copy(goal = goal, isLoading = false) }
        }
        combine(
            repository.observeMilestones(goalId),
            repository.observeTasksForGoal(goalId),
        ) { milestones, tasks -> milestones.sortedBy { it.sortOrder } to tasks }
            .onEach { (milestones, tasks) ->
                _state.update { it.copy(milestones = milestones, tasks = tasks) }
            }.launchIn(scope)
    }

    fun setStatus(status: GoalStatus) {
        val goal = _state.value.goal ?: return
        if (goal.status == status) return
        val updated = goal.copy(status = status, version = goal.version + 1, updatedAt = clock.now())
        scope.launch { repository.upsertGoal(updated) }
        _state.update { it.copy(goal = updated) }
    }
}
