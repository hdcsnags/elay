package dev.elay.data.local

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Mirrors contract §1 `tasks`. `tagsJson` is a JSON-array-of-strings column
 * (mapped explicitly in `data/local/mapping`, no hidden TypeConverter).
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val goalId: String?,
    val milestoneId: String?,
    val title: String,
    val notes: String?,
    val status: String,
    val priority: Int,
    val effort: Int?,
    val estimateMinutes: Int?,
    val dueStartEpochMs: Long?,
    val dueEndEpochMs: Long?,
    val recurrenceRule: String?,
    val tagsJson: String,
    val version: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val syncStatus: String,
    val localUpdatedAtEpochMs: Long,
)

@Dao
interface TaskDao {
    /** Inclusive boundary: a task due exactly at [startMs] or exactly at [endMs] is included. */
    @Query(
        "SELECT * FROM tasks WHERE dueStartEpochMs IS NOT NULL " +
            "AND dueStartEpochMs >= :startMs AND dueStartEpochMs <= :endMs " +
            "ORDER BY dueStartEpochMs",
    )
    fun observeToday(
        startMs: Long,
        endMs: Long,
    ): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE dueStartEpochMs IS NULL ORDER BY title")
    fun observeUnscheduled(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE goalId = :goalId ORDER BY title")
    fun observeForGoal(goalId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Upsert
    suspend fun upsert(entity: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}
