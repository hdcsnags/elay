package dev.elay.sync.impl

import dev.elay.data.local.OutboxDao
import dev.elay.data.local.OutboxEntity
import dev.elay.data.local.PlannerWriteDao
import dev.elay.data.remote.AuthGateway
import dev.elay.data.remote.DataGateway
import dev.elay.data.remote.MutationResult
import dev.elay.data.remote.SessionState
import dev.elay.domain.model.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Scripted fake [DataGateway] for the replay-engine tests (contracts/phase1-planner.md §3):
 * responses are queued per operationId so a test can script a full retry/backoff/conflict
 * sequence for one command while other commands fall back to [default]. No network involved.
 */
class FakeDataGateway(
    private val default: MutationResult = MutationResult.Applied(JsonObject(emptyMap()), 2),
) : DataGateway {
    val calls = mutableListOf<String>()
    private val responses = mutableMapOf<String, ArrayDeque<MutationResult>>()

    fun respond(
        operationId: String,
        vararg results: MutationResult,
    ) {
        responses.getOrPut(operationId) { ArrayDeque() }.addAll(results)
    }

    private fun next(
        operationId: String,
        tag: String,
    ): MutationResult {
        calls += tag
        val queue = responses[operationId]
        return if (queue != null && queue.isNotEmpty()) queue.removeFirst() else default
    }

    override suspend fun upsertGoal(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = next(operationId, "upsertGoal:$operationId")

    override suspend fun upsertMilestone(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = next(operationId, "upsertMilestone:$operationId")

    override suspend fun upsertTask(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = next(operationId, "upsertTask:$operationId")

    override suspend fun upsertCapture(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = next(operationId, "upsertCapture:$operationId")

    override suspend fun upsertTimeBlock(
        operationId: String,
        expectedVersion: Long,
        row: JsonObject,
    ): MutationResult = next(operationId, "upsertTimeBlock:$operationId")

    override suspend fun deleteGoal(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = next(operationId, "deleteGoal:$operationId")

    override suspend fun deleteTask(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = next(operationId, "deleteTask:$operationId")

    override suspend fun deleteCapture(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = next(operationId, "deleteCapture:$operationId")

    override suspend fun deleteTimeBlock(
        operationId: String,
        expectedVersion: Long,
        id: String,
    ): MutationResult = next(operationId, "deleteTimeBlock:$operationId")

    override suspend fun fetchSince(
        aggregate: String,
        sinceVersion: Long,
    ): Result<List<JsonObject>> = Result.success(emptyList())
}

/** Scripted fake [AuthGateway]: no network, session state and refresh count under test control. */
class FakeAuthGateway(
    initial: SessionState = SessionState.SignedIn(UserId("owner-1")),
) : AuthGateway {
    private val _session = MutableStateFlow(initial)
    override val session: Flow<SessionState> = _session

    var refreshCalls: Int = 0
        private set

    fun setSession(state: SessionState) {
        _session.value = state
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ): Result<UserId> = Result.success(UserId("owner-1"))

    override suspend fun signOut() = Unit

    override suspend fun refreshSession(): Result<Unit> {
        refreshCalls++
        return Result.success(Unit)
    }
}

/**
 * In-memory [OutboxDao]. Mirrors the real Room table's semantics that matter here: [mark]
 * increments `attempts` (the one true "attempt" counter, contracts/phase1-planner.md §3);
 * [setStatusOnly] (used by [FakePlannerWriteDao], modeling the shared `outbox` table it also
 * writes to in the real schema) does not.
 */
class FakeOutboxDao : OutboxDao {
    private val entries = linkedMapOf<String, OutboxEntity>()

    override suspend fun enqueue(entity: OutboxEntity) {
        entries[entity.operationId] = entity
    }

    override suspend fun pending(): List<OutboxEntity> =
        entries.values
            .filter { it.status == "PENDING" }
            .sortedWith(compareBy({ it.createdAtEpochMs }, { it.operationId }))

    override suspend fun mark(
        operationId: String,
        status: String,
    ) {
        val current = entries.getValue(operationId)
        entries[operationId] = current.copy(status = status, attempts = current.attempts + 1)
    }

    fun setStatusOnly(
        operationId: String,
        status: String,
    ) {
        val current = entries.getValue(operationId)
        entries[operationId] = current.copy(status = status)
    }

    fun snapshot(operationId: String): OutboxEntity = entries.getValue(operationId)
}

/**
 * In-memory [PlannerWriteDao]. The base class's `@Transaction ack*` methods are real (not
 * overridden) and call these fakes for their low-level pieces, so a call to e.g.
 * `ackGoalUpsert` exercises the same atomic-looking sequence as production: bump the cached
 * row, then mark the outbox row `ACKNOWLEDGED` — both landing in this one fake.
 */
class FakePlannerWriteDao(
    private val outbox: FakeOutboxDao,
) : PlannerWriteDao() {
    val acks = mutableListOf<String>()

    override suspend fun updateGoalAfterAck(
        id: String,
        version: Long,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    ) {
        acks += "goal:$id:v$version"
    }

    override suspend fun deleteGoalRow(id: String) {
        acks += "goal-delete:$id"
    }

    override suspend fun updateMilestoneAfterAck(
        id: String,
        version: Long,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    ) {
        acks += "milestone:$id:v$version"
    }

    override suspend fun deleteMilestoneRow(id: String) {
        acks += "milestone-delete:$id"
    }

    override suspend fun updateTaskAfterAck(
        id: String,
        version: Long,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    ) {
        acks += "task:$id:v$version"
    }

    override suspend fun deleteTaskRow(id: String) {
        acks += "task-delete:$id"
    }

    override suspend fun updateCaptureAfterAck(
        id: String,
        version: Long,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    ) {
        acks += "capture:$id:v$version"
    }

    override suspend fun deleteCaptureRow(id: String) {
        acks += "capture-delete:$id"
    }

    override suspend fun updateTimeBlockAfterAck(
        id: String,
        version: Long,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    ) {
        acks += "timeBlock:$id:v$version"
    }

    override suspend fun deleteTimeBlockRow(id: String) {
        acks += "timeBlock-delete:$id"
    }

    override suspend fun markOutbox(
        operationId: String,
        status: String,
    ) {
        outbox.setStatusOnly(operationId, status)
    }
}
