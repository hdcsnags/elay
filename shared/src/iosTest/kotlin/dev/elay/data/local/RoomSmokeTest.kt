package dev.elay.data.local

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR-005 Room smoke on the iOS simulator: create, write, reopen, read.
 * Runs in CI via :shared:iosSimulatorArm64Test.
 */
class RoomSmokeTest {
    private fun freshEntity(id: String) =
        OutboxEntity(
            operationId = id,
            aggregate = "task",
            aggregateId = "t-1",
            expectedVersion = 1,
            type = "UPSERT_TASK",
            payloadJson = "{}",
            status = "PENDING",
            attempts = 0,
            createdAtEpochMs = 1_000L,
        )

    @Test
    fun createWriteReopenRead() =
        runTest {
            val key = "smoke-${kotlin.random.Random.nextInt(1_000_000)}"
            val db1 = databaseBuilder(key).build()
            db1.outboxDao().enqueue(freshEntity("op-1"))
            assertEquals(1, db1.outboxDao().pending().size)
            db1.close()

            val db2 = databaseBuilder(key).build()
            val pending = db2.outboxDao().pending()
            assertEquals(1, pending.size, "row must survive close/reopen")
            assertEquals("op-1", pending.first().operationId)
            db2.close()
        }
}
