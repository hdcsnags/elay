package dev.elay.ui.plan

import dev.elay.domain.availability.ExternalBusyResult
import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.Priority
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import dev.elay.ui.fake.FakePlannerRepository
import dev.elay.ui.fake.PlannerSeed
import dev.elay.ui.together.proposal.fake.FakeAvailabilityRepository
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class PlanViewModelTest {
    private val zone = TimeZone.UTC
    private val ownerId = UserId("owner-1")
    private val today = LocalDate(2026, 6, 15)
    private val now: Instant = LocalDateTime(2026, 6, 15, 8, 0).toInstant(zone)

    @Test
    fun startsOnTodayAndNavigatesForwardAndBack() =
        runTest {
            val viewModel = PlanViewModel(fakeRepository(), backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()
            assertEquals(today, viewModel.state.value.date)
            assertTrue(viewModel.state.value.isToday)

            viewModel.selectNextDay()
            runCurrent()
            assertEquals(LocalDate(2026, 6, 16), viewModel.state.value.date)
            assertTrue(!viewModel.state.value.isToday)

            viewModel.selectPreviousDay()
            runCurrent()
            assertEquals(today, viewModel.state.value.date)
            assertTrue(viewModel.state.value.isToday)
        }

    @Test
    fun selectTodayReturnsFromAnyOtherDay() =
        runTest {
            val viewModel = PlanViewModel(fakeRepository(), backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            viewModel.selectNextDay()
            viewModel.selectNextDay()
            viewModel.selectToday()
            runCurrent()

            assertEquals(today, viewModel.state.value.date)
        }

    @Test
    fun blocksAreSplitBetweenTimelineAndAllDayForTheSelectedDay() =
        runTest {
            val timed = block("b-timed", startsAt = now, endsAt = now.plusHour(), allDay = false)
            val allDay =
                block(
                    "b-all-day",
                    startsAt = LocalDateTime(2026, 6, 15, 0, 0).toInstant(zone),
                    endsAt = LocalDateTime(2026, 6, 16, 0, 0).toInstant(zone),
                    allDay = true,
                )
            val repository = fakeRepository(blocks = listOf(timed, allDay))
            val viewModel = PlanViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            assertEquals(
                listOf(TimeBlockId("b-timed")),
                viewModel.state.value.timelineBlocks
                    .map { it.id },
            )
            assertEquals(
                listOf(TimeBlockId("b-all-day")),
                viewModel.state.value.allDayBlocks
                    .map { it.id },
            )
        }

    @Test
    fun unscheduledTasksAreAlwaysExposedRegardlessOfSelectedDay() =
        runTest {
            val unscheduled = task("t-unscheduled")
            val repository = fakeRepository(tasks = listOf(unscheduled))
            val viewModel = PlanViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            assertEquals(
                listOf(TaskId("t-unscheduled")),
                viewModel.state.value.unscheduledTasks
                    .map { it.id },
            )

            viewModel.selectNextDay()
            runCurrent()
            assertEquals(
                listOf(TaskId("t-unscheduled")),
                viewModel.state.value.unscheduledTasks
                    .map { it.id },
            )
        }

    @Test
    fun selectingAndDismissingABlockUpdatesTheDetailPlaceholderState() =
        runTest {
            val timed = block("b-timed", startsAt = now, endsAt = now.plusHour())
            val repository = fakeRepository(blocks = listOf(timed))
            val viewModel = PlanViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            viewModel.selectBlock(timed)
            assertEquals(
                timed.id,
                viewModel.state.value.selectedBlock
                    ?.id,
            )

            viewModel.dismissBlockDetail()
            assertNull(viewModel.state.value.selectedBlock)
        }

    @Test
    fun openingTheAddBlockSheetFreshDefaultsToTheCurrentlyShownDayAndNoLinkedTask() =
        runTest {
            val viewModel = PlanViewModel(fakeRepository(), backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            viewModel.openAddBlockSheet()

            val sheet = viewModel.state.value.addBlockSheet
            assertEquals(today, sheet?.date)
            assertEquals(zone, sheet?.zone)
            assertNull(sheet?.linkedTaskId)
            assertEquals("", sheet?.title)
        }

    @Test
    fun openingTheAddBlockSheetFromAnUnscheduledTaskPrefillsTheLinkAndTitle() =
        runTest {
            val unscheduled = task("t-unscheduled")
            val repository = fakeRepository(tasks = listOf(unscheduled))
            val viewModel = PlanViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            viewModel.openAddBlockSheet(unscheduled)

            val sheet = viewModel.state.value.addBlockSheet
            assertEquals(unscheduled.id, sheet?.linkedTaskId)
            assertEquals(unscheduled.title, sheet?.title)
        }

    @Test
    fun dismissingTheSheetClearsItWithoutTouchingTheRepository() =
        runTest {
            val viewModel = PlanViewModel(fakeRepository(), backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()
            viewModel.openAddBlockSheet()

            viewModel.dismissAddBlockSheet()

            assertNull(viewModel.state.value.addBlockSheet)
        }

    @Test
    fun steppingStartTimeAndDurationUpdatesOnlyTheSheet() =
        runTest {
            val viewModel = PlanViewModel(fakeRepository(), backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()
            viewModel.openAddBlockSheet()

            viewModel.stepSheetStartTime(SCHEDULE_TIME_STEP_MINUTES)
            viewModel.stepSheetDuration(SCHEDULE_TIME_STEP_MINUTES)

            val sheet = viewModel.state.value.addBlockSheet
            assertEquals(LocalTime(9, 15), sheet?.startTime)
            assertEquals(45, sheet?.durationMinutes)
        }

    @Test
    fun linkingATaskToAnAlreadyOpenSheetAdoptsItsTitleOnlyWhenTitleWasBlank() =
        runTest {
            val unscheduled = task("t-unscheduled")
            val repository = fakeRepository(tasks = listOf(unscheduled))
            val viewModel = PlanViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()
            viewModel.openAddBlockSheet()

            viewModel.linkSheetTask(unscheduled)

            val linked = viewModel.state.value.addBlockSheet
            assertEquals(unscheduled.id, linked?.linkedTaskId)
            assertEquals(unscheduled.title, linked?.title)

            viewModel.updateSheetTitle("My own title")
            viewModel.linkSheetTask(null)

            val unlinked = viewModel.state.value.addBlockSheet
            assertNull(unlinked?.linkedTaskId)
            assertEquals("My own title", unlinked?.title)
        }

    @Test
    fun savingTheSheetUpsertsTheBuiltBlockAndClosesTheSheet() =
        runTest {
            val repository = fakeRepository()
            val viewModel =
                PlanViewModel(
                    repository,
                    backgroundScope,
                    clock = fixedClock(now),
                    zone = zone,
                    ownerId = ownerId,
                    newBlockId = { "b-new" },
                )
            runCurrent()
            viewModel.openAddBlockSheet()
            viewModel.updateSheetTitle("Study session")

            viewModel.saveScheduledBlock()
            runCurrent()

            assertNull(viewModel.state.value.addBlockSheet)
            val timelineBlocks = viewModel.state.value.timelineBlocks
            val saved = timelineBlocks.singleOrNull { it.id == TimeBlockId("b-new") }
            assertEquals("Study session", saved?.title)
            assertEquals(ownerId, saved?.ownerId)
        }

    @Test
    fun savingWithNoOpenSheetIsANoOp() =
        runTest {
            val repository = fakeRepository()
            val viewModel = PlanViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            viewModel.saveScheduledBlock()
            runCurrent()

            val finalState = viewModel.state.value
            assertTrue(finalState.timelineBlocks.isEmpty())
            assertTrue(finalState.allDayBlocks.isEmpty())
        }

    // --- Stage 4 honest availability: capacity gauge (this seat's grant §5) ---

    @Test
    fun capacityStartsComfortableWithNoBlocksAndReflectsAddedBlocksAfterSaving() =
        runTest {
            val viewModel =
                PlanViewModel(
                    fakeRepository(),
                    backgroundScope,
                    clock = fixedClock(now),
                    zone = zone,
                    newBlockId = { "b-cap" },
                )
            runCurrent()
            assertEquals(CapacityLevel.Comfortable, viewModel.state.value.capacity.level)
            assertEquals(0, viewModel.state.value.capacity.plannedMinutes)

            viewModel.openAddBlockSheet()
            viewModel.stepSheetDuration(360) // 30m default -> 6h30m (390/480 = 81% -> Full)
            viewModel.saveScheduledBlock()
            runCurrent()

            assertEquals(CapacityLevel.Full, viewModel.state.value.capacity.level)
        }

    @Test
    fun capacityLevelsMatchEachBudgetBand() {
        assertEquals(CapacityLevel.Comfortable, capacityLevelFor(0.0f))
        assertEquals(CapacityLevel.Comfortable, capacityLevelFor(0.49f))
        assertEquals(CapacityLevel.Balanced, capacityLevelFor(0.5f))
        assertEquals(CapacityLevel.Balanced, capacityLevelFor(0.79f))
        assertEquals(CapacityLevel.Full, capacityLevelFor(0.8f))
        assertEquals(CapacityLevel.Full, capacityLevelFor(1.0f))
        assertEquals(CapacityLevel.Stretched, capacityLevelFor(1.01f))
    }

    // --- Stage 4 honest availability: manual "I'm busy then" sheet (this seat's grant §4) ---

    @Test
    fun openingTheManualBusySheetDefaultsToTheCurrentlyShownDay() =
        runTest {
            val viewModel = PlanViewModel(fakeRepository(), backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            viewModel.openManualBusySheet()

            val sheet = viewModel.state.value.manualBusySheet
            assertEquals(today, sheet?.date)
            assertEquals(zone, sheet?.zone)
        }

    @Test
    fun savingManualBusyUpsertsAndAppendsAnOptimisticEchoThenClosesTheSheet() =
        runTest {
            val availability = FakeAvailabilityRepository()
            availability.nextUpsertResult = ExternalBusyResult.Applied
            val viewModel =
                PlanViewModel(
                    fakeRepository(),
                    backgroundScope,
                    clock = fixedClock(now),
                    zone = zone,
                    availabilityRepository = availability,
                    newBusyId = { "busy-1" },
                )
            runCurrent()
            viewModel.openManualBusySheet()
            viewModel.updateManualBusyLabel("Dentist")

            viewModel.saveManualBusy()
            runCurrent()

            assertNull(viewModel.state.value.manualBusySheet)
            val entry =
                viewModel.state.value.manualBusyEntries
                    .singleOrNull()
            assertEquals("busy-1", entry?.busyId)
            assertEquals("Dentist", entry?.label)
            val call = availability.upsertCalls.single()
            assertEquals("busy-1", call.busyId)
            assertEquals(TimeZone.currentSystemDefault().id, call.originZoneId)
        }

    @Test
    fun aFailedManualBusySaveKeepsTheSheetOpenWithACalmRetryMessage() =
        runTest {
            val availability = FakeAvailabilityRepository()
            availability.nextUpsertResult = ExternalBusyResult.Failed("connection_reset_by_peer", retryable = true)
            val viewModel =
                PlanViewModel(
                    fakeRepository(),
                    backgroundScope,
                    clock = fixedClock(now),
                    zone = zone,
                    availabilityRepository = availability,
                )
            runCurrent()
            viewModel.openManualBusySheet()

            viewModel.saveManualBusy()
            runCurrent()

            val sheet = viewModel.state.value.manualBusySheet
            assertEquals("Couldn't reach the server — try again in a moment.", sheet?.errorMessage)
            assertTrue(
                viewModel.state.value.manualBusyEntries
                    .isEmpty(),
            )
        }

    @Test
    fun deletingAManualBusyEntryRemovesItOnlyWhenTheServerApplies() =
        runTest {
            val availability = FakeAvailabilityRepository()
            availability.nextUpsertResult = ExternalBusyResult.Applied
            val viewModel =
                PlanViewModel(
                    fakeRepository(),
                    backgroundScope,
                    clock = fixedClock(now),
                    zone = zone,
                    availabilityRepository = availability,
                    newBusyId = { "busy-2" },
                )
            runCurrent()
            viewModel.openManualBusySheet()
            viewModel.saveManualBusy()
            runCurrent()
            assertEquals(1, viewModel.state.value.manualBusyEntries.size)

            availability.nextDeleteResult = ExternalBusyResult.Failed("connection_reset_by_peer", retryable = true)
            viewModel.deleteManualBusy("busy-2")
            runCurrent()
            assertEquals(1, viewModel.state.value.manualBusyEntries.size) // failed delete leaves it visible

            availability.nextDeleteResult = ExternalBusyResult.Applied
            viewModel.deleteManualBusy("busy-2")
            runCurrent()
            assertTrue(
                viewModel.state.value.manualBusyEntries
                    .isEmpty(),
            )
        }

    private fun Instant.plusHour(): Instant = this + 1.hours

    private fun task(id: String) =
        Task(
            id = TaskId(id),
            ownerId = ownerId,
            goalId = null,
            milestoneId = null,
            title = id,
            notes = null,
            status = TaskStatus.Todo,
            priority = Priority.Normal,
            effort = null,
            estimateMinutes = null,
            dueStart = null,
            dueEnd = null,
            recurrenceRule = null,
            tags = emptyList(),
            version = 1,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

    private fun block(
        id: String,
        startsAt: Instant,
        endsAt: Instant,
        allDay: Boolean = false,
    ) = TimeBlock(
        id = TimeBlockId(id),
        ownerId = ownerId,
        taskId = null,
        title = id,
        startsAt = startsAt,
        endsAt = endsAt,
        originTz = zone,
        type = BlockType.Personal,
        status = BlockStatus.Scheduled,
        recurrenceRule = null,
        allDay = allDay,
        version = 1,
    )

    private fun fakeRepository(
        tasks: List<Task> = emptyList(),
        blocks: List<TimeBlock> = emptyList(),
    ) = FakePlannerRepository(
        PlannerSeed(
            goals = emptyList(),
            milestones = emptyList(),
            tasks = tasks,
            captures = emptyList(),
            blocks = blocks,
        ),
    )

    private fun fixedClock(instant: Instant): kotlin.time.Clock =
        object : kotlin.time.Clock {
            override fun now(): Instant = instant
        }
}
