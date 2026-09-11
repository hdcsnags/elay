package dev.elay.sync.impl

import dev.elay.data.local.OutboxEntity
import dev.elay.data.remote.MutationResult
import dev.elay.data.remote.SessionState
import dev.elay.domain.model.UserId
import dev.elay.sync.Aggregate
import dev.elay.sync.SyncStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scripted replay-engine tests (contracts/phase1-planner.md §3) against fakes — no network,
 * no live Room/SQLite (see [dev.elay.data.local.TaskDaoTest] for why a bare JVM host can't
 * reliably open one; these fakes make the coordinator's own logic deterministic everywhere).
 */
class OutboxSyncCoordinatorTest {
    private class Harness(
        initialSession: SessionState = SessionState.SignedIn(UserId("owner-1")),
    ) {
        val outbox = FakeOutboxDao()
        val writeDao = FakePlannerWriteDao(outbox)
        val gateway = FakeDataGateway()
        val auth = FakeAuthGateway(initialSession)
        val delays = mutableListOf<Long>()
        var nowMs = 0L
        val coordinator =
            OutboxSyncCoordinator(
                outboxDao = outbox,
                writeDao = writeDao,
                dataGateway = gateway,
                authGateway = auth,
                clock =
                    ReplayClock(
                        now = { nowMs },
                        delay = { ms -> delays += ms },
                        random = { 1.0 },
                    ),
            )
    }

    private fun entity(
        opId: String,
        aggregate: Aggregate,
        aggregateId: String,
        createdAt: Long,
    ) = OutboxEntity(
        operationId = opId,
        aggregate = aggregate.wire,
        aggregateId = aggregateId,
        expectedVersion = 1,
        type = "UPSERT_${aggregate.wire.uppercase()}",
        payloadJson = """{"id":"$aggregateId"}""",
        status = "PENDING",
        attempts = 0,
        createdAtEpochMs = createdAt,
    )

    @Test
    fun fifoOrderKeptAcrossAggregates() =
        runTest {
            val h = Harness()
            // Out of enqueue order on purpose: pending() must still drain oldest-first,
            // which also respects each aggregateId's own sub-order.
            h.outbox.enqueue(entity("op-b", Aggregate.Task, "t-1", createdAt = 200))
            h.outbox.enqueue(entity("op-a", Aggregate.Goal, "g-1", createdAt = 100))
            h.outbox.enqueue(entity("op-c", Aggregate.Goal, "g-1", createdAt = 300))

            h.coordinator.replayOnce()

            assertEquals(
                listOf("upsertGoal:op-a", "upsertTask:op-b", "upsertGoal:op-c"),
                h.gateway.calls,
            )
            assertEquals(SyncStatus.Idle, h.coordinator.status.value)
        }

    @Test
    fun retryBackoffSequenceThenSucceeds() =
        runTest {
            val h = Harness()
            h.outbox.enqueue(entity("op-1", Aggregate.Goal, "g-1", createdAt = 0))
            h.gateway.respond(
                "op-1",
                MutationResult.Failed("http_503", retryable = true),
                MutationResult.Failed("http_503", retryable = true),
                MutationResult.Applied(JsonObject(emptyMap()), version = 4),
            )

            h.coordinator.replayOnce()

            // attempt exponent 0 then 1, random()=1.0 -> the cap of each range: 1s, then 2s.
            assertEquals(listOf(1_000L, 2_000L), h.delays)
            assertEquals(3, h.gateway.calls.size)
            assertEquals(listOf("goal:g-1:v4"), h.writeDao.acks)
            assertEquals("ACKNOWLEDGED", h.outbox.snapshot("op-1").status)
        }

    @Test
    fun conflictHoldsRowAndReportsStatus() =
        runTest {
            val h = Harness()
            h.outbox.enqueue(entity("op-1", Aggregate.Task, "t-1", createdAt = 0))
            val serverRow =
                buildJsonObject {
                    put("id", "t-1")
                    put("version", 5)
                }
            h.gateway.respond("op-1", MutationResult.Conflict(serverRow))

            h.coordinator.replayOnce()

            assertEquals("CONFLICT", h.outbox.snapshot("op-1").status)
            assertEquals(mapOf("op-1" to serverRow), h.coordinator.conflicts)
            assertEquals(SyncStatus.ConflictsHeld(1), h.coordinator.status.value)
            assertTrue(h.writeDao.acks.isEmpty())
        }

    @Test
    fun terminalFailureOnNonRetryableStopsThatEntryButDrainsTheRest() =
        runTest {
            val h = Harness()
            h.outbox.enqueue(entity("op-bad", Aggregate.Task, "t-1", createdAt = 0))
            h.outbox.enqueue(entity("op-ok", Aggregate.Goal, "g-1", createdAt = 1))
            h.gateway.respond("op-bad", MutationResult.Failed("http_422", retryable = false))

            h.coordinator.replayOnce()

            assertEquals("FAILED", h.outbox.snapshot("op-bad").status)
            assertEquals("ACKNOWLEDGED", h.outbox.snapshot("op-ok").status)
        }

    @Test
    fun idempotentDoubleAckIsSafe() =
        runTest {
            val h = Harness()
            h.outbox.enqueue(entity("op-1", Aggregate.Goal, "g-1", createdAt = 0))

            h.coordinator.replayOnce()
            h.coordinator.replayOnce() // pending() is now empty; must be a safe no-op

            assertEquals(1, h.gateway.calls.size)
            assertEquals(1, h.writeDao.acks.size)
            assertEquals("ACKNOWLEDGED", h.outbox.snapshot("op-1").status)
        }

    @Test
    fun authPausePathWhenAlreadySignedOut() =
        runTest {
            val h = Harness(initialSession = SessionState.SignedOut)
            h.outbox.enqueue(entity("op-1", Aggregate.Goal, "g-1", createdAt = 0))

            h.coordinator.replayOnce()

            assertTrue(h.gateway.calls.isEmpty())
            assertEquals(0, h.auth.refreshCalls)
            assertEquals("PENDING", h.outbox.snapshot("op-1").status)
            assertEquals(SyncStatus.Paused("signed_out"), h.coordinator.status.value)
        }

    @Test
    fun authPausePathOnReactiveAuthFailure() =
        runTest {
            val h = Harness()
            h.outbox.enqueue(entity("op-1", Aggregate.Goal, "g-1", createdAt = 0))
            h.outbox.enqueue(entity("op-2", Aggregate.Goal, "g-2", createdAt = 1))
            h.gateway.respond("op-1", MutationResult.Failed("auth_required", retryable = true))

            h.coordinator.replayOnce()

            assertEquals(1, h.gateway.calls.size, "must stop dispatching once paused for auth")
            assertEquals(1, h.auth.refreshCalls)
            assertEquals("PENDING", h.outbox.snapshot("op-1").status)
            assertEquals("PENDING", h.outbox.snapshot("op-2").status)
            assertEquals(SyncStatus.Paused("auth_required"), h.coordinator.status.value)
        }
}
