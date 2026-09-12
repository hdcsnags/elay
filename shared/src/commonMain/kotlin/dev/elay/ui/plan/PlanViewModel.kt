package dev.elay.ui.plan

import dev.elay.domain.model.Task
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import dev.elay.domain.repository.PlannerRepository
import dev.elay.ui.util.LOCAL_OWNER_ID
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
import kotlinx.coroutines.launch
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
    /** Non-null while the "Add block" sheet (brief §1) is open. */
    val addBlockSheet: ScheduleSheetState? = null,
)

/**
 * Plain, testable ViewModel for the Plan day timeline: day navigation
 * (prev/today/next), block selection for a detail placeholder, the
 * unscheduled-task rail, and the "Add block" sheet (brief §1).
 */
@Suppress("TooManyFunctions") // day nav + block selection + the add-block sheet, one facade (brief §1)
@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModel(
    private val repository: PlannerRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val ownerId: UserId = LOCAL_OWNER_ID,
    private val newBlockId: () -> String = { defaultScheduleBlockId() },
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

    /** Opens the "Add block" sheet (brief §1) for the day currently shown, optionally prefilled
     * from an unscheduled-rail task — the rail's own "Schedule" button and the header's plain
     * "Add block" button both call this, the latter with no [task]. */
    fun openAddBlockSheet(task: Task? = null) {
        _state.update { current ->
            current.copy(
                addBlockSheet =
                    ScheduleSheetState(
                        date = current.date,
                        zone = zone,
                        linkedTaskId = task?.id,
                        title = task?.title.orEmpty(),
                    ),
            )
        }
    }

    fun dismissAddBlockSheet() {
        _state.update { it.copy(addBlockSheet = null) }
    }

    fun updateSheetTitle(title: String) {
        _state.update { it.copy(addBlockSheet = it.addBlockSheet?.copy(title = title)) }
    }

    fun stepSheetStartTime(deltaMinutes: Int) {
        _state.update { current ->
            val sheet = current.addBlockSheet ?: return@update current
            current.copy(addBlockSheet = sheet.copy(startTime = sheet.startTime.stepBy(deltaMinutes)))
        }
    }

    fun stepSheetDuration(deltaMinutes: Int) {
        _state.update { current ->
            val sheet = current.addBlockSheet ?: return@update current
            val next =
                (sheet.durationMinutes + deltaMinutes)
                    .coerceIn(MIN_BLOCK_DURATION_MINUTES, MAX_BLOCK_DURATION_MINUTES)
            current.copy(addBlockSheet = sheet.copy(durationMinutes = next))
        }
    }

    /** Links (or unlinks, for a `null` [task]) the sheet's task; an empty title adopts the newly
     * linked task's own title rather than overwriting whatever the user already typed. */
    fun linkSheetTask(task: Task?) {
        _state.update { current ->
            val sheet = current.addBlockSheet ?: return@update current
            val nextTitle = if (task != null && sheet.title.isBlank()) task.title else sheet.title
            current.copy(addBlockSheet = sheet.copy(linkedTaskId = task?.id, title = nextTitle))
        }
    }

    /** Builds the block from the sheet's current state and enqueues it, then closes the sheet
     * optimistically — [PlannerRepository.upsertBlock] stages the local row and returns fast. */
    fun saveScheduledBlock() {
        val sheet = _state.value.addBlockSheet ?: return
        val block = buildScheduledBlock(TimeBlockId(newBlockId()), ownerId, sheet)
        scope.launch { repository.upsertBlock(block) }
        _state.update { it.copy(addBlockSheet = null) }
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(zone).date
}

private data class PlanDaySnapshot(
    val date: LocalDate,
    val blocks: List<TimeBlock>,
    val unscheduledTasks: List<Task>,
)

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun defaultScheduleBlockId(): String =
    kotlin.uuid.Uuid
        .random()
        .toString()
