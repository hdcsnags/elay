package dev.elay.data.repository

import dev.elay.data.local.CaptureDao
import dev.elay.data.local.GoalDao
import dev.elay.data.local.MilestoneDao
import dev.elay.data.local.PlannerStagingDao
import dev.elay.data.local.SyncStatus
import dev.elay.data.local.TaskDao
import dev.elay.data.local.TimeBlockDao
import dev.elay.data.local.mapping.toDomain
import dev.elay.data.local.mapping.toEntity
import dev.elay.data.remote.dto.toDto
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
import dev.elay.sync.Aggregate
import dev.elay.sync.MutationCommand
import dev.elay.sync.SyncCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Local-first [PlannerRepository] (ADR-003; contracts/phase1-planner.md §3, §5).
 *
 * **Reads** observe Room directly: each `observe*`/single-shot getter maps the
 * relevant DAO's rows through the existing `data/local/mapping` functions —
 * no extra caching layer, so a Room write is visible to a collector on its
 * next emission.
 *
 * **Writes** (upsert/delete/clarify/dismiss) do exactly two things, in order:
 * 1. Stage the optimistic local row via a single Room `@Transaction` on
 *    [stagingDao] (`syncStatus = PENDING`, `expectedVersion` = the row's
 *    current cached version, or `0` for a brand new row — contract §2's
 *    insert rule). The caller's write is visible to readers the instant this
 *    call returns, rendered pending, never terminal (ADR-003 §6).
 * 2. Hand a [MutationCommand] built from that `expectedVersion` and a
 *    contract §4 wire payload (seat B2's `data/remote/dto` `toDto()`
 *    functions, `snake_case`/ISO-8601/exact-enum-string) to [syncCoordinator].
 *
 * ### Enqueue-ownership reconciliation
 * The brief's outline (§1 bullet 1) describes staging the local row AND an
 * outbox row in one transaction, then separately notifying the coordinator.
 * The merged `OutboxSyncCoordinator.enqueue` (`sync/impl`, out of this seat's
 * touch scope) already does the outbox write itself:
 * `outboxDao.enqueue(command.toOutboxEntity(clock.now()))`. So **the
 * coordinator owns every outbox row**; this repository's own transaction
 * ([stagingDao]'s `stage*` methods) never touches the `outbox` table, only
 * the cached entity tables. Calling [SyncCoordinator.enqueue] exactly once
 * per write is therefore the single outbox insert for that mutation — writing
 * a second row here (as the outline's own transaction would, taken literally)
 * would double-enqueue every mutation. This is the reconciliation the brief
 * asked for: read the merged coordinator, then let it be the sole writer.
 *
 * One accepted consequence: the local entity write and the outbox enqueue are
 * two separate Room-transaction-scoped operations rather than one shared SQL
 * transaction (a crash between them would leave a staged local change with no
 * outbox row to replay it). Closing that window would require either
 * touching `sync/impl` (out of scope for this seat) or bypassing
 * `SyncCoordinator.enqueue` to write the outbox row directly (which the brief
 * explicitly rules out: "you ENQUEUE, never dispatch"). Reported as a known
 * limitation, not silently absorbed.
 *
 * One facade over five aggregates plus their Daos — same shape as [PlannerRepository] itself.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class LocalFirstPlannerRepository(
    private val goalDao: GoalDao,
    private val milestoneDao: MilestoneDao,
    private val taskDao: TaskDao,
    private val captureDao: CaptureDao,
    private val timeBlockDao: TimeBlockDao,
    private val stagingDao: PlannerStagingDao,
    private val syncCoordinator: SyncCoordinator,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newOperationId: () -> String = { randomOperationId() },
) : PlannerRepository {
    private val wireJson = Json { encodeDefaults = true }

    // ---- Goals ----

    override fun observeGoals(): Flow<List<Goal>> = goalDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun goal(id: GoalId): Goal? = goalDao.get(id.value)?.toDomain()

    override suspend fun upsertGoal(goal: Goal) {
        val entity = goal.toEntity(SyncStatus.Pending, now())
        val expectedVersion = stagingDao.stageGoalUpsert(entity)
        enqueueUpsert(Aggregate.Goal, goal.id.value, expectedVersion, wireJson.encodeToString(goal.toDto()))
    }

    override suspend fun deleteGoal(id: GoalId) {
        val expectedVersion = stagingDao.stageGoalDelete(id.value)
        enqueueDelete(Aggregate.Goal, id.value, expectedVersion)
    }

    // ---- Milestones ----

    override fun observeMilestones(goalId: GoalId): Flow<List<Milestone>> =
        milestoneDao.observeForGoal(goalId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsertMilestone(milestone: Milestone) {
        val entity = milestone.toEntity(SyncStatus.Pending, now())
        val expectedVersion = stagingDao.stageMilestoneUpsert(entity)
        val wireInstant = wireNow()
        val payload = wireJson.encodeToString(milestone.toDto(createdAt = wireInstant, updatedAt = wireInstant))
        enqueueUpsert(Aggregate.Milestone, milestone.id.value, expectedVersion, payload)
    }

    // ---- Tasks ----

    override fun observeTodayTasks(
        dayStart: Instant,
        dayEnd: Instant,
    ): Flow<List<Task>> =
        taskDao
            .observeToday(dayStart.toEpochMilliseconds(), dayEnd.toEpochMilliseconds())
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeUnscheduledTasks(): Flow<List<Task>> =
        taskDao.observeUnscheduled().map { rows -> rows.map { it.toDomain() } }

    override fun observeTasksForGoal(goalId: GoalId): Flow<List<Task>> =
        taskDao.observeForGoal(goalId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun task(id: TaskId): Task? = taskDao.get(id.value)?.toDomain()

    override suspend fun upsertTask(task: Task) {
        val entity = task.toEntity(SyncStatus.Pending, now())
        val expectedVersion = stagingDao.stageTaskUpsert(entity)
        enqueueUpsert(Aggregate.Task, task.id.value, expectedVersion, wireJson.encodeToString(task.toDto()))
    }

    override suspend fun deleteTask(id: TaskId) {
        val expectedVersion = stagingDao.stageTaskDelete(id.value)
        enqueueDelete(Aggregate.Task, id.value, expectedVersion)
    }

    // ---- Captures (Inbox) ----

    override fun observeInbox(): Flow<List<Capture>> =
        captureDao.observeInbox().map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsertCapture(capture: Capture) {
        val entity = capture.toEntity(SyncStatus.Pending, now())
        val expectedVersion = stagingDao.stageCaptureUpsert(entity)
        val wireInstant = wireNow()
        val payload = wireJson.encodeToString(capture.toDto(createdAt = wireInstant, updatedAt = wireInstant))
        enqueueUpsert(Aggregate.Capture, capture.id.value, expectedVersion, payload)
    }

    /**
     * The capture becomes `Parsed` + `clarifiedTaskId` locally; the new [Task] itself is the
     * caller's job via a separate [upsertTask] call (brief §1: keep the two mutations separate
     * commands). Only the capture's own version gates its outbox `expectedVersion` here.
     */
    override suspend fun clarifyCapture(
        id: CaptureId,
        taskId: TaskId,
    ) {
        val updated = stagingDao.stageCaptureClarify(id.value, taskId.value, now()) ?: return
        enqueueCapturePayload(updated.toDomain())
    }

    override suspend fun dismissCapture(id: CaptureId) {
        val updated = stagingDao.stageCaptureDismiss(id.value, now()) ?: return
        enqueueCapturePayload(updated.toDomain())
    }

    private suspend fun enqueueCapturePayload(capture: Capture) {
        val wireInstant = wireNow()
        val payload = wireJson.encodeToString(capture.toDto(createdAt = wireInstant, updatedAt = wireInstant))
        enqueueUpsert(Aggregate.Capture, capture.id.value, capture.version, payload)
    }

    // ---- Time blocks (Plan) ----

    override fun observeBlocks(
        from: Instant,
        to: Instant,
    ): Flow<List<TimeBlock>> =
        timeBlockDao
            .observeRange(from.toEpochMilliseconds(), to.toEpochMilliseconds())
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeBlocksForTask(taskId: TaskId): Flow<List<TimeBlock>> =
        timeBlockDao.observeForTask(taskId.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsertBlock(block: TimeBlock) {
        val entity = block.toEntity(SyncStatus.Pending, now())
        val expectedVersion = stagingDao.stageTimeBlockUpsert(entity)
        val wireInstant = wireNow()
        val payload = wireJson.encodeToString(block.toDto(createdAt = wireInstant, updatedAt = wireInstant))
        enqueueUpsert(Aggregate.TimeBlock, block.id.value, expectedVersion, payload)
    }

    override suspend fun deleteBlock(id: TimeBlockId) {
        val expectedVersion = stagingDao.stageTimeBlockDelete(id.value)
        enqueueDelete(Aggregate.TimeBlock, id.value, expectedVersion)
    }

    // ---- shared plumbing ----

    /**
     * [Milestone], [Capture] and [TimeBlock] have no `createdAt`/`updatedAt` in the frozen
     * domain model (contracts/phase1-planner.md §5) even though their wire row requires both
     * (§4) — and their Room entities don't cache those columns either (seat B1's schema), so
     * there is no cached value to reuse. Every mutation of these three aggregates stamps both
     * wire fields with the current time; the server is the source of truth for the real
     * `created_at` on first insert and thereafter. Documented as a known limitation, not a
     * silent approximation.
     */
    private fun wireNow(): Instant = Instant.fromEpochMilliseconds(now())

    private suspend fun enqueueUpsert(
        aggregate: Aggregate,
        aggregateId: String,
        expectedVersion: Long,
        payloadJson: String,
    ) {
        syncCoordinator.enqueue(
            MutationCommand.Upsert(
                operationId = newOperationId(),
                aggregate = aggregate,
                aggregateId = aggregateId,
                expectedVersion = expectedVersion,
                payloadJson = payloadJson,
            ),
        )
    }

    private suspend fun enqueueDelete(
        aggregate: Aggregate,
        aggregateId: String,
        expectedVersion: Long,
    ) {
        syncCoordinator.enqueue(
            MutationCommand.Delete(
                operationId = newOperationId(),
                aggregate = aggregate,
                aggregateId = aggregateId,
                expectedVersion = expectedVersion,
            ),
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun randomOperationId(): String = Uuid.random().toString()
