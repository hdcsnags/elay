package dev.elay.ui.plan

import dev.elay.domain.availability.AvailabilityRepository
import dev.elay.domain.availability.ExternalBusyResult
import dev.elay.domain.model.Task
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import dev.elay.domain.repository.PlannerRepository
import dev.elay.ui.together.networkFailureMessage
import dev.elay.ui.together.proposal.fake.sharedFakeAvailabilityRepository
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
    /** Stage-4 capacity gauge (contracts/stage4-honest-availability.md; this seat's grant §5),
     * recomputed alongside [timelineBlocks] every day-snapshot update — see this file's `init`. */
    val capacity: CapacityGaugeState = capacityGaugeFor(emptyList()),
    /** Non-null while the manual "I'm busy then" sheet (this seat's grant §4) is open. */
    val manualBusySheet: ManualBusyDraftState? = null,
    /** This-session-only optimistic echo of upserted manual busy rows — see [ManualBusyEntry]'s
     * kdoc for why there is no durable read-back. */
    val manualBusyEntries: List<ManualBusyEntry> = emptyList(),
)

/**
 * Plain, testable ViewModel for the Plan day timeline: day navigation
 * (prev/today/next), block selection for a detail placeholder, the
 * unscheduled-task rail, and the "Add block" sheet (brief §1), plus the manual busy sheet and
 * capacity gauge (this seat's grant §4-§5) — one facade, hence the suppressions below.
 */
@Suppress("TooManyFunctions", "LongParameterList")
@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModel(
    private val repository: PlannerRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val ownerId: UserId = LOCAL_OWNER_ID,
    private val availabilityRepository: AvailabilityRepository = sharedFakeAvailabilityRepository,
    private val newBlockId: () -> String = { defaultScheduleBlockId() },
    private val newBusyId: () -> String = { defaultManualBusyId() },
    private val newOperationId: () -> String = { defaultManualBusyId() },
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
                val timed = snapshot.blocks.filterNot { block -> block.allDay }
                _state.update {
                    it.copy(
                        date = snapshot.date,
                        isToday = snapshot.date == today(),
                        timelineBlocks = timed,
                        allDayBlocks = snapshot.blocks.filter { block -> block.allDay },
                        unscheduledTasks = snapshot.unscheduledTasks,
                        selectedBlock = null,
                        capacity = capacityGaugeFor(timed),
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

    /** Opens the manual "I'm busy then" sheet (this seat's grant §4, council/stage4-availability-gemini.md
     * §3.2) for the day currently shown — always the Plan day, never separately editable, mirroring
     * [openAddBlockSheet]. */
    fun openManualBusySheet() {
        _state.update { it.copy(manualBusySheet = manualBusyDraftFor(it.date, zone)) }
    }

    fun dismissManualBusySheet() {
        _state.update { it.copy(manualBusySheet = null) }
    }

    fun updateManualBusyLabel(text: String) {
        _state.update { it.copy(manualBusySheet = it.manualBusySheet?.withLabel(text)) }
    }

    fun stepManualBusyStartTime(deltaMinutes: Int) {
        _state.update { it.copy(manualBusySheet = it.manualBusySheet?.stepStartTime(deltaMinutes)) }
    }

    fun setManualBusyDuration(minutes: Int) {
        _state.update { it.copy(manualBusySheet = it.manualBusySheet?.withDuration(minutes)) }
    }

    /** "Mark busy" (§3.2's single-tap save): `rpc_upsert_external_busy` with a client-generated
     * [newBusyId] and `origin_tz` from the DEVICE's actual current zone
     * (`TimeZone.currentSystemDefault().id` — deliverable 4's exact requirement), which may differ
     * from [zone] (this ViewModel's own configurable rendering zone, mockable in tests) — a manual
     * busy entry's provenance should always be the zone the device was physically in when the user
     * tapped Save, not whatever zone Plan happens to be rendering in. On success, appends the
     * caller's own optimistic echo to [PlanUiState.manualBusyEntries] and closes the sheet; on
     * failure, keeps it open with a calm retry message (same shape as
     * [dev.elay.ui.together.proposal.TogetherProposalViewModel]'s mutation handling). */
    fun saveManualBusy() {
        val sheet = _state.value.manualBusySheet ?: return
        val busyId = newBusyId()
        val (start, end) = sheet.toInstantRange()
        val label = sheet.label.trim().ifBlank { null }
        _state.update { it.copy(manualBusySheet = it.manualBusySheet?.copy(isSaving = true, errorMessage = null)) }
        scope.launch {
            val result =
                availabilityRepository.upsertManualBusy(
                    operationId = newOperationId(),
                    busyId = busyId,
                    startsAt = start,
                    endsAt = end,
                    originZoneId = TimeZone.currentSystemDefault().id,
                )
            when (result) {
                ExternalBusyResult.Applied ->
                    _state.update {
                        it.copy(
                            manualBusySheet = null,
                            manualBusyEntries = it.manualBusyEntries + ManualBusyEntry(busyId, start, end, label),
                        )
                    }
                is ExternalBusyResult.Failed ->
                    _state.update {
                        it.copy(
                            manualBusySheet =
                                it.manualBusySheet?.copy(
                                    isSaving = false,
                                    errorMessage = networkFailureMessage(result.retryable),
                                ),
                        )
                    }
            }
        }
    }

    /** Removes one this-session manual busy entry (§3.2's "edit-last-created" allowance extended to
     * every entry this process itself created — see [ManualBusyEntry]'s kdoc for why nothing else
     * is ever shown here to remove). A failed delete leaves the entry visible for a retry rather
     * than silently dropping it from the list. */
    fun deleteManualBusy(busyId: String) {
        scope.launch {
            val result = availabilityRepository.deleteManualBusy(newOperationId(), busyId)
            if (result is ExternalBusyResult.Applied) {
                _state.update {
                    it.copy(
                        manualBusyEntries =
                            it.manualBusyEntries.filterNot { entry ->
                                entry.busyId ==
                                    busyId
                            },
                    )
                }
            }
        }
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

/** Client-generated id for a manual busy row (`kotlin.uuid`, `@OptIn` as elsewhere in this house) —
 * also reused as the manual-busy sheet's default `newOperationId` generator, since both are just a
 * fresh random UUID string with no other shape requirement. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun defaultManualBusyId(): String =
    kotlin.uuid.Uuid
        .random()
        .toString()
