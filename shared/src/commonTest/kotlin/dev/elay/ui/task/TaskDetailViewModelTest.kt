package dev.elay.ui.task

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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class TaskDetailViewModelTest {
    private val zone = TimeZone.UTC
    private val ownerId = UserId("owner-1")
    private val taskId = TaskId("task-1")

    @Test
    fun loadsTheTaskAndItsLinkedBlocksSortedByStartTime() =
        runTest {
            val due = Instant.fromEpochMilliseconds(0)
            val later = block("b-later", startsAt = due + 2.hours)
            val earlier = block("b-earlier", startsAt = due + 1.hours)
            val repository = fakeRepository(tasks = listOf(task()), blocks = listOf(later, earlier))
            val viewModel = TaskDetailViewModel(taskId, repository, backgroundScope, zone = zone)
            runCurrent()

            assertEquals(
                "Finish the draft",
                viewModel.state.value.task
                    ?.title,
            )
            assertEquals(
                listOf(TimeBlockId("b-earlier"), TimeBlockId("b-later")),
                viewModel.state.value.linkedBlocks
                    .map { it.id },
            )
        }

    @Test
    fun notFoundWhenTheTaskIdDoesNotResolve() =
        runTest {
            val repository = fakeRepository()
            val viewModel = TaskDetailViewModel(TaskId("missing"), repository, backgroundScope, zone = zone)
            runCurrent()

            assertTrue(viewModel.state.value.notFound)
        }

    @Test
    fun completeMarksTheTaskCompletedThroughTheRepository() =
        runTest {
            val repository = fakeRepository(tasks = listOf(task()))
            val viewModel = TaskDetailViewModel(taskId, repository, backgroundScope, zone = zone)
            runCurrent()

            viewModel.complete()
            runCurrent()

            assertEquals(
                TaskStatus.Completed,
                viewModel.state.value.task
                    ?.status,
            )
            assertEquals(TaskStatus.Completed, repository.task(taskId)?.status)
        }

    @Test
    fun notTodayShiftsTheDueWindowForwardByOneDay() =
        runTest {
            val dueStart = Instant.fromEpochMilliseconds(0)
            val repository = fakeRepository(tasks = listOf(task(dueStart = dueStart, dueEnd = dueStart + 1.hours)))
            val viewModel = TaskDetailViewModel(taskId, repository, backgroundScope, zone = zone)
            runCurrent()

            viewModel.notToday()
            runCurrent()

            assertEquals(
                dueStart + 1.days,
                viewModel.state.value.task
                    ?.dueStart,
            )
        }

    @Test
    fun deleteRemovesTheTaskAndMarksDeletedSeparatelyFromNotFound() =
        runTest {
            val repository = fakeRepository(tasks = listOf(task()))
            val viewModel = TaskDetailViewModel(taskId, repository, backgroundScope, zone = zone)
            runCurrent()

            viewModel.delete()
            runCurrent()

            assertNull(repository.task(taskId))
            assertTrue(viewModel.state.value.deleted)
            assertTrue(!viewModel.state.value.notFound, "a deliberate delete is not the same as 'not found'")
        }

    private fun task(
        dueStart: Instant? = null,
        dueEnd: Instant? = null,
    ) = Task(
        id = taskId,
        ownerId = ownerId,
        goalId = null,
        milestoneId = null,
        title = "Finish the draft",
        notes = null,
        status = TaskStatus.Todo,
        priority = Priority.Normal,
        effort = null,
        estimateMinutes = null,
        dueStart = dueStart,
        dueEnd = dueEnd,
        recurrenceRule = null,
        tags = emptyList(),
        version = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun block(
        id: String,
        startsAt: Instant,
    ) = TimeBlock(
        id = TimeBlockId(id),
        ownerId = ownerId,
        taskId = taskId,
        title = id,
        startsAt = startsAt,
        endsAt = startsAt + 30.minutes,
        originTz = zone,
        type = BlockType.Focus,
        status = BlockStatus.Scheduled,
        recurrenceRule = null,
        allDay = false,
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
}
