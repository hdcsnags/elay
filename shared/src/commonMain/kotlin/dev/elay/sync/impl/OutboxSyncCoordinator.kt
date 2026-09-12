package dev.elay.sync.impl

import dev.elay.data.local.OutboxDao
import dev.elay.data.local.OutboxEntity
import dev.elay.data.local.PlannerWriteDao
import dev.elay.data.remote.AuthGateway
import dev.elay.data.remote.DataGateway
import dev.elay.data.remote.MutationResult
import dev.elay.data.remote.SessionState
import dev.elay.data.remote.impl.AUTH_REQUIRED_REASON
import dev.elay.sync.Aggregate
import dev.elay.sync.MutationCommand
import dev.elay.sync.SyncCoordinator
import dev.elay.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Clock

private const val BACKOFF_BASE_MS = 1_000L
private const val BACKOFF_FACTOR = 2.0
private const val BACKOFF_CAP_MS = 120_000L

/**
 * The testing seam for §3's timing rules, bundled into one value so the coordinator's
 * constructor stays small: [now] for timestamps, [delay] for the full-jitter backoff wait
 * (awaited in-process so a test can replace it with a non-suspending recorder instead of
 * waiting out real wall-clock time), [random] for the jitter draw.
 */
class ReplayClock(
    val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val delay: suspend (Long) -> Unit = { millis -> kotlinx.coroutines.delay(millis) },
    val random: () -> Double = { Random.nextDouble() },
)

/**
 * contracts/phase1-planner.md §3, implemented exactly:
 * - strict FIFO by `(createdAtEpochMs, operationId)` — [OutboxDao.pending] already returns
 *   that global order, which is a superset ordering that also holds per aggregateId.
 * - one mutation in flight globally — this coordinator drives a single sequential loop,
 *   never dispatching concurrently.
 * - full-jitter exponential backoff (base 1s, factor 2, cap 120s) awaited via [ReplayClock].
 * - an auth failure ([dev.elay.data.remote.impl.AUTH_REQUIRED_REASON]) pauses the queue and
 *   requests a session refresh instead of retrying blindly.
 * - a `conflict` outcome holds the row (never auto-mutates it further) and stores the
 *   server's current row for the caller to resolve.
 * - a successful ack goes through [PlannerWriteDao]'s existing `@Transaction` methods, which
 *   already bump the cached row and mark the outbox entry `ACKNOWLEDGED` atomically.
 */
