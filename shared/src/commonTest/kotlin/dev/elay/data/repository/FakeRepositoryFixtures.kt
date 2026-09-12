package dev.elay.data.repository

import dev.elay.data.local.CaptureDao
import dev.elay.data.local.CaptureEntity
import dev.elay.data.local.GoalDao
import dev.elay.data.local.GoalEntity
import dev.elay.data.local.MilestoneDao
import dev.elay.data.local.MilestoneEntity
import dev.elay.data.local.PlannerStagingDao
import dev.elay.data.local.TaskDao
import dev.elay.data.local.TaskEntity
import dev.elay.data.local.TimeBlockDao
import dev.elay.data.local.TimeBlockEntity
import dev.elay.sync.MutationCommand
import dev.elay.sync.SyncCoordinator
import dev.elay.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * One shared in-memory "database": every fake Dao below (both the read-side
 * [GoalDao]-family fakes and [FakePlannerStagingDao]) reads/writes the same
 * table maps, mirroring how the real Room Daos all operate on the same
 * `@Entity`-backed SQL tables — so a write staged through
 * [FakePlannerStagingDao] is immediately visible to e.g. [FakeGoalDao]'s
 * `observeAll()`, exactly like production.
 */
class FakeTables {
    val goals = MutableStateFlow<Map<String, GoalEntity>>(emptyMap())
    val milestones = MutableStateFlow<Map<String, MilestoneEntity>>(emptyMap())
    val tasks = MutableStateFlow<Map<String, TaskEntity>>(emptyMap())
    val captures = MutableStateFlow<Map<String, CaptureEntity>>(emptyMap())
    val timeBlocks = MutableStateFlow<Map<String, TimeBlockEntity>>(emptyMap())
}

class FakeGoalDao(
    private val tables: FakeTables,
) : GoalDao {
    override fun observeAll(): Flow<List<GoalEntity>> = tables.goals.map { it.values.sortedBy { g -> g.title } }

    override suspend fun get(id: String): GoalEntity? = tables.goals.value[id]

    override suspend fun upsert(entity: GoalEntity) {
        tables.goals.value = tables.goals.value + (entity.id to entity)
    }

    override suspend fun delete(id: String) {
        tables.goals.value = tables.goals.value - id
    }
}

class FakeMilestoneDao(
    private val tables: FakeTables,
) : MilestoneDao {
    override fun observeForGoal(goalId: String): Flow<List<MilestoneEntity>> =
        tables.milestones.map { rows -> rows.values.filter { it.goalId == goalId }.sortedBy { it.sortOrder } }

    override suspend fun get(id: String): MilestoneEntity? = tables.milestones.value[id]

    override suspend fun upsert(entity: MilestoneEntity) {
        tables.milestones.value = tables.milestones.value + (entity.id to entity)
    }

    override suspend fun delete(id: String) {
        tables.milestones.value = tables.milestones.value - id
    }
}

class FakeTaskDao(
    private val tables: FakeTables,
) : TaskDao {
    override fun observeToday(
        startMs: Long,
        endMs: Long,
    ): Flow<List<TaskEntity>> =
        tables.tasks.map { rows ->
            rows.values
                .filter { it.dueStartEpochMs != null && it.dueStartEpochMs in startMs..endMs }
                .sortedBy { it.dueStartEpochMs }
        }

    override fun observeUnscheduled(): Flow<List<TaskEntity>> =
        tables.tasks.map { rows -> rows.values.filter { it.dueStartEpochMs == null }.sortedBy { it.title } }

    override fun observeForGoal(goalId: String): Flow<List<TaskEntity>> =
        tables.tasks.map { rows -> rows.values.filter { it.goalId == goalId }.sortedBy { it.title } }

    override suspend fun get(id: String): TaskEntity? = tables.tasks.value[id]

    override suspend fun upsert(entity: TaskEntity) {
        tables.tasks.value = tables.tasks.value + (entity.id to entity)
    }

    override suspend fun delete(id: String) {
        tables.tasks.value = tables.tasks.value - id
    }
}

class FakeCaptureDao(
    private val tables: FakeTables,
) : CaptureDao {
    override fun observeInbox(): Flow<List<CaptureEntity>> =
        tables.captures.map { rows ->
            rows.values
                .filter { it.clarifiedTaskId == null && it.parseStatus != "dismissed" }
                .sortedByDescending { it.capturedAtEpochMs }
        }

    override suspend fun get(id: String): CaptureEntity? = tables.captures.value[id]

    override suspend fun upsert(entity: CaptureEntity) {
        tables.captures.value = tables.captures.value + (entity.id to entity)
    }

    override suspend fun clarify(
        id: String,
        taskId: String,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    ) {
        val current = tables.captures.value[id] ?: return
        tables.captures.value =
            tables.captures.value +
            (
                id to
                    current.copy(
                        clarifiedTaskId = taskId,
                        parseStatus = "parsed",
                        syncStatus = syncStatus,
                        localUpdatedAtEpochMs = localUpdatedAtEpochMs,
                    )
            )
    }

    override suspend fun delete(id: String) {
        tables.captures.value = tables.captures.value - id
    }
}

