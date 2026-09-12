package dev.elay.data.local

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

/**
 * Additive optimistic-write staging surface for
 * [dev.elay.data.repository.LocalFirstPlannerRepository] (explicit grant,
 * contracts/phase1-planner.md §5 dispatch — seat R). Deliberately a NEW `@Dao`
 * rather than more abstract members on [PlannerWriteDao]: that class is
 * subclassed by `sync/impl`'s `FakePlannerWriteDao` (out of this seat's touch
 * scope), and any new abstract member there would force that file to
 * implement members it never tests. This Dao operates on the same tables —
 * Room lets any number of `@Dao`s share one `@Entity`-backed table — so a
 * write staged here is immediately visible to `GoalDao`/`TaskDao`/etc. reads.
 *
 * Each `stageXUpsert`/`stageXDelete` runs in one Room `@Transaction`: read the
 * row's current cached `version` (`null`/absent, treated as `0`, for a brand
 * new row — contract §2's insert rule) as the mutation's `expectedVersion`,
 * then apply the optimistic local write (`syncStatus` is baked into the
 * entity the repository passes in, already set to `PENDING`). This Dao never
 * touches the `outbox` table itself — see
 * [dev.elay.data.repository.LocalFirstPlannerRepository]'s kdoc for the
 * enqueue-ownership reconciliation against the merged `OutboxSyncCoordinator`.
 */
@Dao
abstract class PlannerStagingDao {
    // ---- Goals ----

    @Upsert
    abstract suspend fun upsertGoalRow(entity: GoalEntity)

    @Query("SELECT version FROM goals WHERE id = :id")
    abstract suspend fun goalVersion(id: String): Long?

    @Query("DELETE FROM goals WHERE id = :id")
    abstract suspend fun deleteGoalRow(id: String)

    @Transaction
    open suspend fun stageGoalUpsert(entity: GoalEntity): Long {
        val expectedVersion = goalVersion(entity.id) ?: 0L
        upsertGoalRow(entity)
        return expectedVersion
    }

    @Transaction
    open suspend fun stageGoalDelete(id: String): Long {
        val expectedVersion = goalVersion(id) ?: 0L
        deleteGoalRow(id)
        return expectedVersion
    }

    // ---- Milestones (no delete command — contracts/phase1-planner.md §3: cascade via goal) ----

    @Upsert
    abstract suspend fun upsertMilestoneRow(entity: MilestoneEntity)

    @Query("SELECT version FROM milestones WHERE id = :id")
    abstract suspend fun milestoneVersion(id: String): Long?

    @Transaction
    open suspend fun stageMilestoneUpsert(entity: MilestoneEntity): Long {
        val expectedVersion = milestoneVersion(entity.id) ?: 0L
        upsertMilestoneRow(entity)
        return expectedVersion
    }

    // ---- Tasks ----

    @Upsert
    abstract suspend fun upsertTaskRow(entity: TaskEntity)

    @Query("SELECT version FROM tasks WHERE id = :id")
    abstract suspend fun taskVersion(id: String): Long?

    @Query("DELETE FROM tasks WHERE id = :id")
    abstract suspend fun deleteTaskRow(id: String)

    @Transaction
    open suspend fun stageTaskUpsert(entity: TaskEntity): Long {
        val expectedVersion = taskVersion(entity.id) ?: 0L
        upsertTaskRow(entity)
        return expectedVersion
    }

    @Transaction
    open suspend fun stageTaskDelete(id: String): Long {
        val expectedVersion = taskVersion(id) ?: 0L
        deleteTaskRow(id)
        return expectedVersion
    }

    // ---- Captures ----

    @Upsert
    abstract suspend fun upsertCaptureRow(entity: CaptureEntity)

    @Query("SELECT version FROM captures WHERE id = :id")
    abstract suspend fun captureVersion(id: String): Long?

    @Query("SELECT * FROM captures WHERE id = :id")
    abstract suspend fun captureRow(id: String): CaptureEntity?

    @Query(
        "UPDATE captures SET parseStatus = 'parsed', clarifiedTaskId = :taskId, " +
            "syncStatus = :syncStatus, localUpdatedAtEpochMs = :localUpdatedAtEpochMs WHERE id = :id",
    )
    abstract suspend fun clarifyCaptureRow(
        id: String,
        taskId: String,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    )

    @Query(
        "UPDATE captures SET parseStatus = 'dismissed', " +
            "syncStatus = :syncStatus, localUpdatedAtEpochMs = :localUpdatedAtEpochMs WHERE id = :id",
    )
    abstract suspend fun dismissCaptureRow(
        id: String,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    )

    @Transaction
    open suspend fun stageCaptureUpsert(entity: CaptureEntity): Long {
        val expectedVersion = captureVersion(entity.id) ?: 0L
        upsertCaptureRow(entity)
        return expectedVersion
    }

    /** Returns the post-update row (for the caller to build the capture's outbox payload), or `null` if missing. */
    @Transaction
    open suspend fun stageCaptureClarify(
        id: String,
        taskId: String,
        localUpdatedAtEpochMs: Long,
    ): CaptureEntity? {
        val before = captureRow(id) ?: return null
        clarifyCaptureRow(id, taskId, SyncStatus.Pending.wire, localUpdatedAtEpochMs)
        return before.copy(
            parseStatus = "parsed",
            clarifiedTaskId = taskId,
            syncStatus = SyncStatus.Pending.wire,
            localUpdatedAtEpochMs = localUpdatedAtEpochMs,
        )
    }

    /** Returns the post-update row (for the caller to build the capture's outbox payload), or `null` if missing. */
    @Transaction
    open suspend fun stageCaptureDismiss(
        id: String,
        localUpdatedAtEpochMs: Long,
    ): CaptureEntity? {
        val before = captureRow(id) ?: return null
        dismissCaptureRow(id, SyncStatus.Pending.wire, localUpdatedAtEpochMs)
        return before.copy(
            parseStatus = "dismissed",
            syncStatus = SyncStatus.Pending.wire,
            localUpdatedAtEpochMs = localUpdatedAtEpochMs,
        )
    }

    // ---- Time blocks ----

    @Upsert
    abstract suspend fun upsertTimeBlockRow(entity: TimeBlockEntity)

    @Query("SELECT version FROM time_blocks WHERE id = :id")
    abstract suspend fun timeBlockVersion(id: String): Long?

    @Query("DELETE FROM time_blocks WHERE id = :id")
    abstract suspend fun deleteTimeBlockRow(id: String)

    @Transaction
    open suspend fun stageTimeBlockUpsert(entity: TimeBlockEntity): Long {
        val expectedVersion = timeBlockVersion(entity.id) ?: 0L
        upsertTimeBlockRow(entity)
        return expectedVersion
    }

    @Transaction
    open suspend fun stageTimeBlockDelete(id: String): Long {
        val expectedVersion = timeBlockVersion(id) ?: 0L
        deleteTimeBlockRow(id)
        return expectedVersion
    }
}
