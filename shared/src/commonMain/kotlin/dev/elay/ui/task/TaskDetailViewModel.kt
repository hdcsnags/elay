package dev.elay.ui.task

import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.repository.PlannerRepository
import dev.elay.ui.util.dueShiftedByOneDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * Task detail surface state (Gate-1 deferred item): the task itself, its linked time blocks
 * ([PlannerRepository.observeBlocksForTask]), and the three actions the route owns — complete,
 * not-today (reschedule, spec §2 — no shame mechanics), and delete.
 */
data class TaskDetailUiState(
    val task: Task? = null,
    val linkedBlocks: List<TimeBlock> = emptyList(),
    val isLoading: Boolean = true,
    /** Set once [TaskDetailViewModel.delete] completes — the screen navigates back on this. */
    val deleted: Boolean = false,
) {
    val notFound: Boolean get() = !isLoading && task == null && !deleted
}

/**
 * Plain, testable ViewModel (house pattern) for Task detail: loads the task once, observes its
 * linked blocks, and drives complete / not-today / delete through [PlannerRepository].
 */
class TaskDetailViewModel(
    private val taskId: TaskId,
    private val repository: PlannerRepository,
    private val scope: CoroutineScope,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val _state = MutableStateFlow(TaskDetailUiState())
    val state: StateFlow<TaskDetailUiState> = _state.asStateFlow()

    init {
        scope.launch {
            val task = repository.task(taskId)
            _state.update { it.copy(task = task, isLoading = false) }
        }
        repository
            .observeBlocksForTask(taskId)
            .onEach { blocks ->
                _state.update { it.copy(linkedBlocks = blocks.sortedBy { block -> block.startsAt }) }
            }.launchIn(scope)
    }

    fun complete() = updateStatus(TaskStatus.Completed)

    /** "Not today" — reschedules the due window a day forward rather than marking a miss. */
    fun notToday() {
        val task = _state.value.task ?: return
        val shifted = task.dueShiftedByOneDay(zone)
        scope.launch { repository.upsertTask(shifted) }
        _state.update { it.copy(task = shifted) }
    }

    fun delete() {
        val task = _state.value.task ?: return
        scope.launch { repository.deleteTask(task.id) }
        _state.update { it.copy(task = null, deleted = true) }
    }

    private fun updateStatus(status: TaskStatus) {
        val task = _state.value.task ?: return
        if (task.status == status) return
        val updated = task.copy(status = status)
        scope.launch { repository.upsertTask(updated) }
        _state.update { it.copy(task = updated) }
    }
}
