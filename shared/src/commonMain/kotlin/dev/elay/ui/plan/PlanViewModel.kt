package dev.elay.ui.plan

import dev.elay.domain.model.Task
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.repository.PlannerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Plan surface state (brief §4): a day's timeline plus the unscheduled-task rail. */
data class PlanUiState(
    val date: LocalDate,
    val zone: TimeZone,
    val isToday: Boolean,
    val timelineBlocks: List<TimeBlock> = emptyList(),
    val allDayBlocks: List<TimeBlock> = emptyList(),
    val unscheduledTasks: List<Task> = emptyList(),
    val selectedBlock: TimeBlock? = null,
)

/**
 * Plain, testable ViewModel for the Plan day timeline: day navigation
 * (prev/today/next), block selection for a detail placeholder, and the
 * unscheduled-task rail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModel(
    private val repository: PlannerRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private val selectedDate = MutableStateFlow(today())
    private val _state = MutableStateFlow(PlanUiState(date = selectedDate.value, zone = zone, isToday = true))
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    init {
        selectedDate
            .flatMapLatest { date ->
                val window = planWindow(date, zone)
                combine(
                    repository.observeBlocks(window.start, window.endExclusive),
                    repository.observeUnscheduledTasks(),
                ) { blocks, unscheduled -> PlanDaySnapshot(date, blocks, unscheduled) }
            }.onEach { snapshot ->
                _state.update {
                    it.copy(
                        date = snapshot.date,
                        isToday = snapshot.date == today(),
                        timelineBlocks = snapshot.blocks.filterNot { block -> block.allDay },
                        allDayBlocks = snapshot.blocks.filter { block -> block.allDay },
                        unscheduledTasks = snapshot.unscheduledTasks,
                        selectedBlock = null,
                    )
                }
            }.launchIn(scope)
    }

    fun selectPreviousDay() {
        selectedDate.update { it.minus(1, DateTimeUnit.DAY) }
    }

    fun selectNextDay() {
        selectedDate.update { it.plus(1, DateTimeUnit.DAY) }
    }

    fun selectToday() {
        selectedDate.update { today() }
    }

    fun selectBlock(block: TimeBlock) {
        _state.update { it.copy(selectedBlock = block) }
    }

    fun dismissBlockDetail() {
        _state.update { it.copy(selectedBlock = null) }
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(zone).date
}

private data class PlanDaySnapshot(
    val date: LocalDate,
    val blocks: List<TimeBlock>,
    val unscheduledTasks: List<Task>,
)
