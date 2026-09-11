package dev.elay.data.local

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction

/**
 * Atomic "apply ACK" writes (contracts/phase1-planner.md §3): a server
 * acknowledgement must bump the cached row's version/sync state AND mark the
 * outbox entry ACKNOWLEDGED in one Room transaction — never two writes that
 * could observe a torn state on crash/replay.
 */
@Dao
abstract class PlannerWriteDao {
    @Query(
        "UPDATE goals SET version = :version, syncStatus = :syncStatus, " +
            "localUpdatedAtEpochMs = :localUpdatedAtEpochMs WHERE id = :id",
    )
    abstract suspend fun updateGoalAfterAck(
        id: String,
        version: Long,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    )

    @Query("DELETE FROM goals WHERE id = :id")
    abstract suspend fun deleteGoalRow(id: String)

    @Query(
        "UPDATE milestones SET version = :version, syncStatus = :syncStatus, " +
            "localUpdatedAtEpochMs = :localUpdatedAtEpochMs WHERE id = :id",
    )
    abstract suspend fun updateMilestoneAfterAck(
        id: String,
        version: Long,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    )

    @Query("DELETE FROM milestones WHERE id = :id")
    abstract suspend fun deleteMilestoneRow(id: String)

    @Query(
        "UPDATE tasks SET version = :version, syncStatus = :syncStatus, " +
            "localUpdatedAtEpochMs = :localUpdatedAtEpochMs WHERE id = :id",
    )
    abstract suspend fun updateTaskAfterAck(
        id: String,
        version: Long,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    )

    @Query("DELETE FROM tasks WHERE id = :id")
    abstract suspend fun deleteTaskRow(id: String)

    @Query(
        "UPDATE captures SET version = :version, syncStatus = :syncStatus, " +
            "localUpdatedAtEpochMs = :localUpdatedAtEpochMs WHERE id = :id",
    )
    abstract suspend fun updateCaptureAfterAck(
        id: String,
        version: Long,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    )

    @Query("DELETE FROM captures WHERE id = :id")
    abstract suspend fun deleteCaptureRow(id: String)

    @Query(
        "UPDATE time_blocks SET version = :version, syncStatus = :syncStatus, " +
            "localUpdatedAtEpochMs = :localUpdatedAtEpochMs WHERE id = :id",
    )
    abstract suspend fun updateTimeBlockAfterAck(
        id: String,
        version: Long,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    )

    @Query("DELETE FROM time_blocks WHERE id = :id")
    abstract suspend fun deleteTimeBlockRow(id: String)

    @Query("UPDATE outbox SET status = :status WHERE operationId = :operationId")
    abstract suspend fun markOutbox(
        operationId: String,
        status: String,
    )

    @Transaction
    open suspend fun ackGoalUpsert(
        id: String,
        version: Long,
        operationId: String,
        localUpdatedAtEpochMs: Long,
    ) {
        updateGoalAfterAck(id, version, SyncStatus.Synced.wire, localUpdatedAtEpochMs)
        markOutbox(operationId, "ACKNOWLEDGED")
    }

    @Transaction
    open suspend fun ackGoalDelete(
        id: String,
        operationId: String,
    ) {
        deleteGoalRow(id)
        markOutbox(operationId, "ACKNOWLEDGED")
    }

    @Transaction
    open suspend fun ackMilestoneUpsert(
        id: String,
        version: Long,
        operationId: String,
        localUpdatedAtEpochMs: Long,
    ) {
        updateMilestoneAfterAck(id, version, SyncStatus.Synced.wire, localUpdatedAtEpochMs)
        markOutbox(operationId, "ACKNOWLEDGED")
    }

    @Transaction
    open suspend fun ackMilestoneDelete(
        id: String,
        operationId: String,
    ) {
        deleteMilestoneRow(id)
        markOutbox(operationId, "ACKNOWLEDGED")
    }

    @Transaction
    open suspend fun ackTaskUpsert(
        id: String,
        version: Long,
        operationId: String,
        localUpdatedAtEpochMs: Long,
    ) {
        updateTaskAfterAck(id, version, SyncStatus.Synced.wire, localUpdatedAtEpochMs)
        markOutbox(operationId, "ACKNOWLEDGED")
    }

    @Transaction
    open suspend fun ackTaskDelete(
        id: String,
        operationId: String,
    ) {
        deleteTaskRow(id)
        markOutbox(operationId, "ACKNOWLEDGED")
    }

    @Transaction
    open suspend fun ackCaptureUpsert(
        id: String,
        version: Long,
        operationId: String,
        localUpdatedAtEpochMs: Long,
    ) {
        updateCaptureAfterAck(id, version, SyncStatus.Synced.wire, localUpdatedAtEpochMs)
        markOutbox(operationId, "ACKNOWLEDGED")
    }

    @Transaction
    open suspend fun ackCaptureDelete(
        id: String,
        operationId: String,
    ) {
        deleteCaptureRow(id)
        markOutbox(operationId, "ACKNOWLEDGED")
    }

    @Transaction
    open suspend fun ackTimeBlockUpsert(
        id: String,
        version: Long,
        operationId: String,
        localUpdatedAtEpochMs: Long,
    ) {
        updateTimeBlockAfterAck(id, version, SyncStatus.Synced.wire, localUpdatedAtEpochMs)
        markOutbox(operationId, "ACKNOWLEDGED")
    }

    @Transaction
    open suspend fun ackTimeBlockDelete(
        id: String,
        operationId: String,
    ) {
        deleteTimeBlockRow(id)
        markOutbox(operationId, "ACKNOWLEDGED")
    }
}
