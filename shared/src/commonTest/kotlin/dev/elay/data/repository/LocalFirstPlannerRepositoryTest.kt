package dev.elay.data.repository

import dev.elay.data.remote.dto.CaptureDto
import dev.elay.data.remote.dto.GoalDto
import dev.elay.data.remote.dto.MilestoneDto
import dev.elay.data.remote.dto.TaskDto
import dev.elay.data.remote.dto.TimeBlockDto
import dev.elay.domain.model.BlockStatus
import dev.elay.domain.model.BlockType
import dev.elay.domain.model.Capture
import dev.elay.domain.model.CaptureId
import dev.elay.domain.model.CaptureSource
import dev.elay.domain.model.Goal
import dev.elay.domain.model.GoalId
import dev.elay.domain.model.GoalStatus
import dev.elay.domain.model.Milestone
import dev.elay.domain.model.MilestoneId
import dev.elay.domain.model.MilestoneStatus
import dev.elay.domain.model.ParseStatus
import dev.elay.domain.model.Priority
import dev.elay.domain.model.Task
import dev.elay.domain.model.TaskId
import dev.elay.domain.model.TaskStatus
import dev.elay.domain.model.TimeBlock
import dev.elay.domain.model.TimeBlockId
import dev.elay.domain.model.UserId
import dev.elay.sync.Aggregate
import dev.elay.sync.MutationCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests LocalFirstPlannerRepository against hand-written Dao/coordinator fakes
 * (see FakeRepositoryFixtures.kt) — no live sqlite on this host (contracts
 * §1's TaskDaoTest/OutboxDaoTest comments explain why). Covers brief §3's
 * checklist: exactly one outbox row per write with the right
 * expectedVersion/wire payload, immediate optimistic (PENDING) rendering,
 * correct read mapping, clarify's capture-only mutation, and delete.
 */
class LocalFirstPlannerRepositoryTest {
    private val owner = UserId("owner-1")
    private val fixedNowMs = 1_780_000_000_000L

    private class Harness {
        val tables = FakeTables()
        val goalDao = FakeGoalDao(tables)
        val milestoneDao = FakeMilestoneDao(tables)
        val taskDao = FakeTaskDao(tables)
        val captureDao = FakeCaptureDao(tables)
        val timeBlockDao = FakeTimeBlockDao(tables)
        val stagingDao = FakePlannerStagingDao(tables)
        val coordinator = FakeSyncCoordinator()
        var nowMs = 0L
        var nextOperationId = 0

        val repository =
            LocalFirstPlannerRepository(
                goalDao = goalDao,
                milestoneDao = milestoneDao,
                taskDao = taskDao,
                captureDao = captureDao,
                timeBlockDao = timeBlockDao,
                stagingDao = stagingDao,
                syncCoordinator = coordinator,
                now = { nowMs },
                newOperationId = { "op-${nextOperationId++}" },
            )
    }

    private fun goal(version: Long = 1) =
        Goal(
            id = GoalId("goal-1"),
            ownerId = owner,
            title = "Ship phase 1",
            notes = null,
            targetDate = null,
            status = GoalStatus.Active,
            version = version,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

    private fun task(
        id: String = "task-1",
        version: Long = 1,
        dueStart: Instant? = null,
    ) = Task(
        id = TaskId(id),
        ownerId = owner,
        goalId = null,
        milestoneId = null,
        title = "Write tests",
        notes = null,
        status = TaskStatus.Todo,
        priority = Priority.Normal,
        effort = null,
        estimateMinutes = null,
        dueStart = dueStart,
        dueEnd = null,
        recurrenceRule = null,
        tags = emptyList(),
        version = version,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun capture(
        id: String = "capture-1",
        version: Long = 1,
        parseStatus: ParseStatus = ParseStatus.Unparsed,
        clarifiedTaskId: TaskId? = null,
    ) = Capture(
        id = CaptureId(id),
        ownerId = owner,
        body = "Call mom",
        source = CaptureSource.Quick,
        parseStatus = parseStatus,
        capturedAt = Instant.fromEpochMilliseconds(0),
        clarifiedTaskId = clarifiedTaskId,
        version = version,
    )

    private fun milestone(version: Long = 1) =
        Milestone(
            id = MilestoneId("milestone-1"),
            goalId = GoalId("goal-1"),
            title = "Module 1",
            targetDate = null,
            sortOrder = 0,
            status = MilestoneStatus.Active,
            version = version,
        )

    private fun block(version: Long = 1) =
        TimeBlock(
            id = TimeBlockId("block-1"),
            ownerId = owner,
            taskId = null,
            title = "Deep work",
            startsAt = Instant.fromEpochMilliseconds(0),
            endsAt = Instant.fromEpochMilliseconds(3_600_000),
            originTz = TimeZone.UTC,
            type = BlockType.Focus,
            status = BlockStatus.Scheduled,
            recurrenceRule = null,
            allDay = false,
            version = version,
        )

    // ---- Goals ----

    @Test
    fun upsertGoal_newRow_stagesPendingAndEnqueuesOneUpsertWithZeroExpectedVersion() =
        runTest {
            val h = Harness()

            h.repository.upsertGoal(goal())

            assertEquals(
                "PENDING",
                h.tables.goals.value
                    .getValue("goal-1")
                    .syncStatus,
            )
            assertEquals(1, h.coordinator.enqueued.size, "exactly one outbox row per mutation")
            val command = h.coordinator.enqueued.single() as MutationCommand.Upsert
            assertEquals(Aggregate.Goal, command.aggregate)
            assertEquals("goal-1", command.aggregateId)
            assertEquals(
                0L,
                command.expectedVersion,
                "brand new local row -> expectedVersion 0 (contract §2 insert rule)",
            )
            val dto = Json.decodeFromString(GoalDto.serializer(), command.payloadJson)
            assertEquals("Ship phase 1", dto.title)
            assertEquals("private", dto.visibility)
        }

    @Test
    fun upsertGoal_existingRow_expectedVersionIsCurrentLocalVersionNotTheDomainArgument() =
        runTest {
            val h = Harness()
            h.repository.upsertGoal(goal(version = 3))
            h.coordinator.enqueued.clear()

            // Caller edits the title but the version they hold is still the last-acked one (3);
            // expectedVersion must come from the CURRENT LOCAL row, not be silently trusted from the arg.
            h.repository.upsertGoal(goal(version = 3).copy(title = "Ship phase 1 (edited)"))

            assertEquals(1, h.coordinator.enqueued.size)
            val command = h.coordinator.enqueued.single() as MutationCommand.Upsert
            assertEquals(3L, command.expectedVersion)
        }

    @Test
    fun deleteGoal_removesLocallyAndEnqueuesDeleteWithCurrentVersion() =
        runTest {
            val h = Harness()
            h.repository.upsertGoal(goal(version = 5))
            h.coordinator.enqueued.clear()

            h.repository.deleteGoal(GoalId("goal-1"))

            assertTrue(
                h.tables.goals.value
                    .isEmpty(),
            )
            assertEquals(1, h.coordinator.enqueued.size)
            val command = h.coordinator.enqueued.single() as MutationCommand.Delete
            assertEquals(Aggregate.Goal, command.aggregate)
            assertEquals(5L, command.expectedVersion)
        }

    @Test
    fun observeGoals_mapsStagedEntityBackToDomain() =
        runTest {
            val h = Harness()

            h.repository.upsertGoal(goal())

            val goals = h.repository.observeGoals().first()
            assertEquals(listOf(GoalId("goal-1")), goals.map { it.id })
            assertEquals(GoalStatus.Active, goals.single().status)
        }

    // ---- Milestones ----

    @Test
    fun upsertMilestone_enqueuesOneCommandWithSyntheticWireTimestamps() =
        runTest {
            val h = Harness()
            h.nowMs = fixedNowMs

            h.repository.upsertMilestone(milestone())

            assertEquals(1, h.coordinator.enqueued.size)
            val command = h.coordinator.enqueued.single() as MutationCommand.Upsert
            assertEquals(Aggregate.Milestone, command.aggregate)
            assertEquals(0L, command.expectedVersion)
            val dto = Json.decodeFromString(MilestoneDto.serializer(), command.payloadJson)
            assertEquals("goal-1", dto.goalId)
            assertEquals(Instant.fromEpochMilliseconds(fixedNowMs).toString(), dto.createdAt)
            assertEquals(Instant.fromEpochMilliseconds(fixedNowMs).toString(), dto.updatedAt)
        }

    // ---- Tasks ----

    @Test
    fun upsertTask_thenDeleteTask_eachEnqueueExactlyOneCommand() =
        runTest {
            val h = Harness()

            h.repository.upsertTask(task())
            h.repository.deleteTask(TaskId("task-1"))

            assertEquals(2, h.coordinator.enqueued.size)
            assertTrue(h.coordinator.enqueued[0] is MutationCommand.Upsert)
            assertTrue(h.coordinator.enqueued[1] is MutationCommand.Delete)
            assertTrue(
                h.tables.tasks.value
                    .isEmpty(),
            )
        }

    @Test
    fun observeTodayTasks_appliesInclusiveBoundaryAfterStaging() =
        runTest {
            val h = Harness()
            h.repository.upsertTask(task("at-start", dueStart = Instant.fromEpochMilliseconds(1_000)))
            h.repository.upsertTask(task("before", dueStart = Instant.fromEpochMilliseconds(999)))
            h.repository.upsertTask(task("unscheduled", dueStart = null))

            val today =
                h.repository
                    .observeTodayTasks(Instant.fromEpochMilliseconds(1_000), Instant.fromEpochMilliseconds(2_000))
                    .first()

            assertEquals(listOf(TaskId("at-start")), today.map { it.id })
        }

    // ---- Captures ----

    @Test
    fun upsertCapture_stagesPendingAndEnqueuesWirePayload() =
        runTest {
            val h = Harness()
            h.nowMs = fixedNowMs

            h.repository.upsertCapture(capture())

            assertEquals(
                "PENDING",
                h.tables.captures.value
                    .getValue("capture-1")
                    .syncStatus,
            )
            val command = h.coordinator.enqueued.single() as MutationCommand.Upsert
            assertEquals(Aggregate.Capture, command.aggregate)
            val dto = Json.decodeFromString(CaptureDto.serializer(), command.payloadJson)
            assertEquals("unparsed", dto.aiParseStatus)
        }

    @Test
    fun clarifyCapture_updatesOnlyTheCaptureAndEnqueuesItsUpsertNotTheTask() =
        runTest {
            val h = Harness()
            h.repository.upsertCapture(capture(version = 1))
            h.coordinator.enqueued.clear()

            h.repository.clarifyCapture(CaptureId("capture-1"), TaskId("task-99"))

            val entity =
                h.tables.captures.value
                    .getValue("capture-1")
            assertEquals("parsed", entity.parseStatus)
            assertEquals("task-99", entity.clarifiedTaskId)
            assertEquals("PENDING", entity.syncStatus)
            assertEquals(1, h.coordinator.enqueued.size, "clarify must enqueue only the capture's own mutation")
            val command = h.coordinator.enqueued.single() as MutationCommand.Upsert
            assertEquals(Aggregate.Capture, command.aggregate)
            assertEquals("capture-1", command.aggregateId)
            assertEquals(
                1L,
                command.expectedVersion,
                "clarify never bumps the local version itself — that's ACK's job",
            )
            val dto = Json.decodeFromString(CaptureDto.serializer(), command.payloadJson)
            assertEquals("parsed", dto.aiParseStatus)
            assertEquals("task-99", dto.clarifiedTaskId)
        }

    @Test
    fun dismissCapture_marksDismissedAndRemovesFromInbox() =
        runTest {
            val h = Harness()
            h.repository.upsertCapture(capture())
            h.coordinator.enqueued.clear()

            h.repository.dismissCapture(CaptureId("capture-1"))

            assertEquals(
                "dismissed",
                h.tables.captures.value
                    .getValue("capture-1")
                    .parseStatus,
            )
            assertTrue(
                h.repository
                    .observeInbox()
                    .first()
                    .isEmpty(),
            )
            val command = h.coordinator.enqueued.single() as MutationCommand.Upsert
            val dto = Json.decodeFromString(CaptureDto.serializer(), command.payloadJson)
            assertEquals("dismissed", dto.aiParseStatus)
        }

    @Test
    fun clarifyCapture_missingRow_isANoOpAndEnqueuesNothing() =
        runTest {
            val h = Harness()

            h.repository.clarifyCapture(CaptureId("does-not-exist"), TaskId("task-1"))

            assertTrue(h.coordinator.enqueued.isEmpty())
            assertNull(h.tables.captures.value["does-not-exist"])
        }

    // ---- Time blocks ----

    @Test
    fun upsertBlock_thenDeleteBlock_eachEnqueueExactlyOneCommandWithCorrectVersion() =
        runTest {
            val h = Harness()

            h.repository.upsertBlock(block(version = 1))
            val upsertCommand = h.coordinator.enqueued.single() as MutationCommand.Upsert
            assertEquals(0L, upsertCommand.expectedVersion)
            val dto = Json.decodeFromString(TimeBlockDto.serializer(), upsertCommand.payloadJson)
            assertEquals("focus", dto.type)

            h.coordinator.enqueued.clear()
            h.repository.deleteBlock(TimeBlockId("block-1"))

            assertTrue(
                h.tables.timeBlocks.value
                    .isEmpty(),
            )
            val deleteCommand = h.coordinator.enqueued.single() as MutationCommand.Delete
            assertEquals(Aggregate.TimeBlock, deleteCommand.aggregate)
        }

    // ---- Reads: task-round-trip wire-format sanity ----

    @Test
    fun upsertTask_payloadRoundTripsThroughTaskDto() =
        runTest {
            val h = Harness()

            h.repository.upsertTask(task())

            val command = h.coordinator.enqueued.single() as MutationCommand.Upsert
            val dto = Json.decodeFromString(TaskDto.serializer(), command.payloadJson)
            assertEquals("todo", dto.status)
            assertEquals(1, dto.priority)
        }

    @Test
    fun noWriteEverEnqueuesMoreThanOneOutboxRow() =
        runTest {
            val h = Harness()

            h.repository.upsertGoal(goal())
            h.repository.upsertTask(task())
            h.repository.upsertCapture(capture())
            h.repository.upsertMilestone(milestone())
            h.repository.upsertBlock(block())

            assertEquals(5, h.coordinator.enqueued.size, "one command enqueued per write call, never doubled")
        }
}