class OutboxSyncCoordinator(
    private val outboxDao: OutboxDao,
    private val writeDao: PlannerWriteDao,
    private val dataGateway: DataGateway,
    private val authGateway: AuthGateway,
    private val clock: ReplayClock = ReplayClock(),
) : SyncCoordinator {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    /** Covariant override — [SyncCoordinator] promises a `Flow`; callers with the concrete
     * type (tests, in-process wiring) can read [StateFlow.value] directly. */
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Held conflicts (contract §3): operationId -> server's current row. Phase 1 keeps this
     * in memory only — `data/local`'s [OutboxEntity] has no column to persist the server row
     * (see seat B2's report for the schema extension this would need), so a process restart
     * loses the cached server snapshot even though the outbox row correctly stays `CONFLICT`
     * and is never silently retried.
     */
    private val heldConflicts = mutableMapOf<String, JsonObject>()
    val conflicts: Map<String, JsonObject> get() = heldConflicts.toMap()

    override suspend fun enqueue(command: MutationCommand) {
        outboxDao.enqueue(command.toOutboxEntity(clock.now()))
    }

    override suspend fun replayOnce() {
        while (true) {
            if (authGateway.session.first() is SessionState.SignedOut) {
                _status.value = SyncStatus.Paused("signed_out")
                return
            }
            val pending = outboxDao.pending()
            if (pending.isEmpty()) break
            _status.value = SyncStatus.Replaying(pending.size)

            val head = pending.first()
            outboxDao.mark(head.operationId, "IN_FLIGHT")
            val command = head.toMutationCommand()

            when (val result = dispatch(command)) {
                is MutationResult.Applied -> {
                    ack(command, result)
                    heldConflicts.remove(command.operationId)
                }
                is MutationResult.Conflict -> holdConflict(head, result)
                is MutationResult.Failed -> if (!handleFailure(head, result)) return
            }
        }
        _status.value =
            if (heldConflicts.isNotEmpty()) SyncStatus.ConflictsHeld(heldConflicts.size) else SyncStatus.Idle
    }

    /** Returns `false` when replay must stop entirely this call (auth pause). */
    private suspend fun handleFailure(
        entry: OutboxEntity,
        failure: MutationResult.Failed,
    ): Boolean {
        // Diagnosability (concierge 2026-09-12): failures must reach the platform log.
        val failNote = "${failure.reason} retryable=${failure.retryable}"
        println("ELAY sync failure: op=${entry.operationId} ${entry.type} -> $failNote")
        if (failure.reason == AUTH_REQUIRED_REASON) {
            writeDao.markOutbox(entry.operationId, "PENDING")
            authGateway.refreshSession()
            _status.value = SyncStatus.Paused(AUTH_REQUIRED_REASON)
            return false
        }
        if (failure.retryable) {
            writeDao.markOutbox(entry.operationId, "PENDING")
            clock.delay(fullJitterBackoff(entry.attempts, clock.random))
        } else {
            writeDao.markOutbox(entry.operationId, "FAILED")
        }
        return true
    }

    private suspend fun holdConflict(
        entry: OutboxEntity,
        conflict: MutationResult.Conflict,
    ) {
        writeDao.markOutbox(entry.operationId, "CONFLICT")
        heldConflicts[entry.operationId] = conflict.current
    }

    private suspend fun ack(
        command: MutationCommand,
        applied: MutationResult.Applied,
    ) {
        when (command) {
            is MutationCommand.Upsert -> ackUpsert(command, applied.version)
            is MutationCommand.Delete -> ackDelete(command)
        }
    }

    private suspend fun ackUpsert(
        command: MutationCommand.Upsert,
        version: Long,
    ) {
        val ts = clock.now()
        val id = command.aggregateId
        val opId = command.operationId
        when (command.aggregate) {
            Aggregate.Goal -> writeDao.ackGoalUpsert(id, version, opId, ts)
            Aggregate.Milestone -> writeDao.ackMilestoneUpsert(id, version, opId, ts)
            Aggregate.Task -> writeDao.ackTaskUpsert(id, version, opId, ts)
            Aggregate.Capture -> writeDao.ackCaptureUpsert(id, version, opId, ts)
            Aggregate.TimeBlock -> writeDao.ackTimeBlockUpsert(id, version, opId, ts)
        }
    }

    private suspend fun ackDelete(command: MutationCommand.Delete) {
        val id = command.aggregateId
        val opId = command.operationId
        when (command.aggregate) {
            Aggregate.Goal -> writeDao.ackGoalDelete(id, opId)
            Aggregate.Milestone -> writeDao.ackMilestoneDelete(id, opId)
            Aggregate.Task -> writeDao.ackTaskDelete(id, opId)
            Aggregate.Capture -> writeDao.ackCaptureDelete(id, opId)
            Aggregate.TimeBlock -> writeDao.ackTimeBlockDelete(id, opId)
        }
    }

    private suspend fun dispatch(command: MutationCommand): MutationResult =
        when (command) {
            is MutationCommand.Upsert -> dispatchUpsert(command)
            is MutationCommand.Delete -> dispatchDelete(command)
        }

    private suspend fun dispatchUpsert(command: MutationCommand.Upsert): MutationResult {
        val row = Json.parseToJsonElement(command.payloadJson) as JsonObject
        val opId = command.operationId
        val expected = command.expectedVersion
        return when (command.aggregate) {
            Aggregate.Goal -> dataGateway.upsertGoal(opId, expected, row)
            Aggregate.Milestone -> dataGateway.upsertMilestone(opId, expected, row)
            Aggregate.Task -> dataGateway.upsertTask(opId, expected, row)
            Aggregate.Capture -> dataGateway.upsertCapture(opId, expected, row)
            Aggregate.TimeBlock -> dataGateway.upsertTimeBlock(opId, expected, row)
        }
    }

    private suspend fun dispatchDelete(command: MutationCommand.Delete): MutationResult {
        val opId = command.operationId
        val expected = command.expectedVersion
        val id = command.aggregateId
        return when (command.aggregate) {
            Aggregate.Goal -> dataGateway.deleteGoal(opId, expected, id)
            Aggregate.Task -> dataGateway.deleteTask(opId, expected, id)
            Aggregate.Capture -> dataGateway.deleteCapture(opId, expected, id)
            Aggregate.TimeBlock -> dataGateway.deleteTimeBlock(opId, expected, id)
            // No rpc_delete_milestone in the frozen DataGateway (contracts/phase1-planner.md
            // §5) — milestones are removed via the parent goal's cascade delete.
            Aggregate.Milestone ->
                MutationResult.Failed(reason = "milestone_delete_unsupported", retryable = false)
        }
    }
}

private fun MutationCommand.toOutboxEntity(createdAtEpochMs: Long): OutboxEntity {
    val verb = if (this is MutationCommand.Upsert) "UPSERT" else "DELETE"
    return OutboxEntity(
        operationId = operationId,
        aggregate = aggregate.wire,
        aggregateId = aggregateId,
        expectedVersion = expectedVersion,
        type = "${verb}_${aggregate.wire.uppercase()}",
        payloadJson = (this as? MutationCommand.Upsert)?.payloadJson ?: "{}",
        status = "PENDING",
        attempts = 0,
        createdAtEpochMs = createdAtEpochMs,
    )
}

private fun OutboxEntity.toMutationCommand(): MutationCommand {
    val agg = Aggregate.entries.first { it.wire == aggregate }
    return if (type.startsWith("UPSERT")) {
        MutationCommand.Upsert(operationId, agg, aggregateId, expectedVersion, payloadJson)
    } else {
        MutationCommand.Delete(operationId, agg, aggregateId, expectedVersion)
    }
}

/** Full jitter (AWS backoff family): `random(0, min(cap, base * factor^attempt))`. */
private fun fullJitterBackoff(
    attempt: Int,
    random: () -> Double,
): Long {
    val exponential = BACKOFF_BASE_MS * BACKOFF_FACTOR.pow(attempt)
    val capped = min(BACKOFF_CAP_MS.toDouble(), exponential)
    return (random() * capped).toLong()
}