class FakeTimeBlockDao(
    private val tables: FakeTables,
) : TimeBlockDao {
    override fun observeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<TimeBlockEntity>> =
        tables.timeBlocks.map { rows ->
            rows.values
                .filter { it.startsAtEpochMs <= endMs && it.endsAtEpochMs >= startMs }
                .sortedBy { it.startsAtEpochMs }
        }

    override fun observeForTask(taskId: String): Flow<List<TimeBlockEntity>> =
        tables.timeBlocks.map { rows -> rows.values.filter { it.taskId == taskId }.sortedBy { it.startsAtEpochMs } }

    override suspend fun upsert(entity: TimeBlockEntity) {
        tables.timeBlocks.value = tables.timeBlocks.value + (entity.id to entity)
    }

    override suspend fun delete(id: String) {
        tables.timeBlocks.value = tables.timeBlocks.value - id
    }
}

/** In-memory [PlannerStagingDao] sharing [tables] with the read-side fakes above. */
class FakePlannerStagingDao(
    private val tables: FakeTables,
) : PlannerStagingDao() {
    override suspend fun upsertGoalRow(entity: GoalEntity) {
        tables.goals.value = tables.goals.value + (entity.id to entity)
    }

    override suspend fun goalVersion(id: String): Long? = tables.goals.value[id]?.version

    override suspend fun deleteGoalRow(id: String) {
        tables.goals.value = tables.goals.value - id
    }

    override suspend fun upsertMilestoneRow(entity: MilestoneEntity) {
        tables.milestones.value = tables.milestones.value + (entity.id to entity)
    }

    override suspend fun milestoneVersion(id: String): Long? = tables.milestones.value[id]?.version

    override suspend fun upsertTaskRow(entity: TaskEntity) {
        tables.tasks.value = tables.tasks.value + (entity.id to entity)
    }

    override suspend fun taskVersion(id: String): Long? = tables.tasks.value[id]?.version

    override suspend fun deleteTaskRow(id: String) {
        tables.tasks.value = tables.tasks.value - id
    }

    override suspend fun upsertCaptureRow(entity: CaptureEntity) {
        tables.captures.value = tables.captures.value + (entity.id to entity)
    }

    override suspend fun captureVersion(id: String): Long? = tables.captures.value[id]?.version

    override suspend fun captureRow(id: String): CaptureEntity? = tables.captures.value[id]

    override suspend fun clarifyCaptureRow(
        id: String,
        taskId: String,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    ) {
        val current = tables.captures.value[id] ?: return
        tables.captures.value =
            tables.captures.value +
            (
                id to
                    current.copy(
                        parseStatus = "parsed",
                        clarifiedTaskId = taskId,
                        syncStatus = syncStatus,
                        localUpdatedAtEpochMs = localUpdatedAtEpochMs,
                    )
            )
    }

    override suspend fun dismissCaptureRow(
        id: String,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    ) {
        val current = tables.captures.value[id] ?: return
        tables.captures.value =
            tables.captures.value +
            (
                id to
                    current.copy(
                        parseStatus = "dismissed",
                        syncStatus = syncStatus,
                        localUpdatedAtEpochMs = localUpdatedAtEpochMs,
                    )
            )
    }

    override suspend fun upsertTimeBlockRow(entity: TimeBlockEntity) {
        tables.timeBlocks.value = tables.timeBlocks.value + (entity.id to entity)
    }

    override suspend fun timeBlockVersion(id: String): Long? = tables.timeBlocks.value[id]?.version

    override suspend fun deleteTimeBlockRow(id: String) {
        tables.timeBlocks.value = tables.timeBlocks.value - id
    }
}

/** Records every enqueued command — no replay, no network; this seat only asserts what it enqueues. */
class FakeSyncCoordinator : SyncCoordinator {
    val enqueued = mutableListOf<MutationCommand>()

    override val status: Flow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)

    override suspend fun enqueue(command: MutationCommand) {
        enqueued += command
    }

    override suspend fun replayOnce() {
        error("not exercised by LocalFirstPlannerRepositoryTest — see OutboxSyncCoordinatorTest for replay behavior")
    }
}
