package dev.elay.data.local

import kotlinx.coroutines.flow.first
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

    private fun freshGoal(id: String) =
        GoalEntity(
            id = id,
            ownerId = "u-1",
            title = "Ship phase 1",
            notes = null,
            targetDate = "2026-12-31",
            status = "active",
            version = 1,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            syncStatus = "SYNCED",
            localUpdatedAtEpochMs = 1_000L,
        )

    @Test
    fun goalEntityCreateWriteReopenRead() =
        runTest {
            val key = "smoke-goal-${kotlin.random.Random.nextInt(1_000_000)}"
            val db1 = databaseBuilder(key).build()
            db1.goalDao().upsert(freshGoal("goal-1"))
            assertEquals(
                1,
                db1
                    .goalDao()
                    .observeAll()
                    .first()
                    .size,
            )
            db1.close()

            val db2 = databaseBuilder(key).build()
            val goals = db2.goalDao().observeAll().first()
            assertEquals(1, goals.size, "row must survive close/reopen")
            assertEquals("goal-1", goals.first().id)
            db2.close()
        }
}
