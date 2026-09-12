package dev.elay.di

import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.repository.PlannerRepository
import dev.elay.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phase 1 replay trigger (brief §2, option A over a periodic timer): [LocalFirstPlannerRepository]
 * enqueues every write but the coordinator never self-triggers a drain, so this thin decorator
 * fires a fire-and-forget [SyncCoordinator.replayOnce] right after each write reaches the
 * delegate. Chosen over a periodic 30s poll because:
 * - it syncs the instant a write lands, which matches the product's "propose → accept in under
 *   15 seconds" latency goal far better than waiting up to half a minute;
 * - it costs nothing while idle — no timer coroutine ticking in every signed-in session whether
 *   or not anything changed;
 * - it is trivially deterministic to test (one write → exactly one recorded `replayOnce()` call
 *   via a fake [SyncCoordinator], no virtual-time `advanceTimeBy` choreography).
 * The accepted cost: a burst of writes fires a burst of overlapping `replayOnce()` calls rather
 * than one coalesced drain — acceptable in Phase 1 since [SyncCoordinator] already serializes
 * replay internally (contracts/phase1-planner.md §3: "one mutation in flight globally").
 *
 * Reads pass straight through via interface delegation (`by delegate`) — only the ten write
 * methods are overridden.
 */
@Suppress("TooManyFunctions")
class ReplayTriggeringPlannerRepository(
    private val delegate: PlannerRepository,
    private val syncCoordinator: SyncCoordinator,
    private val scope: CoroutineScope,
) : PlannerRepository by delegate {
    override suspend fun upsertGoal(goal: Goal) {
        delegate.upsertGoal(goal)
        triggerReplay()
    }

    override suspend fun deleteGoal(id: GoalId) {
        delegate.deleteGoal(id)
        triggerReplay()
    }

    override suspend fun upsertMilestone(milestone: Milestone) {
        delegate.upsertMilestone(milestone)
        triggerReplay()
    }

    override suspend fun upsertTask(task: Task) {
        delegate.upsertTask(task)
        triggerReplay()
    }

    override suspend fun deleteTask(id: TaskId) {
        delegate.deleteTask(id)
        triggerReplay()
    }

    override suspend fun upsertCapture(capture: Capture) {
        delegate.upsertCapture(capture)
        triggerReplay()
    }

    override suspend fun clarifyCapture(
        id: CaptureId,
        taskId: TaskId,
    ) {
        delegate.clarifyCapture(id, taskId)
        triggerReplay()
    }

    override suspend fun dismissCapture(id: CaptureId) {
        delegate.dismissCapture(id)
        triggerReplay()
    }

    override suspend fun upsertBlock(block: TimeBlock) {
        delegate.upsertBlock(block)
        triggerReplay()
    }

    override suspend fun deleteBlock(id: TimeBlockId) {
        delegate.deleteBlock(id)
        triggerReplay()
    }

    private fun triggerReplay() {
        scope.launch { syncCoordinator.replayOnce() }
    }
}
