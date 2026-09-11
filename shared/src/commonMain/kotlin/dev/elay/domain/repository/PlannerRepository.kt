package dev.elay.domain.repository

import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Frozen Phase 1 repository surface (council DAO list, contracts/phase1-planner.md §5).
 * Implementations are local-first: reads observe Room; writes stage into the
 * outbox and return optimistically (rendered as pending until acknowledged).
 */
interface PlannerRepository {
    // Goals
    fun observeGoals(): Flow<List<Goal>>

    suspend fun goal(id: GoalId): Goal?

    suspend fun upsertGoal(goal: Goal)

    suspend fun deleteGoal(id: GoalId)

    // Milestones
    fun observeMilestones(goalId: GoalId): Flow<List<Milestone>>

    suspend fun upsertMilestone(milestone: Milestone)

    // Tasks
    fun observeTodayTasks(
        dayStart: Instant,
        dayEnd: Instant,
    ): Flow<List<Task>>

    fun observeUnscheduledTasks(): Flow<List<Task>>

    fun observeTasksForGoal(goalId: GoalId): Flow<List<Task>>

    suspend fun task(id: TaskId): Task?

    suspend fun upsertTask(task: Task)

    suspend fun deleteTask(id: TaskId)

    // Captures (Inbox)
    fun observeInbox(): Flow<List<Capture>>

    suspend fun upsertCapture(capture: Capture)

    suspend fun clarifyCapture(
        id: CaptureId,
        taskId: TaskId,
    )

    suspend fun dismissCapture(id: CaptureId)

    // Time blocks (Plan)
    fun observeBlocks(
        from: Instant,
        to: Instant,
    ): Flow<List<TimeBlock>>

    fun observeBlocksForTask(taskId: TaskId): Flow<List<TimeBlock>>

    suspend fun upsertBlock(block: TimeBlock)

    suspend fun deleteBlock(id: TimeBlockId)
}

/** Profile + preferences (spec §7 profiles). */
interface ProfileRepository {
    data class Profile(
        val displayName: String,
        val homeTz: String,
        val locale: String,
        val weekStart: Int,
    )

    fun observeProfile(): Flow<Profile?>

    suspend fun updateProfile(profile: Profile)

    suspend fun setHomeTimeZone(tzId: String)
}

/** Deliberate second name for LocalDate use at call sites without wildcarding imports. */
typealias PlannerDate = LocalDate
