package dev.elay.ui.today

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.CaptureSource
import dev.elay.domain.model.NextTimeSuggestion
import dev.elay.domain.model.NextTimeSuggestionResult
import dev.elay.domain.model.ParseStatus
import dev.elay.domain.model.Priority
import dev.elay.domain.model.RecordOutcomeResult
import dev.elay.domain.model.SessionOutcome
import dev.elay.domain.model.SessionOutcomeId
import dev.elay.domain.model.SessionOutcomeKind
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import dev.elay.ui.fake.FakePlannerRepository
import dev.elay.ui.fake.PlannerSeed
import dev.elay.ui.today.fake.FakeOutcomeRepository
import dev.elay.ui.util.dayWindow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class TodayViewModelTest {
    private val zone = TimeZone.UTC
    private val ownerId = UserId("owner-1")

    @Test
    fun todayFilteringIncludesExactBoundaryDueDatesAndExcludesAdjacentDays() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours

            val atStart = task("t-start", dueStart = window.start)
            val atEndInclusive = task("t-end", dueStart = window.endExclusive - 1.nanoseconds)
            val justBefore = task("t-before", dueStart = window.start - 1.nanoseconds)
            val atNextMidnight = task("t-next-midnight", dueStart = window.endExclusive)
            val unscheduled = task("t-unscheduled", dueStart = null)

            val repository =
                fakeRepository(
                    tasks = listOf(atStart, atEndInclusive, justBefore, atNextMidnight, unscheduled),
                )
            val viewModel = TodayViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            val focusIds =
                viewModel.state.value.focusTasks
                    .map { it.id }
            assertTrue(TaskId("t-start") in focusIds, "task due exactly at day start must be included")
            assertTrue(TaskId("t-end") in focusIds, "task due at the last instant of the day must be included")
            assertTrue(TaskId("t-before") !in focusIds, "task due before the day window must be excluded")
            assertTrue(TaskId("t-next-midnight") !in focusIds, "task due at next midnight belongs to tomorrow")
            assertTrue(TaskId("t-unscheduled") !in focusIds, "unscheduled tasks are not part of today")
        }

    @Test
    fun focusTasksAreCappedAtThreeSortedByPriorityDescendingAndExcludeDoneOrCancelled() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours
            val due = window.start + 2.hours

            val low = task("t-low", dueStart = due, priority = Priority.Low)
            val normal = task("t-normal", dueStart = due, priority = Priority.Normal)
            val high = task("t-high", dueStart = due, priority = Priority.High)
            val urgent = task("t-urgent", dueStart = due, priority = Priority.Urgent)
            val completed = task("t-done", dueStart = due, priority = Priority.Urgent, status = TaskStatus.Completed)
            val cancelled =
                task("t-cancelled", dueStart = due, priority = Priority.Urgent, status = TaskStatus.Cancelled)

            val repository =
                fakeRepository(tasks = listOf(low, normal, high, urgent, completed, cancelled))
            val viewModel = TodayViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            val focus = viewModel.state.value.focusTasks
            assertEquals(3, focus.size)
            assertEquals(listOf(TaskId("t-urgent"), TaskId("t-high"), TaskId("t-normal")), focus.map { it.id })
        }

    @Test
    fun inboxCountReflectsUnparsedCaptures() =
        runTest {
            val captures =
                listOf(
                    capture("c-1", ParseStatus.Unparsed),
                    capture("c-2", ParseStatus.Unparsed),
                    capture("c-3", ParseStatus.Dismissed),
                )
            val repository = fakeRepository(captures = captures)
            val viewModel =
                TodayViewModel(repository, backgroundScope, clock = fixedClock(Instant.fromEpochMilliseconds(0)))
            runCurrent()

            assertEquals(2, viewModel.state.value.inboxCount)
        }

    @Test
    fun completeBlockMarksItCompletedThroughTheRepository() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours
            val block = block("b-1", startsAt = now, endsAt = now + 1.hours)
            val repository = fakeRepository(blocks = listOf(block))
            val viewModel = TodayViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            viewModel.completeBlock(block)
            runCurrent()

            assertEquals(
                BlockStatus.Completed,
                viewModel.state.value.blocks
                    .single { it.id == block.id }
                    .status,
            )
        }

    @Test
    fun currentBlockSkipsAnAlreadyCompletedBlockInProgressWindow() =
        runTest {
            // Regression (Gate 1 deferred bug): "Up next" offered Complete on a block whose
            // window covers `now` but whose status is already Completed/Cancelled.
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours
            val completed =
                block("b-completed", startsAt = now - 10.minutes, endsAt = now + 10.minutes)
                    .copy(status = BlockStatus.Completed)
            val cancelled =
                block("b-cancelled", startsAt = now - 10.minutes, endsAt = now + 10.minutes)
                    .copy(status = BlockStatus.Cancelled)
            val repository = fakeRepository(blocks = listOf(completed, cancelled))
            val viewModel = TodayViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            assertNull(viewModel.state.value.currentBlock)
        }

    @Test
    fun nextBlockSkipsAnAlreadyCompletedOrCancelledUpcomingBlock() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours
            val completedSoon =
                block("b-completed", startsAt = now + 10.minutes, endsAt = now + 40.minutes)
                    .copy(status = BlockStatus.Completed)
            val scheduledLater =
                block("b-scheduled", startsAt = now + 1.hours, endsAt = now + 90.minutes)
            val repository = fakeRepository(blocks = listOf(completedSoon, scheduledLater))
            val viewModel = TodayViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            assertEquals(
                TimeBlockId("b-scheduled"),
                viewModel.state.value.nextBlock
                    ?.id,
            )
        }

    @Test
    fun notTodayBlockMovesItToTomorrowWithoutChangingItsDuration() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours
            val block = block("b-1", startsAt = now, endsAt = now + 30.minutes)
            val repository = fakeRepository(blocks = listOf(block))
            val viewModel = TodayViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            viewModel.notTodayBlock(block)
            runCurrent()

            // Rescheduled a day forward, so it no longer shows up in today's window.
            assertTrue(
                viewModel.state.value.blocks
                    .none { it.id == block.id },
            )
        }

    // --- Stage 5: post-session wrap-up (contracts/stage5-retention-hardening.md; council §B) ---

    @Test
    fun wrapUpBlockAppearsForAnElapsedStillScheduledBlockWithinTheFourHourTtl() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 3.hours
            val elapsed = block("b-elapsed", startsAt = now - 90.minutes, endsAt = now - 30.minutes)
            val repository = fakeRepository(blocks = listOf(elapsed))
            val viewModel = TodayViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            assertEquals(
                TimeBlockId("b-elapsed"),
                viewModel.state.value.wrapUpBlock
                    ?.id,
            )
        }

    @Test
    fun wrapUpBlockExpiresAfterTheFourHourTtl() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 6.hours
            val staleElapsed = block("b-stale", startsAt = now - 5.hours - 30.minutes, endsAt = now - 5.hours)
            val repository = fakeRepository(blocks = listOf(staleElapsed))
            val viewModel = TodayViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            assertNull(viewModel.state.value.wrapUpBlock)
        }

    @Test
    fun wrapUpBlockAppearsForAnExplicitlyCompletedBlockEvenBeforeItsScheduledEnd() =
        runTest {
            // §B 1.1 "Explicit Completion" — the user's own Complete tap can happen early (finished
            // early), so a Completed block is eligible regardless of endsAt-vs-now.
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours
            val completedEarly =
                block("b-completed-early", startsAt = now - 10.minutes, endsAt = now + 50.minutes)
                    .copy(status = BlockStatus.Completed)
            val repository = fakeRepository(blocks = listOf(completedEarly))
            val viewModel = TodayViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            assertEquals(
                TimeBlockId("b-completed-early"),
                viewModel.state.value.wrapUpBlock
                    ?.id,
            )
        }

    @Test
    fun wrapUpBlockPicksTheMostRecentlyEndedWhenMultipleAreEligible() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 3.hours
            val olderElapsed = block("b-older", startsAt = now - 2.hours, endsAt = now - 90.minutes)
            val newerElapsed = block("b-newer", startsAt = now - 40.minutes, endsAt = now - 10.minutes)
            val repository = fakeRepository(blocks = listOf(olderElapsed, newerElapsed))
            val viewModel = TodayViewModel(repository, backgroundScope, clock = fixedClock(now), zone = zone)
            runCurrent()

            assertEquals(
                TimeBlockId("b-newer"),
                viewModel.state.value.wrapUpBlock
                    ?.id,
            )
        }

    @Test
    fun recordFinishedEarlyRecordsPlannedMinusFifteenMinutes() =
        runTest {
            val (viewModel, outcomes, block) = elapsedBlockViewModel(plannedMinutes = 60)
            outcomes.nextRecordResult = appliedResult(block)

            viewModel.recordFinishedEarly(block)
            runCurrent()

            val call = outcomes.recordCalls.single()
            assertEquals(block.id.value, call.timeBlockId)
            assertEquals(SessionOutcomeKind.FinishedEarly, call.outcome)
            assertEquals(45, call.actualMinutes)
            assertTrue(call.operationId.isNotBlank())
            assertTrue(block.id.value in viewModel.state.value.recordedOutcomeBlockIds)
        }

    @Test
    fun recordOnTimeRecordsFinishedEarlyWithActualEqualToPlanned() =
        runTest {
            // SessionOutcomeKind has no "on time" member (closed four-value set) — this folds into
            // FinishedEarly with a zero delta rather than inventing a fifth kind.
            val (viewModel, outcomes, block) = elapsedBlockViewModel(plannedMinutes = 60)
            outcomes.nextRecordResult = appliedResult(block)

            viewModel.recordOnTime(block)
            runCurrent()

            val call = outcomes.recordCalls.single()
            assertEquals(SessionOutcomeKind.FinishedEarly, call.outcome)
            assertEquals(60, call.actualMinutes)
        }

    @Test
    fun recordDidntHappenCarriesNoActualMinutes() =
        runTest {
            val (viewModel, outcomes, block) = elapsedBlockViewModel()
            outcomes.nextRecordResult = appliedResult(block)

            viewModel.recordDidntHappen(block)
            runCurrent()

            val call = outcomes.recordCalls.single()
            assertEquals(SessionOutcomeKind.DidntHappen, call.outcome)
            assertNull(call.actualMinutes)
        }

    @Test
    fun recordRescheduleRecordsTheRescheduledKindWithNoActualMinutes() =
        runTest {
            val (viewModel, outcomes, block) = elapsedBlockViewModel()
            outcomes.nextRecordResult = appliedResult(block)

            viewModel.recordReschedule(block)
            runCurrent()

            val call = outcomes.recordCalls.single()
            assertEquals(SessionOutcomeKind.Rescheduled, call.outcome)
            assertNull(call.actualMinutes)
        }

    @Test
    fun dismissWrapUpRecordsNothingAndHidesTheRowForTheSession() =
        runTest {
            val (viewModel, outcomes, block) = elapsedBlockViewModel()

            viewModel.dismissWrapUp(block)
            runCurrent()

            assertTrue(outcomes.recordCalls.isEmpty())
            assertNull(viewModel.state.value.wrapUpBlock)
            assertTrue(block.id.value in viewModel.state.value.dismissedWrapUpBlockIds)
        }

    @Test
    fun ranLongConfirmRecordsPlannedPlusTheSelectedDelta() =
        runTest {
            val (viewModel, outcomes, block) = elapsedBlockViewModel(plannedMinutes = 60)
            outcomes.nextRecordResult = appliedResult(block)

            viewModel.beginRanLongAdjustment(block)
            runCurrent()
            assertEquals(
                15,
                viewModel.state.value.pendingRanLongAdjustment
                    ?.selectedDeltaMinutes,
            )

            viewModel.selectRanLongDelta(block, 30)
            viewModel.confirmRanLongAdjustment(block)
            runCurrent()

            val call = outcomes.recordCalls.single()
            assertEquals(SessionOutcomeKind.RanLong, call.outcome)
            assertEquals(90, call.actualMinutes)
            assertNull(viewModel.state.value.pendingRanLongAdjustment)
        }

    @Test
    fun ranLongAutoSavesAfterThreeSecondsOfInactivity() =
        runTest {
            val (viewModel, outcomes, block) = elapsedBlockViewModel(plannedMinutes = 60)
            outcomes.nextRecordResult = appliedResult(block)

            viewModel.beginRanLongAdjustment(block)
            runCurrent()
            advanceTimeBy(3.1.seconds)
            runCurrent()

            val call = outcomes.recordCalls.single()
            assertEquals(SessionOutcomeKind.RanLong, call.outcome)
            assertEquals(75, call.actualMinutes) // planned(60) + the pre-selected +15m default
        }

    @Test
    fun cancelRanLongAdjustmentRecordsNothing() =
        runTest {
            val (viewModel, outcomes, block) = elapsedBlockViewModel()

            viewModel.beginRanLongAdjustment(block)
            runCurrent()
            viewModel.cancelRanLongAdjustment()
            advanceTimeBy(5.seconds)
            runCurrent()

            assertTrue(outcomes.recordCalls.isEmpty())
            assertNull(viewModel.state.value.pendingRanLongAdjustment)
        }

    @Test
    fun wrapUpConfirmationClearsItselfAfterItsOwnTtl() =
        runTest {
            val (viewModel, outcomes, block) = elapsedBlockViewModel()
            outcomes.nextRecordResult = appliedResult(block)

            viewModel.recordDidntHappen(block)
            runCurrent()
            assertEquals(block.id.value, viewModel.state.value.wrapUpConfirmationBlockId)

            advanceTimeBy(2.6.seconds)
            runCurrent()
            assertNull(viewModel.state.value.wrapUpConfirmationBlockId)
        }

    // --- Stage 5: next-time suggestion (council/stage5-retention-gemini.md §B §2) ---

    @Test
    fun nextTimeSuggestionRendersOnlyWhenNonNullAndDifferentFromStandard() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours
            val upcoming = block("b-upcoming", startsAt = now + 1.hours, endsAt = now + 2.hours)
            val outcomes = FakeOutcomeRepository()
            outcomes.nextSuggestionResult =
                NextTimeSuggestionResult.Loaded(NextTimeSuggestion(suggestedMinutes = 80, sampleSize = 3, basis = null))
            val repository = fakeRepository(blocks = listOf(upcoming))
            val viewModel =
                TodayViewModel(
                    repository,
                    backgroundScope,
                    clock = fixedClock(now),
                    zone = zone,
                    outcomeRepository = outcomes,
                )
            runCurrent()

            viewModel.refreshNextTimeSuggestion(upcoming)
            runCurrent()

            val card = viewModel.state.value.nextTimeCard
            assertEquals("b-upcoming", card?.blockId)
            assertEquals(60, card?.standardMinutes)
            assertEquals(80, card?.suggestedMinutes)
            assertEquals("b-upcoming", outcomes.suggestionCalls.single().titleKey)
        }

    @Test
    fun nextTimeSuggestionStaysHiddenWhenTheServerHasNotEnoughSamples() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours
            val upcoming = block("b-upcoming", startsAt = now + 1.hours, endsAt = now + 2.hours)
            val outcomes = FakeOutcomeRepository()
            outcomes.nextSuggestionResult =
                NextTimeSuggestionResult.Loaded(
                    NextTimeSuggestion(suggestedMinutes = null, sampleSize = 1, basis = null),
                )
            val repository = fakeRepository(blocks = listOf(upcoming))
            val viewModel =
                TodayViewModel(
                    repository,
                    backgroundScope,
                    clock = fixedClock(now),
                    zone = zone,
                    outcomeRepository = outcomes,
                )
            runCurrent()

            viewModel.refreshNextTimeSuggestion(upcoming)
            runCurrent()

            assertNull(viewModel.state.value.nextTimeCard)
        }

    @Test
    fun nextTimeSuggestionStaysHiddenWhenThereIsNoVarianceFromStandard() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours
            val upcoming = block("b-upcoming", startsAt = now + 1.hours, endsAt = now + 2.hours) // 60 planned
            val outcomes = FakeOutcomeRepository()
            outcomes.nextSuggestionResult =
                NextTimeSuggestionResult.Loaded(NextTimeSuggestion(suggestedMinutes = 60, sampleSize = 4, basis = null))
            val repository = fakeRepository(blocks = listOf(upcoming))
            val viewModel =
                TodayViewModel(
                    repository,
                    backgroundScope,
                    clock = fixedClock(now),
                    zone = zone,
                    outcomeRepository = outcomes,
                )
            runCurrent()

            viewModel.refreshNextTimeSuggestion(upcoming)
            runCurrent()

            assertNull(viewModel.state.value.nextTimeCard)
        }

    @Test
    fun dismissNextTimeCardSuppressesItForTheRestOfTheSession() =
        runTest {
            val date = LocalDate(2026, 6, 15)
            val window = dayWindow(date, zone)
            val now = window.start + 1.hours
            val upcoming = block("b-upcoming", startsAt = now + 1.hours, endsAt = now + 2.hours)
            val outcomes = FakeOutcomeRepository()
            outcomes.nextSuggestionResult =
                NextTimeSuggestionResult.Loaded(NextTimeSuggestion(suggestedMinutes = 80, sampleSize = 3, basis = null))
            val repository = fakeRepository(blocks = listOf(upcoming))
            val viewModel =
                TodayViewModel(
                    repository,
                    backgroundScope,
                    clock = fixedClock(now),
                    zone = zone,
                    outcomeRepository = outcomes,
                )
            runCurrent()
            viewModel.refreshNextTimeSuggestion(upcoming)
            runCurrent()
            assertEquals(
                80,
                viewModel.state.value.nextTimeCard
                    ?.suggestedMinutes,
            )

            viewModel.dismissNextTimeCard("b-upcoming")
            runCurrent()
            assertNull(viewModel.state.value.nextTimeCard)

            // A later refresh for the same block must not resurrect it this session (§B 2.3
            // "Suppression": "does not reappear until a subsequent session outcome is logged").
            viewModel.refreshNextTimeSuggestion(upcoming)
            runCurrent()
            assertNull(viewModel.state.value.nextTimeCard)
        }

    /** A block that ended 30 minutes ago (within the wrap-up TTL) plus its [FakeOutcomeRepository]
     * and [TodayViewModel], wired together for the wrap-up-chip tests above. */
    private fun TestScope.elapsedBlockViewModel(
        plannedMinutes: Int = 30,
    ): Triple<TodayViewModel, FakeOutcomeRepository, TimeBlock> {
        val date = LocalDate(2026, 6, 15)
        val window = dayWindow(date, zone)
        val now = window.start + 3.hours
        val elapsed = block("b-elapsed", startsAt = now - (plannedMinutes + 30).minutes, endsAt = now - 30.minutes)
        val outcomes = FakeOutcomeRepository()
        val repository = fakeRepository(blocks = listOf(elapsed))
        val viewModel =
            TodayViewModel(
                repository,
                backgroundScope,
                clock = fixedClock(now),
                zone = zone,
                outcomeRepository = outcomes,
            )
        return Triple(viewModel, outcomes, elapsed)
    }

    private fun appliedResult(block: TimeBlock): RecordOutcomeResult.Applied =
        RecordOutcomeResult.Applied(
            SessionOutcome(
                id = SessionOutcomeId("outcome-1"),
                timeBlockId = block.id,
                outcome = SessionOutcomeKind.FinishedEarly,
                plannedMinutes = 60,
                actualMinutes = 45,
                deltaMinutes = -15,
                version = 1,
            ),
        )

    private fun task(
        id: String,
        dueStart: Instant?,
        priority: Priority = Priority.Normal,
        status: TaskStatus = TaskStatus.Todo,
    ) = Task(
        id = TaskId(id),
        ownerId = ownerId,
        goalId = null,
        milestoneId = null,
        title = id,
        notes = null,
        status = status,
        priority = priority,
        effort = null,
        estimateMinutes = null,
        dueStart = dueStart,
        dueEnd = null,
        recurrenceRule = null,
        tags = emptyList(),
        version = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun capture(
        id: String,
        parseStatus: ParseStatus,
    ) = Capture(
        id = CaptureId(id),
        ownerId = ownerId,
        body = id,
        source = CaptureSource.Quick,
        parseStatus = parseStatus,
        capturedAt = Instant.fromEpochMilliseconds(0),
        clarifiedTaskId = null,
        version = 1,
    )

    private fun block(
        id: String,
        startsAt: Instant,
        endsAt: Instant,
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
        allDay = false,
        version = 1,
    )

    private fun fakeRepository(
        tasks: List<Task> = emptyList(),
        captures: List<Capture> = emptyList(),
        blocks: List<TimeBlock> = emptyList(),
    ) = FakePlannerRepository(
        PlannerSeed(goals = emptyList(), milestones = emptyList(), tasks = tasks, captures = captures, blocks = blocks),
    )

    private fun fixedClock(instant: Instant): kotlin.time.Clock =
        object : kotlin.time.Clock {
            override fun now(): Instant = instant
        }
}
