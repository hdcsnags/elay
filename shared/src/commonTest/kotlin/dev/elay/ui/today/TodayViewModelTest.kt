package dev.elay.ui.today

import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.CaptureSource
import dev.elay.domain.model.ParseStatus
import dev.elay.domain.model.Priority
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import dev.elay.ui.fake.FakePlannerRepository
import dev.elay.ui.fake.PlannerSeed
import dev.elay.ui.util.dayWindow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

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
