package dev.elay.ui.today

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.repository.PlannerRepository
import dev.elay.ui.util.dayWindow
import dev.elay.ui.util.dueShiftedByOneDay
import dev.elay.ui.util.shiftedByOneDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Today surface state (brief §2): the day's blocks, top focus tasks, and the inbox count chip. */
data class TodayUiState(
    val zone: TimeZone = TimeZone.currentSystemDefault(),
    val now: Instant? = null,
    val blocks: List<TimeBlock> = emptyList(),
    val focusTasks: List<Task> = emptyList(),
    val inboxCount: Int = 0,
) {
    /** The block in progress right now, if any. */
    val currentBlock: TimeBlock?
        get() = now?.let { moment -> blocks.firstOrNull { moment >= it.startsAt && moment < it.endsAt } }

    /** The soonest upcoming block, used when nothing is in progress. */
    val nextBlock: TimeBlock?
        get() = now?.let { moment -> blocks.filter { it.startsAt > moment }.minByOrNull { it.startsAt } }

    val isEmpty: Boolean get() = blocks.isEmpty() && focusTasks.isEmpty()
}

/** Max focus tasks shown on Today (spec §2 — "max 3 focus outcomes", priority overload guardrail). */
const val MAX_FOCUS_TASKS = 3

/**
 * Plain, testable ViewModel (no android.lifecycle dependency): owns Today's
 * observation of the repository and the Complete / Not-today actions.
 */
class TodayViewModel(
    private val repository: PlannerRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val _state = MutableStateFlow(TodayUiState(zone = zone))
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    init {
        val today = clock.now().toLocalDateTime(zone).date
        val window = dayWindow(today, zone)
        combine(
            repository.observeBlocks(window.start, window.endExclusive),
            repository.observeTodayTasks(window.start, window.endInclusive),
            repository.observeInbox(),
        ) { blocks, tasks, inbox -> Triple(blocks, tasks, inbox) }
            .onEach { (blocks, tasks, inbox) ->
                _state.update {
                    it.copy(
                        now = clock.now(),
                        blocks = blocks.sortedBy { block -> block.startsAt },
                        focusTasks = tasks.activeFocusOrder(),
                        inboxCount = inbox.size,
                    )
                }
            }.launchIn(scope)
    }

    /** Refreshes the "now" marker so countdown text stays current between repository emissions. */
    fun refreshNow() {
        _state.update { it.copy(now = clock.now()) }
    }

    fun completeBlock(block: TimeBlock) {
        scope.launch { repository.upsertBlock(block.copy(status = BlockStatus.Completed)) }
    }

    /** Reschedules rather than fails the plan (spec §2 — no shame mechanics). */
    fun notTodayBlock(block: TimeBlock) {
        scope.launch { repository.upsertBlock(block.shiftedByOneDay(zone)) }
    }

    fun completeTask(task: Task) {
        scope.launch { repository.upsertTask(task.copy(status = TaskStatus.Completed)) }
    }

    fun notTodayTask(task: Task) {
        scope.launch { repository.upsertTask(task.dueShiftedByOneDay(zone)) }
    }
}

private fun List<Task>.activeFocusOrder(): List<Task> =
    filter { it.status == TaskStatus.Todo || it.status == TaskStatus.InProgress }
        .sortedByDescending { it.priority.value }
        .take(MAX_FOCUS_TASKS)
