package dev.elay.sync.impl

import dev.elay.data.local.CaptureDao
import dev.elay.data.local.CaptureEntity
import dev.elay.data.local.GoalDao
import dev.elay.data.local.GoalEntity
import dev.elay.data.local.MilestoneDao
import dev.elay.data.local.MilestoneEntity
import dev.elay.data.local.TaskDao
import dev.elay.data.local.TaskEntity
import dev.elay.data.local.TimeBlockDao
import dev.elay.data.local.TimeBlockEntity
import dev.elay.data.remote.DataGateway
import dev.elay.data.remote.MutationResult
import dev.elay.data.remote.dto.CaptureDto
import dev.elay.data.remote.dto.GoalDto
import dev.elay.data.remote.dto.MilestoneDto
import dev.elay.data.remote.dto.TaskDto
import dev.elay.data.remote.dto.TimeBlockDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ServerHydrator] against fake gateway + fake DAOs (brief §6) — no network, no live Room (see
 * [dev.elay.data.local.TaskDaoTest] for why a bare JVM host can't reliably open one). Covers the
 * one rule that matters: hydrates SYNCED rows, never clobbers a local PENDING/CONFLICT row, and
 * swallows a failure on one aggregate without stopping the rest or throwing.
 */
class ServerHydratorTest {
    private class Harness(
        rowsByAggregate: Map<String, List<JsonObject>> = emptyMap(),
        failingAggregates: Set<String> = emptySet(),
    ) {
        val goalDao = FakeGoalDao()
        val milestoneDao = FakeMilestoneDao()
        val taskDao = FakeTaskDao()
        val captureDao = FakeCaptureDao()
        val timeBlockDao = FakeTimeBlockDao()
        val gateway = FetchOnlyDataGateway(rowsByAggregate, failingAggregates)
        val hydrator =
            ServerHydrator(
                dataGateway = gateway,
                goalDao = goalDao,
                milestoneDao = milestoneDao,
                taskDao = taskDao,
                captureDao = captureDao,
                timeBlockDao = timeBlockDao,
                now = { 999L },
            )

        // Short accessors keep assertions to <= 3 chained calls (ktlint's chain-method-continuation).
        fun goal(id: String) = goalDao.rows.getValue(id)

        fun milestone(id: String) = milestoneDao.rows.getValue(id)

        fun task(id: String) = taskDao.rows.getValue(id)

        fun capture(id: String) = captureDao.rows.getValue(id)

        fun timeBlock(id: String) = timeBlockDao.rows.getValue(id)
    }

    @Test
    fun hydratesFreshServerRowsAsSyncedAcrossEveryAggregate() =
        runTest {
            val h =
                Harness(
                    rowsByAggregate =
                        mapOf(
                            "goal" to listOf(goalRow("g-1")),
                            "milestone" to listOf(milestoneRow("m-1", goalId = "g-1")),
                            "task" to listOf(taskRow("t-1")),
                            "capture" to listOf(captureRow("c-1")),
                            "time_block" to listOf(timeBlockRow("tb-1")),
                        ),
                )

            h.hydrator.hydrateAll()

            assertEquals("SYNCED", h.goal("g-1").syncStatus)
            assertEquals("SYNCED", h.milestone("m-1").syncStatus)
            assertEquals("SYNCED", h.task("t-1").syncStatus)
            assertEquals("SYNCED", h.capture("c-1").syncStatus)
            assertEquals("SYNCED", h.timeBlock("tb-1").syncStatus)
            assertEquals(999L, h.goal("g-1").localUpdatedAtEpochMs)
        }

    @Test
    fun neverClobbersALocalPendingTaskRow() =
        runTest {
            val h = Harness(rowsByAggregate = mapOf("task" to listOf(taskRow("t-1", title = "Server title"))))
            h.taskDao.rows["t-1"] = localTaskEntity("t-1", title = "Local unsynced edit", syncStatus = "PENDING")

            h.hydrator.hydrateAll()

            assertEquals("Local unsynced edit", h.task("t-1").title)
            assertEquals("PENDING", h.task("t-1").syncStatus)
        }

    @Test
    fun neverClobbersALocalConflictGoalRow() =
        runTest {
            val h = Harness(rowsByAggregate = mapOf("goal" to listOf(goalRow("g-1", title = "Server title"))))
            h.goalDao.rows["g-1"] = localGoalEntity("g-1", title = "Local conflicted edit", syncStatus = "CONFLICT")

            h.hydrator.hydrateAll()

            assertEquals("Local conflicted edit", h.goal("g-1").title)
            assertEquals("CONFLICT", h.goal("g-1").syncStatus)
        }

    /** [TimeBlockDao] has no single-row `get` — this proves the full-range-index workaround still
     * respects the never-clobber rule. */
    @Test
    fun neverClobbersALocalPendingTimeBlockRowDespiteNoSingleRowGet() =
        runTest {
            val h =
                Harness(rowsByAggregate = mapOf("time_block" to listOf(timeBlockRow("tb-1", title = "Server"))))
            h.timeBlockDao.rows["tb-1"] =
                localTimeBlockEntity("tb-1", title = "Local unsynced block", syncStatus = "PENDING")

            h.hydrator.hydrateAll()

            assertEquals("Local unsynced block", h.timeBlock("tb-1").title)
        }

    @Test
    fun aFailingAggregateIsSwallowedAndDoesNotStopTheOthers() =
        runTest {
            val h =
                Harness(
                    rowsByAggregate = mapOf("task" to listOf(taskRow("t-1"))),
                    failingAggregates = setOf("goal"),
                )

            // Must not throw, and must not leave the other four un-hydrated.
            h.hydrator.hydrateAll()

            assertNull(h.goalDao.rows["g-never-created"])
            assertEquals(0, h.goalDao.rows.size)
            assertEquals("SYNCED", h.task("t-1").syncStatus)
        }

    @Test
    fun hydratingWithNoServerRowsIsANoOp() =
        runTest {
            val h = Harness()

            h.hydrator.hydrateAll()

            assertEquals(0, h.goalDao.rows.size)
            assertEquals(0, h.taskDao.rows.size)
            assertEquals(0, h.timeBlockDao.rows.size)
        }
}

// ---- row fixtures (wire JSON, exactly what SupabaseDataGateway.fetchSince would decode) ----

private fun goalRow(
    id: String,
    title: String = "Goal $id",
): JsonObject =
    Json
        .encodeToJsonElement(
            GoalDto(
                id = id,
                ownerId = "owner-1",
                visibility = "private",
                title = title,
                status = "active",
                version = 1,
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
            ),
        ).jsonObject

private fun milestoneRow(
    id: String,
    goalId: String,
): JsonObject =
    Json
        .encodeToJsonElement(
            MilestoneDto(
                id = id,
                goalId = goalId,
                title = "Milestone $id",
                sortOrder = 0,
                status = "pending",
                version = 1,
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
            ),
        ).jsonObject

private fun taskRow(
    id: String,
    title: String = "Task $id",
): JsonObject =
    Json
        .encodeToJsonElement(
            TaskDto(
                id = id,
                ownerId = "owner-1",
                visibility = "private",
                title = title,
                status = "todo",
                priority = 1,
                version = 1,
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
            ),
        ).jsonObject

private fun captureRow(id: String): JsonObject =
    Json
        .encodeToJsonElement(
            CaptureDto(
                id = id,
                ownerId = "owner-1",
                body = "Capture $id",
                source = "quick",
                aiParseStatus = "unparsed",
                capturedAt = "2026-06-01T00:00:00Z",
                version = 1,
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
            ),
        ).jsonObject

private fun timeBlockRow(
    id: String,
    title: String = "Block $id",
): JsonObject =
    Json
        .encodeToJsonElement(
            TimeBlockDto(
                id = id,
                ownerId = "owner-1",
                visibility = "private",
                title = title,
                startsAtUtc = "2026-06-15T09:00:00Z",
                endsAtUtc = "2026-06-15T09:30:00Z",
                originTz = "UTC",
                type = "personal",
                status = "scheduled",
                allDay = false,
                version = 1,
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
            ),
        ).jsonObject

// ---- local-only entity fixtures (pre-seeded PENDING/CONFLICT rows) ----

private fun localTaskEntity(
    id: String,
    title: String,
    syncStatus: String,
) = TaskEntity(
    id = id,
    ownerId = "owner-1",
    goalId = null,
    milestoneId = null,
    title = title,
    notes = null,
    status = "todo",
    priority = 1,
    effort = null,
    estimateMinutes = null,
    dueStartEpochMs = null,
    dueEndEpochMs = null,
    recurrenceRule = null,
    tagsJson = "[]",
    version = 1,
    createdAtEpochMs = 0,
    updatedAtEpochMs = 0,
    syncStatus = syncStatus,
    localUpdatedAtEpochMs = 0,
)

private fun localGoalEntity(
    id: String,
    title: String,
    syncStatus: String,
) = GoalEntity(
    id = id,
    ownerId = "owner-1",
    title = title,
    notes = null,
    targetDate = null,
    status = "active",
    version = 1,
    createdAtEpochMs = 0,
    updatedAtEpochMs = 0,
    syncStatus = syncStatus,
    localUpdatedAtEpochMs = 0,
)

private fun localTimeBlockEntity(
    id: String,
    title: String,
    syncStatus: String,
) = TimeBlockEntity(
    id = id,
    ownerId = "owner-1",
    taskId = null,
    title = title,
    startsAtEpochMs = 0,
    endsAtEpochMs = 1_000,
    originTz = "UTC",
    type = "personal",
    status = "scheduled",
    recurrenceRule = null,
    allDay = false,
    version = 1,
    syncStatus = syncStatus,
    localUpdatedAtEpochMs = 0,
)

// ---- fakes ----

private class FetchOnlyDataGateway(
    private val rowsByAggregate: Map<String, List<JsonObject>>,
    private val failingAggregates: Set<String>,
) : DataGateway {
    override suspend fun fetchSince(
        aggregate: String,
        sinceVersion: Long,
    ): Result<List<JsonObject>> =
        if (aggregate in failingAggregates) {
            Result.failure(RuntimeException("boom:$aggregate"))
        } else {
            Result.success(rowsByAggregate[aggregate].orEmpty())
        }

    override suspend fun upsertGoal(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = notUsed()

    override suspend fun upsertMilestone(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = notUsed()

    override suspend fun upsertTask(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = notUsed()

    override suspend fun upsertCapture(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = notUsed()

    override suspend fun upsertTimeBlock(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = notUsed()

    override suspend fun deleteGoal(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = notUsed()

    override suspend fun deleteTask(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = notUsed()

    override suspend fun deleteCapture(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = notUsed()

    override suspend fun deleteTimeBlock(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = notUsed()

    private fun notUsed(): Nothing = error("not used by ServerHydratorTest")
}

private class FakeGoalDao : GoalDao {
    val rows = mutableMapOf<String, GoalEntity>()

    override fun observeAll(): Flow<List<GoalEntity>> = flowOf(rows.values.toList())

    override suspend fun get(id: String): GoalEntity? = rows[id]

    override suspend fun upsert(entity: GoalEntity) {
        rows[entity.id] = entity
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }
}

private class FakeMilestoneDao : MilestoneDao {
    val rows = mutableMapOf<String, MilestoneEntity>()

    override fun observeForGoal(goalId: String): Flow<List<MilestoneEntity>> =
        flowOf(rows.values.filter { it.goalId == goalId })

    override suspend fun get(id: String): MilestoneEntity? = rows[id]

    override suspend fun upsert(entity: MilestoneEntity) {
        rows[entity.id] = entity
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }
}

private class FakeTaskDao : TaskDao {
    val rows = mutableMapOf<String, TaskEntity>()

    override fun observeToday(
        startMs: Long,
        endMs: Long,
    ): Flow<List<TaskEntity>> = flowOf(rows.values.toList())

    override fun observeUnscheduled(): Flow<List<TaskEntity>> = flowOf(rows.values.toList())

    override fun observeForGoal(goalId: String): Flow<List<TaskEntity>> =
        flowOf(rows.values.filter { it.goalId == goalId })

    override suspend fun get(id: String): TaskEntity? = rows[id]

    override suspend fun upsert(entity: TaskEntity) {
        rows[entity.id] = entity
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }
}

private class FakeCaptureDao : CaptureDao {
    val rows = mutableMapOf<String, CaptureEntity>()

    override fun observeInbox(): Flow<List<CaptureEntity>> = flowOf(rows.values.toList())

    override suspend fun get(id: String): CaptureEntity? = rows[id]

    override suspend fun upsert(entity: CaptureEntity) {
        rows[entity.id] = entity
    }

    override suspend fun clarify(
        id: String,
        taskId: String,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    ) {
        val existing = rows[id] ?: return
        rows[id] =
            existing.copy(
                clarifiedTaskId = taskId,
                parseStatus = "parsed",
                syncStatus = syncStatus,
                localUpdatedAtEpochMs = localUpdatedAtEpochMs,
            )
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }
}

private class FakeTimeBlockDao : TimeBlockDao {
    val rows = mutableMapOf<String, TimeBlockEntity>()

    override fun observeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<TimeBlockEntity>> = flowOf(rows.values.toList())

    override fun observeForTask(taskId: String): Flow<List<TimeBlockEntity>> =
        flowOf(rows.values.filter { it.taskId == taskId })

    override suspend fun upsert(entity: TimeBlockEntity) {
        rows[entity.id] = entity
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }
}
