package dev.elay.data.local

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Mirrors contract §1 `time_blocks`. `originTz` is the IANA zone id string (ADR-006). */
@Entity(tableName = "time_blocks")
data class TimeBlockEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val taskId: String?,
    val title: String?,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long,
    val originTz: String,
    val type: String,
    val status: String,
    val recurrenceRule: String?,
    val allDay: Boolean,
    val version: Long,
    val syncStatus: String,
    val localUpdatedAtEpochMs: Long,
)

@Dao
interface TimeBlockDao {
    /** Overlap query, inclusive: a block touching [startMs] or [endMs] exactly is included. */
    @Query(
        "SELECT * FROM time_blocks WHERE startsAtEpochMs <= :endMs AND endsAtEpochMs >= :startMs " +
            "ORDER BY startsAtEpochMs",
    )
    fun observeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<TimeBlockEntity>>

    @Query("SELECT * FROM time_blocks WHERE taskId = :taskId ORDER BY startsAtEpochMs")
    fun observeForTask(taskId: String): Flow<List<TimeBlockEntity>>

    @Upsert
    suspend fun upsert(entity: TimeBlockEntity)

    @Query("DELETE FROM time_blocks WHERE id = :id")
    suspend fun delete(id: String)
}
