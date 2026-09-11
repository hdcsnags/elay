package dev.elay.ui.fake

import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.ParseStatus
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.repository.PlannerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * In-memory [PlannerRepository] — the only data source until the Room/Supabase
 * data layer lands (brief §1). Backed by [MutableStateFlow]s so every
 * `observe*` stream reacts immediately to writes, the way the real
 * local-first repository will.
 */
@Suppress("TooManyFunctions") // implements the deliberate PlannerRepository facade
class FakePlannerRepository internal constructor(
    seed: PlannerSeed,
) : PlannerRepository {
    private val goals = MutableStateFlow(seed.goals)
    private val milestones = MutableStateFlow(seed.milestones)
    private val tasks = MutableStateFlow(seed.tasks)
    private val captures = MutableStateFlow(seed.captures)
    private val blocks = MutableStateFlow(seed.blocks)

    // Goals

    override fun observeGoals(): Flow<List<Goal>> = goals.asStateFlow()

    override suspend fun goal(id: GoalId): Goal? = goals.value.find { it.id == id }

    override suspend fun upsertGoal(goal: Goal) {
        goals.update { list -> list.upserted(goal) { it.id } }
    }

    override suspend fun deleteGoal(id: GoalId) {
        goals.update { list -> list.filterNot { it.id == id } }
    }

    // Milestones

    override fun observeMilestones(goalId: GoalId): Flow<List<Milestone>> =
        milestones.map { list -> list.filter { it.goalId == goalId }.sortedBy { it.sortOrder } }

    override suspend fun upsertMilestone(milestone: Milestone) {
        milestones.update { list -> list.upserted(milestone) { it.id } }
    }

    // Tasks

    override fun observeTodayTasks(
        dayStart: Instant,
        dayEnd: Instant,
    ): Flow<List<Task>> =
        tasks.map { list ->
            list
                .filter { task -> task.dueStart != null && task.dueStart >= dayStart && task.dueStart <= dayEnd }
                .sortedBy { it.dueStart }
        }

    override fun observeUnscheduledTasks(): Flow<List<Task>> =
        tasks.map { list -> list.filter { it.dueStart == null }.sortedBy { it.title } }

    override fun observeTasksForGoal(goalId: GoalId): Flow<List<Task>> =
        tasks.map { list -> list.filter { it.goalId == goalId } }

    override suspend fun task(id: TaskId): Task? = tasks.value.find { it.id == id }

    override suspend fun upsertTask(task: Task) {
        tasks.update { list -> list.upserted(task) { it.id } }
    }

    override suspend fun deleteTask(id: TaskId) {
        tasks.update { list -> list.filterNot { it.id == id } }
    }

    // Captures (Inbox)

    override fun observeInbox(): Flow<List<Capture>> =
        captures.map { list ->
            list.filter { it.parseStatus == ParseStatus.Unparsed }.sortedByDescending { it.capturedAt }
        }

    override suspend fun upsertCapture(capture: Capture) {
        captures.update { list -> list.upserted(capture) { it.id } }
    }

    override suspend fun clarifyCapture(
        id: CaptureId,
        taskId: TaskId,
    ) {
        captures.update { list ->
            list.map { capture ->
                if (capture.id == id) {
                    capture.copy(
                        parseStatus = ParseStatus.Parsed,
                        clarifiedTaskId = taskId,
                        version = capture.version + 1,
                    )
                } else {
                    capture
                }
            }
        }
    }

    override suspend fun dismissCapture(id: CaptureId) {
        captures.update { list ->
            list.map { capture ->
                if (capture.id == id) {
                    capture.copy(parseStatus = ParseStatus.Dismissed, version = capture.version + 1)
                } else {
                    capture
                }
            }
        }
    }

    // Time blocks (Plan)

    override fun observeBlocks(
        from: Instant,
        to: Instant,
    ): Flow<List<TimeBlock>> =
        blocks.map { list ->
            list.filter { block -> block.startsAt < to && block.endsAt > from }.sortedBy { it.startsAt }
        }

    override fun observeBlocksForTask(taskId: TaskId): Flow<List<TimeBlock>> =
        blocks.map { list -> list.filter { it.taskId == taskId } }

    override suspend fun upsertBlock(block: TimeBlock) {
        blocks.update { list -> list.upserted(block) { it.id } }
    }

    override suspend fun deleteBlock(id: TimeBlockId) {
        blocks.update { list -> list.filterNot { it.id == id } }
    }

    companion object {
        /** A freshly seeded repository — construction happens at App wiring time (concierge). */
        fun seeded(
            clock: Clock = Clock.System,
            zone: TimeZone = TimeZone.currentSystemDefault(),
        ): FakePlannerRepository = FakePlannerRepository(PlannerSeed.build(clock.now(), zone))
    }
}

private fun <T, K> List<T>.upserted(
    item: T,
    key: (T) -> K,
): List<T> {
    val itemKey = key(item)
    val index = indexOfFirst { key(it) == itemKey }
    return if (index >= 0) toMutableList().apply { set(index, item) } else this + item
}

/** Factory for App-level wiring (contracts/phase1-planner.md §6 — construction lands with DI later). */
fun createFakePlannerRepository(
    clock: Clock = Clock.System,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): PlannerRepository = FakePlannerRepository.seeded(clock, zone)

/** Single shared instance so Today/Inbox/Plan observe the same in-memory data until real DI lands. */
val sharedFakePlannerRepository: PlannerRepository by lazy { createFakePlannerRepository() }
