package dev.elay.ui.goal

import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.GoalStatus
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.MilestoneId
import dev.elay.domain.model.MilestoneStatus
import dev.elay.domain.model.Priority
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.UserId
import dev.elay.ui.fake.FakePlannerRepository
import dev.elay.ui.fake.PlannerSeed
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalDetailViewModelTest {
    private val ownerId = UserId("owner-1")
    private val goalId = GoalId("goal-1")

    @Test
    fun loadsTheGoalAndSortsMilestonesBySortOrder() =
        runTest {
            val goal = goal()
            val milestoneB = milestone("m-b", sortOrder = 1)
            val milestoneA = milestone("m-a", sortOrder = 0)
            val repository = fakeRepository(goals = listOf(goal), milestones = listOf(milestoneB, milestoneA))
            val viewModel = GoalDetailViewModel(goalId, repository, backgroundScope, clock = fixedClock())
            runCurrent()

            assertEquals(goal, viewModel.state.value.goal)
            assertEquals(
                listOf(MilestoneId("m-a"), MilestoneId("m-b")),
                viewModel.state.value.milestones
                    .map { it.id },
            )
        }

    @Test
    fun observesOnlyTasksLinkedToThisGoal() =
        runTest {
            val linked = task("t-linked", goalId = goalId)
            val other = task("t-other", goalId = GoalId("goal-2"))
            val repository =
                fakeRepository(goals = listOf(goal()), tasks = listOf(linked, other))
            val viewModel = GoalDetailViewModel(goalId, repository, backgroundScope, clock = fixedClock())
            runCurrent()

            assertEquals(
                listOf(TaskId("t-linked")),
                viewModel.state.value.tasks
                    .map { it.id },
            )
        }

    @Test
    fun notFoundWhenTheGoalIdDoesNotResolve() =
        runTest {
            val repository = fakeRepository()
            val viewModel = GoalDetailViewModel(GoalId("missing"), repository, backgroundScope, clock = fixedClock())
            runCurrent()

            assertTrue(viewModel.state.value.notFound)
        }

    @Test
    fun setStatusUpdatesLocalStateAndPersistsThroughTheRepository() =
        runTest {
            val repository = fakeRepository(goals = listOf(goal()))
            val viewModel = GoalDetailViewModel(goalId, repository, backgroundScope, clock = fixedClock())
            runCurrent()

            viewModel.setStatus(GoalStatus.Paused)
            runCurrent()

            assertEquals(
                GoalStatus.Paused,
                viewModel.state.value.goal
                    ?.status,
            )
            assertEquals(GoalStatus.Paused, repository.goal(goalId)?.status)
        }

    @Test
    fun setStatusIsANoOpWhenAlreadyAtThatStatus() =
        runTest {
            val repository = fakeRepository(goals = listOf(goal()))
            val viewModel = GoalDetailViewModel(goalId, repository, backgroundScope, clock = fixedClock())
            runCurrent()

            viewModel.setStatus(GoalStatus.Active)
            runCurrent()

            assertEquals(
                1L,
                viewModel.state.value.goal
                    ?.version,
            )
        }

    private fun goal() =
        Goal(
            id = goalId,
            ownerId = ownerId,
            title = "Finish the course",
            notes = null,
            targetDate = null,
            status = GoalStatus.Active,
            version = 1,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

    private fun milestone(
        id: String,
        sortOrder: Int,
    ) = Milestone(
        id = MilestoneId(id),
        goalId = goalId,
        title = id,
        targetDate = null,
        sortOrder = sortOrder,
        status = MilestoneStatus.Pending,
        version = 1,
    )

    private fun task(
        id: String,
        goalId: GoalId?,
    ) = Task(
        id = TaskId(id),
        ownerId = ownerId,
        goalId = goalId,
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

    private fun fakeRepository(
        goals: List<Goal> = emptyList(),
        milestones: List<Milestone> = emptyList(),
        tasks: List<Task> = emptyList(),
    ) = FakePlannerRepository(
        PlannerSeed(
            goals = goals,
            milestones = milestones,
            tasks = tasks,
            captures = emptyList(),
            blocks = emptyList(),
        ),
    )

    private fun fixedClock(): kotlin.time.Clock =
        object : kotlin.time.Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(0)
        }
}
