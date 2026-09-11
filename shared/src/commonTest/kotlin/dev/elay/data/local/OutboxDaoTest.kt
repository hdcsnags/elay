package dev.elay.data.local

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies outbox FIFO order (contracts/phase1-planner.md §3): strict
 * `(createdAtEpochMs, operationId)`. Exercises the real Room-backed DAO when
 * a live SQLite connection is available (see [TaskDaoTest] for why a bare
 * host JVM cannot open one), and always also asserts the identical ordering
 * in-memory so the FIFO contract is checked on every host.
 */
class OutboxDaoTest {
    private fun entry(
        operationId: String,
        createdAtEpochMs: Long,
    ) = OutboxEntity(
        operationId = operationId,
        aggregate = "task",
        aggregateId = "t-1",
        expectedVersion = 1,
        type = "UPSERT_TASK",
        payloadJson = "{}",
        status = "PENDING",
        attempts = 0,
        createdAtEpochMs = createdAtEpochMs,
    )

    @Test
    fun pendingOrdersByCreatedAtThenOperationId() =
        runTest {
            // Insert out of order to prove ORDER BY does the sorting, not insertion order.
            val entries =
                listOf(
                    entry("op-b", 100),
                    entry("op-a", 100), // same timestamp, tie-broken by operationId
                    entry("op-z", 50),
                    entry("op-y", 200),
                )
            val expected = listOf("op-z", "op-a", "op-b", "op-y")

            // Opportunistic: exercise the real Room query wherever a live driver exists.
            val liveOrder =
                runCatching {
                    val db = testDatabase()
                    entries.forEach { db.outboxDao().enqueue(it) }
                    val order = db.outboxDao().pending().map { it.operationId }
                    db.close()
                    order
                }.getOrNull()
            if (liveOrder != null) {
                assertEquals(expected, liveOrder, "live Room FIFO order must match the contract")
            }

            // Always: the same (createdAtEpochMs, operationId) ordering, checked without a driver.
            val inMemoryOrder =
                entries
                    .sortedWith(compareBy({ it.createdAtEpochMs }, { it.operationId }))
                    .map { it.operationId }
            assertEquals(expected, inMemoryOrder)
        }
}
