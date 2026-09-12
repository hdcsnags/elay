package dev.elay.di

import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.TaskId
import dev.elay.ui.fake.FakePlannerRepository
import dev.elay.ui.fake.PlannerSeed
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplayTriggeringPlannerRepositoryTest {
    private fun emptyDelegate() =
        FakePlannerRepository(
            PlannerSeed(
                goals = emptyList(),
                milestones = emptyList(),
                tasks = emptyList(),
                captures = emptyList(),
                blocks = emptyList(),
            ),
        )

    @Test
    fun eachWriteFiresExactlyOneFireAndForgetReplay() =
        runTest {
            val coordinator = FakeSyncCoordinator()
            val repository = ReplayTriggeringPlannerRepository(emptyDelegate(), coordinator, backgroundScope)

            repository.deleteTask(TaskId("t-1"))
            runCurrent()
            assertEquals(1, coordinator.replayOnceCalls)

            repository.dismissCapture(CaptureId("c-1"))
            runCurrent()
            assertEquals(2, coordinator.replayOnceCalls)
        }

    @Test
    fun readsPassThroughToTheDelegateWithoutTriggeringReplay() =
        runTest {
            val coordinator = FakeSyncCoordinator()
            val delegate = emptyDelegate()
            val repository = ReplayTriggeringPlannerRepository(delegate, coordinator, backgroundScope)

            repository.observeInbox()
            runCurrent()

            assertEquals(0, coordinator.replayOnceCalls)
        }
}
