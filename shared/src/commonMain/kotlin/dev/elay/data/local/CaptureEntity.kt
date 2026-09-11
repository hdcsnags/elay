package dev.elay.data.local

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Mirrors contract §1 `captures` (owner-only, no sharing columns). */
@Entity(tableName = "captures")
data class CaptureEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val body: String,
    val source: String,
    val parseStatus: String,
    val capturedAtEpochMs: Long,
    val clarifiedTaskId: String?,
    val version: Long,
    val syncStatus: String,
    val localUpdatedAtEpochMs: Long,
)

@Dao
interface CaptureDao {
    /** Inbox = not yet clarified into a task and not dismissed. */
    @Query(
        "SELECT * FROM captures WHERE clarifiedTaskId IS NULL AND parseStatus != 'dismissed' " +
            "ORDER BY capturedAtEpochMs DESC",
    )
    fun observeInbox(): Flow<List<CaptureEntity>>

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun get(id: String): CaptureEntity?

    @Upsert
    suspend fun upsert(entity: CaptureEntity)

    @Query(
        "UPDATE captures SET clarifiedTaskId = :taskId, parseStatus = 'parsed', " +
            "syncStatus = :syncStatus, localUpdatedAtEpochMs = :localUpdatedAtEpochMs WHERE id = :id",
    )
    suspend fun clarify(
        id: String,
        taskId: String,
        syncStatus: String,
        localUpdatedAtEpochMs: Long,
    )

    @Query("DELETE FROM captures WHERE id = :id")
    suspend fun delete(id: String)
}
