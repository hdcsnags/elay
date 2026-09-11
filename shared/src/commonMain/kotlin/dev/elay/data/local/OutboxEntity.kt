package dev.elay.data.local

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query

/**
 * Append-only mutation outbox (ADR-003, contracts/phase0-foundation.md).
 * Skeleton laid by the concierge to prove Room/KSP wiring; seat B1 extends.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val operationId: String,
    val aggregate: String,
    val aggregateId: String,
    val expectedVersion: Long,
    val type: String,
    val payloadJson: String,
    val status: String,
    val attempts: Int,
    val createdAtEpochMs: Long,
)

@Dao
interface OutboxDao {
    @Insert
    suspend fun enqueue(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY createdAtEpochMs, operationId")
    suspend fun pending(): List<OutboxEntity>

    @Query("UPDATE outbox SET status = :status, attempts = attempts + 1 WHERE operationId = :operationId")
    suspend fun mark(
        operationId: String,
        status: String,
    )
}
